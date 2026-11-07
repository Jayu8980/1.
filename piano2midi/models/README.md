# On-demand transcription models

The app downloads these files the first time you select the MuScriptor engine. The file
sizes and SHA-256 hashes are listed in `android/app/src/main/java/com/hft/piano2midi/core/engine/Engine.java`.

| Folder | Model | Files |
|---|---|---|
| `muscriptor-medium/` | MuScriptor **medium** (307M parameters, 24 layers, dim 1024) | `muscriptor.onnx` split into `.00`–`.03` (joined in the app), `ms_frontend.bin` |

`muscriptor.onnx` is the official checkpoint `MuScriptor/muscriptor-medium` from HuggingFace,
converted by `tools/muscriptor/export_muscriptor_onnx.py` into one ONNX graph (prefix plus
step decoding with a growing KV cache) and quantized to int8 with dynamic quantization.
`ms_frontend.bin` holds the checkpoint's STFT window and mel filterbank.

Licences:
- Model weights: © Kyutai and Mirelo, **CC BY-NC 4.0**, non-commercial use only
  (https://creativecommons.org/licenses/by-nc/4.0/). The converted, quantized files here are adapted from them.
- MuScriptor code: MIT License (`LICENSE-MuScriptor-code`).

Rouard et al., "MuScriptor: An Open Model for Multi-Instrument Music Transcription", 2026.
