#!/usr/bin/env python3
"""Export an MT3 checkpoint to two ONNX graphs for on-device greedy decoding.

  mt3_encoder.onnx   spec [1,T,512]  ->  cross_k, cross_v [L,1,6,T,64]
  mt3_decoder.onnx   token [1,1] int64, past_k, past_v [L,1,6,P,64], cross_k, cross_v
                     ->  logits [1,V], new_k, new_v [L,1,6,P+1,64]

The decoder cache grows by concatenation so ONNX Runtime output tensors can be fed
straight back as the next step's inputs (no copies through Java). The position of the
new token is P (the cache length). Weights are int8-quantized (dynamic) by default.

  python3 export_mt3_onnx.py --ckpt checkpoints/ismir2021 --out out_dir [--fp32]
"""
import argparse
import os
import sys

import numpy as np
import torch
import torch.nn as nn

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import mt3_torch as M  # noqa: E402


class Enc(nn.Module):
    def __init__(self, m):
        super().__init__()
        self.m = m

    def forward(self, spec):
        return self.m.cross_kv(self.m.encode(spec))


class Step(nn.Module):
    def __init__(self, m):
        super().__init__()
        self.m = m

    def forward(self, token, past_k, past_v, cross_k, cross_v):
        m = self.m
        pos = past_k.shape[3]
        x = m.emb(token) + m.pos[pos:pos + 1].unsqueeze(0)
        nk, nv = [], []
        for i, l in enumerate(m.dec):
            y = l.n1(x)
            q, k, v = l.sa.split(l.sa.q(y)), l.sa.split(l.sa.k(y)), l.sa.split(l.sa.v(y))
            k = torch.cat([past_k[i], k], dim=2)
            v = torch.cat([past_v[i], v], dim=2)
            nk.append(k)
            nv.append(v)
            x = x + l.sa.attend(q, k, v)
            y = l.n2(x)
            x = x + l.ca.attend(l.ca.split(l.ca.q(y)), cross_k[i], cross_v[i])
            x = x + l.mlp(l.n3(x))
        return m.logits(m.dec_norm(x))[:, 0], torch.stack(nk), torch.stack(nv)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--ckpt', required=True)
    ap.add_argument('--out', required=True)
    ap.add_argument('--fp32', action='store_true')
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    model = M.build(M.load_t5x_params(a.ckpt))
    L = len(model.dec)
    T = 256 if os.path.basename(a.ckpt.rstrip('/')) == 'mt3' else 512
    enc_p, dec_p = os.path.join(a.out, 'enc_fp32.onnx'), os.path.join(a.out, 'dec_fp32.onnx')
    with torch.no_grad():
        torch.onnx.export(Enc(model), (torch.randn(1, T, 512),), enc_p, input_names=['spec'],
                          output_names=['cross_k', 'cross_v'],
                          dynamic_axes={'spec': {1: 'T'}, 'cross_k': {3: 'T'}, 'cross_v': {3: 'T'}},
                          opset_version=17, dynamo=False)
        P = 3
        args = (torch.zeros(1, 1, dtype=torch.long), torch.randn(L, 1, 6, P, 64), torch.randn(L, 1, 6, P, 64),
                torch.randn(L, 1, 6, T, 64), torch.randn(L, 1, 6, T, 64))
        torch.onnx.export(Step(model), args, dec_p,
                          input_names=['token', 'past_k', 'past_v', 'cross_k', 'cross_v'],
                          output_names=['logits', 'new_k', 'new_v'],
                          dynamic_axes={'past_k': {3: 'P'}, 'past_v': {3: 'P'}, 'cross_k': {3: 'T'},
                                        'cross_v': {3: 'T'}, 'new_k': {3: 'P1'}, 'new_v': {3: 'P1'}},
                          opset_version=17, dynamo=False)
    if a.fp32:
        os.replace(enc_p, os.path.join(a.out, 'mt3_encoder.onnx'))
        os.replace(dec_p, os.path.join(a.out, 'mt3_decoder.onnx'))
    else:
        from onnxruntime.quantization import QuantType, quantize_dynamic
        quantize_dynamic(enc_p, os.path.join(a.out, 'mt3_encoder.onnx'), weight_type=QuantType.QInt8,
                         op_types_to_quantize=['MatMul'])
        quantize_dynamic(dec_p, os.path.join(a.out, 'mt3_decoder.onnx'), weight_type=QuantType.QInt8,
                         op_types_to_quantize=['MatMul', 'Gather'])
        os.remove(enc_p)
        os.remove(dec_p)
    for f in ('mt3_encoder.onnx', 'mt3_decoder.onnx'):
        print(f, os.path.getsize(os.path.join(a.out, f)) // 1024, 'KiB')


if __name__ == '__main__':
    main()
