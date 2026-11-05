# Piano2MIDI: polyphonic audio → MIDI on Android

An Android app that converts piano recordings into MIDI files on the device, using
Sony's **hFT-Transformer** (Hierarchical Frequency-Time Transformer, ISMIR 2023,
[arXiv:2307.04305](https://arxiv.org/abs/2307.04305)) as the transcription engine.
No server or internet connection is needed.

```
audio file ─► MediaCodec decode ─► mono ─► resample 16 kHz ─► log-mel (256 bins, hop 256)
           ─► hFT-Transformer (ONNX Runtime, 128-frame windows + 32-frame margins)
           ─► onset / offset / frame / velocity per key ─► note decoding ─► .mid
```

## Features

- Opens mp3, m4a/aac, wav, flac, ogg/opus and anything else the device decoder supports, up to 10 minutes. You can also share audio straight into the app from another app ("Convert to MIDI").
- Polyphonic transcription across all 88 piano keys, with velocity.
- Scrollable piano-roll preview, plus MIDI playback with a moving playhead.
- Adjustable onset, offset and frame thresholds and a note-end mode. Changing them re-decodes the notes instantly, without running the model again.
- Save a `.mid` file anywhere (Storage Access Framework) or share it to other apps.

## Install

Download `piano2midi.apk`, either from the build artifacts of the **Piano2MIDI APK**
GitHub Actions workflow or by building it yourself (see below). Then install it with
"install unknown apps" allowed, or with `adb install piano2midi.apk`.
Requires Android 8.0 (API 26) or newer. ABIs: arm64-v8a, armeabi-v7a, x86_64.

The APK is signed with a debug key. To publish it, sign it with your own key.

## Accuracy and speed

The Java port was checked against the original Python code (`model/amt.py`,
torchaudio features, PyTorch model) on a FluidSynth-rendered polyphonic test piece
(99 notes: chords, bass, arpeggios):

| check | result |
| --- | --- |
| ONNX vs PyTorch outputs | max abs diff 1.3e-7, velocity argmax 100 % identical |
| Java log-mel vs torchaudio | max abs diff 7e-4 where there is signal (log domain) |
| Java notes vs Python `mpe2note` | identical: 95 notes, Δt ≤ 0.05 ms, same velocities |
| vs. ground-truth MIDI (mir_eval) | onset P/R/F1 0.989 / 0.949 / **0.969**, onset+offset F1 0.825 |

Speed: on a 4-core x86 desktop CPU a 2.048 s window takes about 1.3–1.6 s, so roughly
0.7× real time. Expect about 1–3× real time on recent phones and slower on older ones.
The app shows progress and an estimated time remaining, and you can cancel a run.

The model was trained on MAESTRO (solo grand piano), so it works best on solo
piano. Other instruments are transcribed as piano notes, with lower quality.

## Building

### Gradle / Android Studio (recommended)

```bash
cd piano2midi/android
./gradlew assembleRelease     # → app/build/outputs/apk/release/app-release.apk
```

The workflow `.github/workflows/piano2midi-apk.yml` runs this on every push that
touches `piano2midi/android/**` and uploads the APK as an artifact.

### Without the Android SDK

`android/build-local.sh` builds a signed APK using only Debian/Ubuntu packages
(`aapt`, `apksigner`, `zipalign`, a JDK). It downloads `android.jar`, the ONNX Runtime
AAR and D8 on first run:

```bash
sudo apt-get install aapt apksigner zipalign openjdk-17-jdk
piano2midi/android/build-local.sh   # → piano2midi/android/build/piano2midi.apk
```

### Re-exporting the model

`app/src/main/assets/hft_transformer.onnx` is already included. To regenerate it from
the official checkpoint:

```bash
pip install torch onnx onnxruntime
wget https://github.com/sony/hFT-Transformer/releases/download/ismir2023/checkpoint.zip
unzip checkpoint.zip
python3 piano2midi/tools/export_onnx.py \
    --checkpoint checkpoint/MAESTRO-V3/model_016_003.pkl \
    --out piano2midi/android/app/src/main/assets/hft_transformer.onnx
```

The exported graph has input `spec [1,256,192]` and outputs the 2nd-stage
`onset`, `offset`, `mpe` `[1,128,88]` and `velocity` (argmax, int64). The reference
evaluation scripts use these same outputs.

### Desktop check

`tools/desktop/DesktopTranscribe.java` runs the same Java core on a desktop JVM, using
`com.microsoft.onnxruntime:onnxruntime` from Maven Central. Use it to transcribe a
WAV file or to compare results with the Python implementation.

## Project layout

| path | purpose |
| --- | --- |
| `android/app/src/main/java/com/hft/piano2midi/core/Resampler.java` | torchaudio-compatible sinc resampler |
| `.../core/FeatureExtractor.java` | STFT + slaney/htk mel filterbank + log, matching torchaudio |
| `.../core/Transcriber.java` | windowing/padding over the feature matrix (port of `AMT.transcript`) |
| `.../core/NoteDecoder.java` | peak picking and note assembly (port of `AMT.mpe2note`) |
| `.../core/MidiWriter.java` | Standard MIDI File writer |
| `.../core/OnnxModel.java` | ONNX Runtime wrapper |
| `.../AudioDecoder.java` | MediaExtractor/MediaCodec decoder → mono float PCM |
| `.../MainActivity.java`, `PianoRollView.java`, `FileShareProvider.java` | UI, preview, sharing |
| `tools/export_onnx.py`, `tools/model/` | checkpoint → ONNX export |

## License

- App code in this folder: MIT.
- hFT-Transformer source code (`tools/model/model_spec2midi.py`, and the algorithms
  ported to Java): MIT, © 2023 Sony Group Corporation. See `tools/LICENSE-CODE`.
- Model weights (`hft_transformer.onnx`, converted from `model_016_003.pkl`):
  **CC BY-NC-SA 4.0**. They were trained on MAESTRO v3.0.0, so **non-commercial use only**,
  and modified copies must be shared under the same license. See `tools/LICENSE-CHECKPOINTS.md`.

Citation: K. Toyama, T. Akama, Y. Ikemiya, Y. Takida, W.-H. Liao, Y. Mitsufuji,
"Automatic Piano Transcription with Hierarchical Frequency-Time Transformer," ISMIR 2023.
