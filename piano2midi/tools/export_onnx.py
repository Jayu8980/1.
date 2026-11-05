#!/usr/bin/env python3
"""Export the hFT-Transformer checkpoint (model_016_003.pkl) to ONNX for the Android app.

The exported graph takes one log-mel window and returns the 2nd-stage (time
attention) outputs, which are the outputs the original evaluation scripts use:

  input  spec      float32 [1, 256, 192]   (n_bins, margin_b + 128 + margin_f)
  output onset     float32 [1, 128, 88]    sigmoid probabilities
  output offset    float32 [1, 128, 88]
  output mpe       float32 [1, 128, 88]    frame-level (multi-pitch) activity
  output velocity  int64   [1, 128, 88]    argmax over 128 velocity classes

Usage:
  python3 export_onnx.py --checkpoint checkpoint/MAESTRO-V3/model_016_003.pkl \
                         --out ../android/app/src/main/assets/hft_transformer.onnx
"""
import argparse
import os
import pickle
import sys

import torch
import torch.nn as nn

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)  # so pickle can resolve `model.model_spec2midi`


class _CpuUnpickler(pickle.Unpickler):
    """Unpickle a model that was pickled on a CUDA machine onto the CPU."""

    def find_class(self, module, name):
        if module == 'torch.storage' and name == '_load_from_bytes':
            import io
            return lambda b: torch.load(io.BytesIO(b), map_location='cpu', weights_only=False)
        return super().find_class(module, name)


def load_model(path):
    with open(path, 'rb') as f:
        model = _CpuUnpickler(f).load()
    model = model.to('cpu').eval()
    for m in model.modules():
        if hasattr(m, 'device'):
            m.device = 'cpu'
        for attr in ('scale', 'scale_freq', 'scale_time'):
            if hasattr(m, attr) and isinstance(getattr(m, attr), torch.Tensor):
                setattr(m, attr, getattr(m, attr).to('cpu'))
    return model


class Stage2(nn.Module):
    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, spec):
        out = self.model(spec)
        # out = onset_A, offset_A, mpe_A, velocity_A, attention, onset_B, offset_B, mpe_B, velocity_B
        return out[5], out[6], out[7], out[8].argmax(dim=3)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--checkpoint', required=True)
    ap.add_argument('--out', required=True)
    ap.add_argument('--opset', type=int, default=17)
    args = ap.parse_args()

    model = load_model(args.checkpoint)
    wrapper = Stage2(model).eval()
    dummy = torch.randn(1, 256, 192)

    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with torch.no_grad():
        torch.onnx.export(
            wrapper, (dummy,), args.out,
            input_names=['spec'],
            output_names=['onset', 'offset', 'mpe', 'velocity'],
            opset_version=args.opset,
            do_constant_folding=True,
            dynamo=False,
        )

    # Sanity check against PyTorch.
    try:
        import numpy as np
        import onnxruntime as ort
        sess = ort.InferenceSession(args.out, providers=['CPUExecutionProvider'])
        x = torch.randn(1, 256, 192) * 3 - 8
        with torch.no_grad():
            ref = wrapper(x)
        got = sess.run(None, {'spec': x.numpy()})
        for name, r, g in zip(['onset', 'offset', 'mpe'], ref[:3], got[:3]):
            print(f'{name:8s} max abs diff: {np.abs(r.numpy() - g).max():.2e}')
        agree = (ref[3].numpy() == got[3]).mean()
        print(f'velocity argmax agreement: {agree * 100:.2f}%')
    except ImportError:
        print('onnxruntime not installed, skipping verification')
    print('wrote', args.out, os.path.getsize(args.out) // 1024, 'KiB')


if __name__ == '__main__':
    main()
