# On-demand transcription models

The app downloads these files the first time an MT3 engine is selected.

| Folder | Checkpoint | Notes |
|---|---|---|
| `mt3-piano/` | MT3 `ismir2021` (piano, with velocity) | 512-frame segments |
| `mt3-multi/` | MT3 `mt3` (multi-instrument) | 256-frame segments, note ties |

Each folder holds `mt3_encoder.onnx` and `mt3_decoder.onnx`. These were converted from the
official T5X checkpoints with `tools/mt3/export_mt3_onnx.py` and quantized to int8 with dynamic
quantization.

MT3 code and checkpoints: © Google LLC, Apache License 2.0 (see `LICENSE-MT3`).
Gardner et al., "MT3: Multi-Task Multitrack Music Transcription", ICLR 2022.
