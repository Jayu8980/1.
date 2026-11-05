#!/usr/bin/env python3
"""PyTorch re-implementation of MT3 (Gardner et al., ICLR 2022) for conversion to ONNX.

Loads the public T5X checkpoints (gs://mt3/checkpoints/{ismir2021,mt3}) directly from
their zarr / msgpack files, reproduces the T5.1.1 encoder-decoder from mt3/network.py
(pre-RMS-norm, gated-GELU MLP, no biases, fixed sinusoidal positions, query scaling
folded into the weights), the TF log-mel front end (mt3/spectrograms.py) and greedy
decoding plus the event-codec -> notes decoding (vocabularies / note_sequences).
"""
import gzip
import json
import math
import os

import msgpack
import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F

# ---------------------------------------------------------------- checkpoint

def _ext_hook(code, data):
    if code == 1:  # flax ndarray: msgpack (shape, dtype, buffer)
        shape, dtype, buf = msgpack.unpackb(data, raw=False)
        return np.frombuffer(buf, dtype=np.dtype(dtype)).reshape(shape)
    return msgpack.ExtType(code, data)


def load_t5x_params(ckpt_dir):
    """Returns {'encoder.layers_0.attention.query.kernel': ndarray, ...}."""
    meta = msgpack.unpackb(open(os.path.join(ckpt_dir, 'checkpoint'), 'rb').read(),
                           raw=False, ext_hook=_ext_hook, strict_map_key=False)
    target = meta['optimizer']['target']
    out = {}

    def walk(node, path):
        if isinstance(node, dict) and 'driver' in node and node.get('driver') == 'zarr':
            out[path] = _read_zarr(os.path.join(ckpt_dir, node['kvstore']['path']))
        elif isinstance(node, dict):
            for k, v in node.items():
                walk(v, path + '.' + k if path else k)
        elif isinstance(node, np.ndarray):
            out[path] = node

    walk(target, '')
    return out


def _read_zarr(d):
    z = json.load(open(os.path.join(d, '.zarray')))
    shape, chunks = z['shape'], z['chunks']
    arr = np.zeros(shape, dtype=np.dtype(z['dtype']))
    grid = [math.ceil(s / c) for s, c in zip(shape, chunks)]
    for idx in np.ndindex(*grid):
        name = z.get('dimension_separator', '.').join(str(i) for i in idx)
        raw = open(os.path.join(d, name), 'rb').read()
        if z['compressor'] and z['compressor']['id'] == 'gzip':
            raw = gzip.decompress(raw)
        block = np.frombuffer(raw, dtype=np.dtype(z['dtype'])).reshape(chunks)
        sl = tuple(slice(i * c, min((i + 1) * c, s)) for i, c, s in zip(idx, chunks, shape))
        arr[sl] = block[tuple(slice(0, s.stop - s.start) for s in sl)]
    return arr

# ---------------------------------------------------------------- model

class RMSNorm(nn.Module):
    def __init__(self, d):
        super().__init__()
        self.scale = nn.Parameter(torch.ones(d))

    def forward(self, x):
        return x * torch.rsqrt(x.pow(2).mean(-1, keepdim=True) + 1e-6) * self.scale


def sinusoidal(max_len, d):
    pe = np.zeros((max_len, d), dtype=np.float32)
    pos = np.arange(max_len)[:, None]
    scale = -np.log(10000.0) / (d // 2 - 1)
    div = np.exp(np.arange(d // 2) * scale)
    pe[:, :d // 2] = np.sin(pos * div)
    pe[:, d // 2:] = np.cos(pos * div)
    return torch.from_numpy(pe)


class Attention(nn.Module):
    def __init__(self, d=512, h=6, hd=64):
        super().__init__()
        self.h, self.hd = h, hd
        self.q = nn.Linear(d, h * hd, bias=False)
        self.k = nn.Linear(d, h * hd, bias=False)
        self.v = nn.Linear(d, h * hd, bias=False)
        self.o = nn.Linear(h * hd, d, bias=False)

    def split(self, x):
        b, t, _ = x.shape
        return x.view(b, t, self.h, self.hd).transpose(1, 2)  # b h t d

    def attend(self, q, k, v, bias=None):
        w = q @ k.transpose(-1, -2)  # no 1/sqrt(d): folded into the query weights
        if bias is not None:
            w = w + bias
        w = torch.softmax(w, dim=-1)
        y = (w @ v).transpose(1, 2).reshape(q.shape[0], -1, self.h * self.hd)
        return self.o(y)


class MLP(nn.Module):
    def __init__(self, d=512, f=1024):
        super().__init__()
        self.wi_0 = nn.Linear(d, f, bias=False)
        self.wi_1 = nn.Linear(d, f, bias=False)
        self.wo = nn.Linear(f, d, bias=False)

    def forward(self, x):
        return self.wo(F.gelu(self.wi_0(x), approximate='tanh') * self.wi_1(x))


class EncLayer(nn.Module):
    def __init__(self):
        super().__init__()
        self.n1, self.att, self.n2, self.mlp = RMSNorm(512), Attention(), RMSNorm(512), MLP()

    def forward(self, x):
        y = self.n1(x)
        x = x + self.att.attend(self.att.split(self.att.q(y)), self.att.split(self.att.k(y)), self.att.split(self.att.v(y)))
        return x + self.mlp(self.n2(x))


class DecLayer(nn.Module):
    def __init__(self):
        super().__init__()
        self.n1, self.sa, self.n2, self.ca, self.n3, self.mlp = RMSNorm(512), Attention(), RMSNorm(512), Attention(), RMSNorm(512), MLP()


class MT3(nn.Module):
    def __init__(self, vocab, n_mels=512, layers=8):
        super().__init__()
        self.proj = nn.Linear(n_mels, 512, bias=False)
        self.enc = nn.ModuleList([EncLayer() for _ in range(layers)])
        self.enc_norm = RMSNorm(512)
        self.emb = nn.Embedding(vocab, 512)
        self.dec = nn.ModuleList([DecLayer() for _ in range(layers)])
        self.dec_norm = RMSNorm(512)
        self.logits = nn.Linear(512, vocab, bias=False)
        self.register_buffer('pos', sinusoidal(2048, 512), persistent=False)

    # ---- encoder: spectrogram [1, T, 512] -> encoded [1, T, 512]
    def encode(self, spec):
        x = self.proj(spec) + self.pos[:spec.shape[1]]
        for l in self.enc:
            x = l(x)
        return self.enc_norm(x)

    # ---- cross-attention keys/values for every decoder layer, computed once per segment
    def cross_kv(self, enc):
        ks, vs = [], []
        for l in self.dec:
            ks.append(l.ca.split(l.ca.k(enc)))
            vs.append(l.ca.split(l.ca.v(enc)))
        return torch.stack(ks), torch.stack(vs)  # [L, 1, h, T, d]

    # ---- one decoding step with a self-attention KV cache of fixed size (ONNX friendly)
    def step(self, token, position, cache_k, cache_v, cross_k, cross_v):
        """token [1,1] int64, position [1] int64 (0-based),
        cache_k/v [L,1,h,MAXLEN,d] (rows >= position are ignored), cross_k/v [L,1,h,T,d].
        Returns logits [1,vocab] and the updated caches."""
        maxlen = cache_k.shape[3]
        x = self.emb(token) + self.pos[position].unsqueeze(0)  # [1,1,512]
        ar = torch.arange(maxlen, device=token.device)
        bias = torch.where(ar <= position, torch.zeros((), dtype=x.dtype), torch.full((), -1e10, dtype=x.dtype))
        bias = bias.view(1, 1, 1, maxlen)
        onehot = (ar == position).to(x.dtype).view(1, 1, maxlen, 1)
        new_k, new_v = [], []
        for i, l in enumerate(self.dec):
            y = l.n1(x)
            q, k, v = l.sa.split(l.sa.q(y)), l.sa.split(l.sa.k(y)), l.sa.split(l.sa.v(y))
            ck = cache_k[i] * (1 - onehot) + k * onehot  # write row `position`
            cv = cache_v[i] * (1 - onehot) + v * onehot
            new_k.append(ck)
            new_v.append(cv)
            x = x + l.sa.attend(q, ck, cv, bias)
            y = l.n2(x)
            x = x + l.ca.attend(l.ca.split(l.ca.q(y)), cross_k[i], cross_v[i])
            x = x + l.mlp(l.n3(x))
        logits = self.logits(self.dec_norm(x))[:, 0]
        return logits, torch.stack(new_k), torch.stack(new_v)


def build(params):
    vocab = params['decoder.token_embedder.embedding'].shape[0]
    m = MT3(vocab).eval()
    t = lambda a: torch.from_numpy(np.ascontiguousarray(a))
    lin = lambda mod, k: mod.weight.data.copy_(t(params[k]).reshape(params[k].shape[0], -1).T)
    def lin_out(mod, k):  # attention out kernel [h, d, emb] -> Linear(h*d, emb)
        a = params[k]
        mod.weight.data.copy_(t(a.reshape(-1, a.shape[-1])).T)
    with torch.no_grad():
        m.proj.weight.copy_(t(params['encoder.continuous_inputs_projection.kernel']).T)
        for i, l in enumerate(m.enc):
            p = f'encoder.layers_{i}.'
            l.n1.scale.copy_(t(params[p + 'pre_attention_layer_norm.scale']))
            l.n2.scale.copy_(t(params[p + 'pre_mlp_layer_norm.scale']))
            for n in 'qkv':
                lin(getattr(l.att, n), p + 'attention.' + {'q': 'query', 'k': 'key', 'v': 'value'}[n] + '.kernel')
            lin_out(l.att.o, p + 'attention.out.kernel')
            for n in ('wi_0', 'wi_1', 'wo'):
                lin(getattr(l.mlp, n), p + 'mlp.' + n + '.kernel')
        m.enc_norm.scale.copy_(t(params['encoder.encoder_norm.scale']))
        m.emb.weight.copy_(t(params['decoder.token_embedder.embedding']))
        for i, l in enumerate(m.dec):
            p = f'decoder.layers_{i}.'
            l.n1.scale.copy_(t(params[p + 'pre_self_attention_layer_norm.scale']))
            l.n2.scale.copy_(t(params[p + 'pre_cross_attention_layer_norm.scale']))
            l.n3.scale.copy_(t(params[p + 'pre_mlp_layer_norm.scale']))
            for att, name in ((l.sa, 'self_attention'), (l.ca, 'encoder_decoder_attention')):
                for n in 'qkv':
                    lin(getattr(att, n), p + name + '.' + {'q': 'query', 'k': 'key', 'v': 'value'}[n] + '.kernel')
                lin_out(att.o, p + name + '.out.kernel')
            for n in ('wi_0', 'wi_1', 'wo'):
                lin(getattr(l.mlp, n), p + 'mlp.' + n + '.kernel')
        m.dec_norm.scale.copy_(t(params['decoder.decoder_norm.scale']))
        m.logits.weight.copy_(t(params['decoder.logits_dense.kernel']).T)
    return m

# ---------------------------------------------------------------- front end (TF compatible)

SR, HOP, NFFT, NMEL, LO, HI = 16000, 128, 2048, 512, 20.0, 7600.0


def mel_matrix():
    """tf.signal.linear_to_mel_weight_matrix(512, 1025, 16000, 20, 7600)."""
    hz2mel = lambda f: 1127.0 * np.log1p(f / 700.0)
    nbins = NFFT // 2 + 1
    lin = np.linspace(0.0, SR / 2, nbins, dtype=np.float64)[1:]
    sm = hz2mel(lin)[:, None]
    edges = np.linspace(hz2mel(LO), hz2mel(HI), NMEL + 2)
    lower, center, upper = edges[:-2], edges[1:-1], edges[2:]
    w = np.maximum(0.0, np.minimum((sm - lower) / (center - lower), (upper - sm) / (upper - center)))
    return np.concatenate([np.zeros((1, NMEL)), w], 0).astype(np.float32)


def logmel(samples):
    """samples (float32, 16 kHz) -> [ceil(n/128), 512] log-mel, like spectral_ops.compute_logmel."""
    n = len(samples)
    frames = -(-n // HOP)
    pad = (frames - 1) * HOP + NFFT - n
    x = np.concatenate([samples, np.zeros(max(0, pad), np.float32)])
    win = (0.5 - 0.5 * np.cos(2 * np.pi * np.arange(NFFT) / NFFT)).astype(np.float32)
    idx = np.arange(NFFT)[None, :] + HOP * np.arange(frames)[:, None]
    mag = np.abs(np.fft.rfft(x[idx] * win, axis=1)).astype(np.float32)
    mel = mag @ mel_matrix()
    return np.log(np.where(mel <= 0, 1e-5, mel)).astype(np.float32)

# ---------------------------------------------------------------- vocabulary / decoding

class Codec:
    def __init__(self, num_velocity_bins):
        self.vbins = num_velocity_bins
        self.ranges = [('shift', 0, 1000), ('pitch', 0, 127), ('velocity', 0, num_velocity_bins),
                       ('tie', 0, 0), ('program', 0, 127), ('drum', 0, 127)]

    def decode(self, idx):
        off = 0
        for name, lo, hi in self.ranges:
            if off <= idx <= off + hi - lo:
                return name, lo + idx - off
            off += hi - lo + 1
        return None, None


def tokens_to_notes(segments, codec, ties):
    """segments: list of (start_time, decoded_token_ids). Port of decode_and_combine_predictions."""
    notes = []
    active = {}  # (pitch, program) -> (onset, velocity)
    cur_time = 0.0
    for si, (start, toks) in enumerate(segments):
        max_time = segments[si + 1][0] if si + 1 < len(segments) else None
        tied, tie_section = set(), ties
        cur_vel, program, steps = 100, 0, 0
        t = start
        for tok in toks:
            name, val = codec.decode(tok)
            if name is None:
                continue
            if name == 'shift':
                steps += val
                t = start + steps / 100.0
                if max_time is not None and t > max_time:
                    break
                continue
            steps = 0
            if t < cur_time:
                continue
            cur_time = t
            if name == 'pitch':
                key = (val, program)
                if tie_section:
                    if key in active and key not in tied:
                        tied.add(key)
                elif cur_vel == 0:
                    if key in active:
                        on, v = active.pop(key)
                        notes.append((on, max(t, on + 0.01), val, v, program))
                else:
                    if key in active:
                        on, v = active.pop(key)
                        notes.append((on, max(t, on + 0.01), val, v, program))
                    active[key] = (t, cur_vel)
            elif name == 'velocity':
                cur_vel = 0 if val == 0 else int(127 * val / codec.vbins)
            elif name == 'program':
                program = val
            elif name == 'tie':
                if tie_section:
                    for key in list(active):
                        if key not in tied:
                            on, v = active.pop(key)
                            notes.append((on, max(cur_time, on + 0.01), key[0], v, key[1]))
                    tie_section = False
            elif name == 'drum':
                pass
    for (p, prog), (on, v) in list(active.items()):
        cur_time = max(cur_time, on + 0.01)
    for (p, prog), (on, v) in list(active.items()):
        notes.append((on, max(cur_time, on + 0.01), p, v, prog))
    return sorted(notes)
