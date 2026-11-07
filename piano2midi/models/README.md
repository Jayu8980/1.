# On-demand transcription models

These files are bundled into the APK. `build-local.sh` and the Gradle task `joinMuscriptorModel`
join the parts into `assets/muscriptor.onnx`, which has SHA-256
`526840874517f8f4c5fca337385a55f5cff97e7c1b984fd870cb8a25bd6aaec2`.

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
