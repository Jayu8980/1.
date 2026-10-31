# ACE Studio Feature Analysis and Pre-Planning for VoxForge Studio

**Purpose:** list the features ACE Studio is known for, compare each one with what VoxForge already has, and set out a phased plan to close the gaps.

**Basis:** ACE Studio's publicly described features up to mid-2026: product pages, release notes and user tutorials. ACE Studio changes often, so check each row against the current release before starting work on it. Rows marked *(verify)* are ones where my information is less certain.

**Status key:** ✅ Done · 🟡 Partial · ❌ Missing · ⛔ Not possible in a browser-only app (needs a desktop component or a server)

---

## 1. Summary

| Area | ACE Studio | VoxForge today | Gap size |
| --- | --- | --- | --- |
| Voice quality | Neural (AI) singing voices trained on real singers | Formant (filter-based) synthesis: understandable, but robotic | **Large**. This is the main gap. |
| Editing workflow | Piano roll, lyrics, phonemes, parameter curves | Piano roll, lyrics, 6 parameter curves | Small to medium |
| Singer library | Large library, many languages, voice mixing | 6 built-in voices, 2-way blending | Medium |
| AI assist | Auto pitch/expression, retakes, choir, audio-to-MIDI, stem separation, voice conversion | Auto expression, harmony, humanize | Large |
| DAW integration | Plugin bridge that syncs with a DAW | Standalone; MIDI and WAV exchange only | Large (⛔ in browser) |
| Files and mixing | MIDI and audio import, WAV/stem export, multitrack | Same, plus PWA and autosave | Small |

**Conclusion:** VoxForge already matches ACE Studio's *editing workflow* closely. Closing the gap in **voice realism** and the **AI audio tools** needs a neural model running in the browser (ONNX Runtime Web / WebGPU) or an optional render server. That decision drives the rest of the roadmap.

---

## 2. Feature-by-feature matrix

### 2.1 Singers and voice

| # | ACE Studio feature | What it does | VoxForge | Browser feasibility | Priority |
| --- | --- | --- | --- | --- | --- |
| V1 | AI singer library | Many professionally recorded and trained voices, previewed and chosen from a library | 🟡 6 synthetic singer presets | Feasible with neural voice models (download size per voice: tens of MB) | P0 |
| V2 | Multilingual singing | Mandarin, Japanese, English, Spanish, Korean and others *(verify the current language list)* | 🟡 English-like spelling, romaji, kana | Needs a grapheme-to-phoneme (G2P) step and phoneme sets per language | P1 |
| V3 | Voice mixing / blending | Blend several singers into a new timbre, including changing the mix over time *(verify)* | 🟡 2-singer static blend | Easy: N-way blend plus a blend curve over time | P2 |
| V4 | Singer styles / emotions | Several singing styles per singer (soft, powerful, etc.) | 🟡 Global style presets | Easy for presets; true per-style voices need a model | P2 |
| V5 | Custom voice training | Train a personal voice from the user's own recordings *(verify)* | ❌ | ⛔ in browser (training needs a GPU server) | P3 |
| V6 | AI choir / ensemble | A group sound made from many virtual singers | 🟡 Ensemble 1–6 detuned voices | Feasible: per-voice timing, formant and pitch variation | P2 |
| V7 | AI instruments | AI-rendered solo instruments (e.g. violin) *(verify the current list)* | 🟡 Simple oscillator instruments | Sample-based or neural instruments | P3 |

### 2.2 Note, lyric and phoneme editing

| # | ACE Studio feature | VoxForge | Gap / work needed | Priority |
| --- | --- | --- | --- | --- |
| E1 | Piano roll (draw, move, resize, split, merge) | ✅ (no merge yet) | Add a merge-notes command | P1 |
| E2 | Lyric entry per note and in bulk | ✅ with automatic syllable splitting | Add per-language lyric modes | P1 |
| E3 | Phoneme view and editing | ❌ (phonemes are worked out internally but not shown) | Phoneme lane: show and override phonemes, drag consonant length and position | **P0** |
| E4 | Slur / melisma (one syllable over several notes) | ✅ via `-` | Show visually linked notes | P2 |
| E5 | Pitch curve editing (drawing directly on notes) | 🟡 Pitch is drawn in the lower lane, as cents | Pen tool on the piano roll itself; anchor-point editing; "reset to auto pitch" for a range | **P0** |
| E6 | Auto pitch / natural transitions | ✅ Portamento, overshoot, scoop, drift, vibrato | Per-note settings for transition length, vibrato start and depth | P1 |
| E7 | Parameter lanes | ✅ Pitch, Volume, Breath, Tension, Vibrato, Gender | ACE also has **Energy** and **Falsetto** *(verify)*: add them; also a lane for each of the singer blend weights | P1 |
| E8 | Retakes / alternate takes | Re-render a phrase for a different natural performance and pick the best | ❌ | Seeded random variation per phrase plus an A/B take picker | P1 |
| E9 | Tempo and time-signature changes | ACE supports tempo maps *(verify)* | ❌ (one tempo only) | Tempo map in the state model; tick-to-seconds conversion through the map | P1 |
| E10 | Clip / region based arranging | Note regions that can be moved and copied in the arrangement | 🟡 Regions are shown but can't be moved | Region model: move, copy and loop note regions | P2 |

### 2.3 AI audio tools

| # | ACE Studio feature | What it does | VoxForge | Feasibility | Priority |
| --- | --- | --- | --- | --- | --- |
| A1 | Audio-to-MIDI (vocal transcription) | Turns a sung or hummed recording into notes and pitch | ❌ | Feasible: YIN/pYIN pitch tracking (pure JS) or a CREPE-style ONNX model, plus note segmentation | **P0** |
| A2 | Stem separation / vocal removal | Splits a song into vocals and accompaniment | ❌ | Possible with a Demucs-class ONNX model through WebGPU, but heavy (model > 80 MB, slow on CPU). Optional server path. | P2 |
| A3 | Voice conversion / voice changer | Re-sings a recorded vocal in an AI singer's voice *(verify)* | ❌ | Needs a neural conversion model; WebGPU or server | P3 |
| A4 | AI harmony | Generates backing harmonies | ✅ Diatonic harmony generator | Add chord-aware harmony (follow an instrument track's chords) | P2 |
| A5 | Auto expression | Automatic dynamics and phrasing | ✅ | Add style-aware variants | P2 |
| A6 | Recording input | Record a guide vocal | ❌ | Feasible: `getUserMedia` plus an audio track | P1 |

### 2.4 Integration, mixing and files

| # | ACE Studio feature | VoxForge | Feasibility | Priority |
| --- | --- | --- | --- | --- |
| I1 | DAW plugin bridge (VST3/AU, tempo and transport sync) | ❌ | ⛔ in browser. Alternatives: Web MIDI clock sync, or a separate desktop wrapper (Tauri/Electron plus a JUCE plugin) | P3 |
| I2 | MIDI import/export with lyrics | ✅ | — | — |
| I3 | Audio import (backing tracks) | ✅ | Add clip trimming, fades and per-clip gain UI | P2 |
| I4 | WAV export (mix and per-track stems) | ✅ mix plus active track | Export all stems at once as a ZIP; also MP3/FLAC | P1 |
| I5 | Mixer (volume, pan, effects) | 🟡 Volume, pan, reverb send | Per-track EQ, compressor and delay; level meters | P2 |
| I6 | Project files | ✅ `.vfx` plus autosave | Version migration, saving recent projects in browser storage (IndexedDB) | P2 |
| I7 | Real-time playback while editing | 🟡 Schedules the whole song when play starts; edits during playback are not heard | Re-schedule only the changed phrases while playing | P1 |
| I8 | Accounts and cloud sync | ❌ | Needs a backend; not required for a local-first app | P3 |

---

## 3. Gap analysis: what really matters

1. **Voice realism (V1).** This is the main thing users judge a vocal synth on. The formant engine can't match neural voices. Options:
   - **A. In-browser neural synthesis (recommended to try first).** Use an open singing-voice model in the style of DiffSinger (an open-source singing synthesizer) with a vocoder, exported to ONNX and run with ONNX Runtime Web on WebGPU, falling back to WASM. Keeps the app private and offline. Risks: model size, first-load time, slow rendering on weak devices, and **the licence of each voice bank** (many open voice banks don't allow commercial use).
   - **B. Optional render server.** A Python service (the same model types) renders phrases; the browser streams and caches the results. Better quality and speed, but costs hosting and loses offline use.
   - **C. Hybrid.** Keep the formant engine as the instant preview, and use neural rendering in the background per phrase, cached by a hash of the phrase content. This matches how ACE re-renders only edited phrases, so editing stays responsive.
   → **Plan: C, with A as the neural back end, and B available behind a setting.**

2. **Phoneme and pitch editing (E3, E5).** These are the core "fine-tuning" tools for advanced users. They are fully feasible now and don't depend on the neural decision.

3. **Audio-to-MIDI (A1).** A key reason users choose ACE. A pure-JS pYIN version is feasible now.

4. **Retakes, tempo map and real-time re-rendering (E8, E9, I7).** Quality-of-life features that make the app feel professional.

5. **Out of scope for a web app:** a DAW plugin bridge (I1) and custom voice training (V5). Record these as "desktop edition" items.

---

## 4. Target architecture changes

```
                ┌──────────────── UI (existing) ────────────────┐
                │ piano roll · phoneme lane · param lanes · mixer│
                └───────────────┬───────────────────────────────┘
                                │ project state (+ tempo map, phoneme overrides, takes)
                ┌───────────────▼───────────────┐
                │  Phrase renderer / scheduler  │  splits song into phrases,
                │  (hash → cache)               │  re-renders only changed ones
                └───────┬───────────────┬───────┘
         instant preview │               │ background render
        ┌────────────────▼──┐     ┌──────▼─────────────────────────┐
        │ Formant engine    │     │ Neural engine (Web Worker)     │
        │ (existing synth.js)│    │ G2P → acoustic model → vocoder │
        └───────────────────┘     │ ONNX Runtime Web (WebGPU/WASM) │
                                  │ or remote render server        │
                                  └────────────────────────────────┘
        AudioBuffer cache per phrase → mix graph (existing buildSongGraph)
```

Key new modules:

| Module | Responsibility |
| --- | --- |
| `js/phonemes/` | Per-language G2P (English dictionary + rules, Japanese kana, Mandarin pinyin), phoneme timing model |
| `js/render/phraseCache.js` | Phrase hashing, render queue, AudioBuffer cache stored in IndexedDB |
| `js/render/neuralWorker.js` | Loading ONNX models, inference, vocoder |
| `js/analysis/pitchTrack.js` | pYIN pitch tracking, note segmentation for audio-to-MIDI |
| `js/tempoMap.js` | Tempo/time-signature changes, tick↔seconds conversion |
| `js/takes.js` | Seeded variation per phrase, take selection |

State model additions: `project.tempoMap[]`, `note.phonemes?` (overrides), `note.timing?` (consonant offsets), `phrase.takeSeed`, `track.blendCurve`, `params.energy`, `params.falsetto`.

---

## 5. Phased roadmap

| Phase | Scope | Features | Rough effort* |
| --- | --- | --- | --- |
| **1. Editing parity** | Everything that doesn't need ML | E3 phoneme lane, E5 pen tool for pitch on the piano roll + reset to auto, E6 per-note transition/vibrato settings, E7 Energy and Falsetto lanes, E1 merge, E9 tempo map, I7 live re-render of changed phrases, I4 export all stems as ZIP | 3–4 weeks |
| **2. Audio tools** | Signal processing in pure JS | A1 audio-to-MIDI (pYIN), A6 recording a guide vocal, E8 retakes (seeded variation), V6 better choir, I5 EQ/compressor/meters, I3 clip trimming and fades | 3 weeks |
| **3. Neural voice** | Main quality jump | Phrase cache plus neural Web Worker, one licensed open voice to prove it works, then expanding the library (V1); multilingual G2P (V2); N-way blending with blend curves (V3) | 6–10 weeks (research-heavy) |
| **4. Advanced AI** | Heavy models, optional server | A2 stem separation, A3 voice conversion, chord-aware harmony (A4), optional render server | 6+ weeks |
| **5. Desktop edition** (optional) | Beyond the browser | Tauri/Electron wrapper, DAW bridge plugin (I1), custom voice training via a server (V5) | Separate project |

*Rough estimates for one experienced developer; refine during planning for each phase.

---

## 6. Acceptance criteria (examples)

- **Phoneme lane:** every vocal note shows its phonemes; editing a phoneme or dragging a consonant boundary changes playback; undo works.
- **Pitch pen:** freehand pitch drawn on the piano roll is heard within 200 ms of releasing the mouse during playback; "reset to auto" restores the generated curve for the selected range.
- **Audio-to-MIDI:** a clean, monophonic sung WAV produces notes with ≥90% onset accuracy (±50 ms) and the correct pitch class on held notes.
- **Neural voice (Phase 3 exit):** a 30-second phrase renders in under 30 s on a mid-range laptop with WebGPU; the cached phrase plays back instantly; the voice licence allows the intended use.
- **Retakes:** each phrase offers at least 3 takes that sound different, and the chosen take is saved in the project file.

---

## 7. Risks and open decisions

| Risk / decision | Impact | Mitigation |
| --- | --- | --- |
| Voice model licensing | Blocks Phase 3 if no suitable voice is found | Shortlist voice banks with clear licences before building; consider recording and training our own voice |
| Model download size and speed on low-end devices | Poor first experience | Lazy-load per singer, cache with a service worker, keep the formant engine as a fallback |
| WebGPU availability | Neural rendering slow on some browsers | WASM fallback, optional server rendering |
| Copying ACE Studio's branding or UI too closely | Legal and reputation risk | Keep our own name, visual design and singer identities; match features, not trade dress |
| Scope creep (desktop/DAW) | Delays the core web product | Keep it as a separate Phase 5 decision |

**Decisions needed from the product owner before Phase 3:**
1. Local-only (in-browser) neural rendering, a server, or both?
2. Which languages are required at launch?
3. Free/open-source app, or commercial? (This decides which voice licences are acceptable.)

---

## 8. Recommended next step

Start **Phase 1** now; none of it depends on the open decisions. In parallel, run a **two-week prototype for Phase 3**: run one open singing model in ONNX Runtime Web inside a Web Worker on a single phrase. Measure quality, speed and download size before committing to the neural roadmap.
