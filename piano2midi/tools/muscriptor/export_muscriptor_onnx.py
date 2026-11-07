#!/usr/bin/env python3
"""Export a MuScriptor checkpoint (Kyutai x Mirelo) to one ONNX graph for on-device greedy decoding.

  muscriptor.onnx  mel [1,M,512] (log-mel frames, M = 501 on the first call of a chunk, else 0),
                   tokens [1,N] int64, past_k, past_v [L,1,P,H,64]
                   -> logits [1,card] (last position), new_k, new_v [L,1,P+M+N,H,64]

First call of a 5 s chunk: M = 501, P = 0, tokens = [DS, IG, card, tie prologue...], where
DS = card + 1 and IG = card + 2 are extra embedding rows holding the null dataset_name and
instrument_group conditions. That gives the prefix [mel projection, dataset, instrument]
that muscriptor.models.lm.LMModel.forward builds. Later calls: M = 0, N = 1. Weights are
int8-quantized (dynamic) by default. The window and mel filterbank of the front end are
written to ms_frontend.bin (see the app's Muscriptor engine).

  python3 export_muscriptor_onnx.py --weights model.safetensors --config config.json --out out_dir [--fp32]
"""
import argparse
import json
import math
import os

import torch
import torch.nn as nn
import torch.nn.functional as F
from safetensors.torch import load_file


class Muscriptor(nn.Module):
    def __init__(self, sd, cfg):
        super().__init__()
        self.D, self.H, self.L, self.card = cfg['dim'], cfg['num_heads'], cfg['num_layers'], cfg['card']
        self.hd = self.D // self.H
        p = lambda k: nn.Parameter(sd[k], requires_grad=False)
        c = 'condition_provider.conditioners.'
        self.mel_w, self.mel_b = p(c + 'self_wav.output_proj.weight'), p(c + 'self_wav.output_proj.bias')
        # null class conditions: tokenize(None) -> 0, forward embeds index 0 + 1
        self.register_buffer('ds_null', sd[c + 'dataset_name.embed.weight'][1].clone())
        self.register_buffer('ig_null', sd[c + 'instrument_group.embed.weight'][1].clone())
        self.emb = p('emb.0.weight' if 'emb.0.weight' in sd else 'emb.weight')
        self.out_w = p('linears.0.weight' if 'linears.0.weight' in sd else 'linear.weight')
        self.on_w, self.on_b = p('out_norm.weight'), p('out_norm.bias')
        self.layers = []
        for i in range(self.L):
            t = 'transformer.layers.%d.' % i
            l = nn.ParameterDict({k.replace('.', '_'): p(t + k) for k in (
                'self_attn.in_proj_weight', 'self_attn.out_proj.weight', 'norm1.weight', 'norm1.bias',
                'norm2.weight', 'norm2.bias', 'linear1.weight', 'linear2.weight')})
            self.add_module('l%d' % i, l)
            self.layers.append(l)

    def pos(self, start, T):
        half = self.D // 2
        positions = (torch.arange(T, dtype=torch.float32) + start).view(-1, 1)
        adim = torch.arange(half, dtype=torch.float32).view(1, -1)
        phase = positions / (10000.0 ** (adim / (half - 1)))
        return torch.cat([torch.cos(phase), torch.sin(phase)], dim=-1)

    def run(self, x, past_k, past_v):
        T, D = x.shape[1], self.D
        start = past_k.shape[2]
        x = x + self.pos(start, T).unsqueeze(0)
        # bottom-right aligned causal mask over [past + new] keys
        qi = torch.arange(T).view(-1, 1) + start
        kj = torch.arange(T + start).view(1, -1)
        mask = torch.where(kj > qi, torch.tensor(float('-inf')), torch.tensor(0.0))
        ks, vs = [], []
        for i, l in enumerate(self.layers):
            y = F.layer_norm(x, (D,), l['norm1_weight'], l['norm1_bias'], 1e-5)
            qkv = F.linear(y, l['self_attn_in_proj_weight']).reshape(1, -1, 3, self.H, self.hd)
            q, k, v = qkv[:, :, 0], qkv[:, :, 1], qkv[:, :, 2]          # [B,T,H,hd]
            k = torch.cat([past_k[i], k], dim=1)
            v = torch.cat([past_v[i], v], dim=1)
            ks.append(k)
            vs.append(v)
            qt, kt, vt = q.transpose(1, 2), k.transpose(1, 2), v.transpose(1, 2)
            att = qt @ kt.transpose(-1, -2) / math.sqrt(self.hd) + mask
            o = (att.softmax(-1) @ vt).transpose(1, 2).reshape(1, -1, D)
            x = x + F.linear(o, l['self_attn_out_proj_weight'])
            y = F.layer_norm(x, (D,), l['norm2_weight'], l['norm2_bias'], 1e-5)
            x = x + F.linear(F.gelu(F.linear(y, l['linear1_weight'])), l['linear2_weight'])
        x = F.layer_norm(x[:, -1:], (D,), self.on_w, self.on_b, 1e-5)
        return F.linear(x, self.out_w)[:, 0], torch.stack(ks), torch.stack(vs)


class Graph(nn.Module):
    def __init__(self, m):
        super().__init__()
        self.m = m
        # token table + two rows for the null dataset / instrument conditions
        self.table = nn.Parameter(torch.cat([m.emb, m.ds_null.view(1, -1), m.ig_null.view(1, -1)]), requires_grad=False)

    def forward(self, mel, tokens, past_k, past_v):
        m = self.m
        x = torch.cat([F.linear(mel, m.mel_w, m.mel_b), F.embedding(tokens, self.table)], dim=1)
        return m.run(x, past_k, past_v)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--weights', required=True)
    ap.add_argument('--config', required=True)
    ap.add_argument('--out', required=True)
    ap.add_argument('--fp32', action='store_true')
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    cfg = json.load(open(a.config))
    m = Muscriptor(load_file(a.weights), cfg).eval()
    L, H, hd = m.L, m.H, m.hd
    tmp = os.path.join(a.out, 'ms_fp32.onnx')
    card = m.card
    with torch.no_grad():
        P = 7
        args = (torch.randn(1, 5, 512), torch.tensor([[card + 1, card + 2, card]]),
                torch.randn(L, 1, P, H, hd), torch.randn(L, 1, P, H, hd))
        torch.onnx.export(Graph(m), args, tmp, input_names=['mel', 'tokens', 'past_k', 'past_v'],
                          output_names=['logits', 'new_k', 'new_v'],
                          dynamic_axes={'mel': {1: 'M'}, 'tokens': {1: 'N'}, 'past_k': {2: 'P'}, 'past_v': {2: 'P'},
                                        'new_k': {2: 'T'}, 'new_v': {2: 'T'}},
                          opset_version=17, dynamo=False)
    dst = os.path.join(a.out, 'muscriptor.onnx')
    if a.fp32:
        os.replace(tmp, dst)
    else:
        from onnxruntime.quantization import QuantType, quantize_dynamic
        quantize_dynamic(tmp, dst, weight_type=QuantType.QInt8, op_types_to_quantize=['MatMul', 'Gather'])
        os.remove(tmp)
    # front end: periodic Hann window [2048] + dense mel filterbank [1025, 512], little-endian float32
    sd = load_file(a.weights)
    c = 'condition_provider.conditioners.self_wav.mel_spec_transform.'
    torch.cat([sd[c + 'spectrogram.window'].float().ravel(), sd[c + 'mel_scale.fb'].float().ravel()]).numpy() \
        .astype('<f4').tofile(os.path.join(a.out, 'ms_frontend.bin'))
    for f in ('muscriptor.onnx', 'ms_frontend.bin'):
        print(f, os.path.getsize(os.path.join(a.out, f)) // 1024, 'KiB')


if __name__ == '__main__':
    main()
