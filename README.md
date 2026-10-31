# VoxForge Studio

A full-featured **singing voice synthesis studio** that runs entirely in the browser — in the spirit of AI vocal tools like ACE Studio. Draw a melody, type lyrics, pick a virtual singer and shape the performance. No build step, no server-side processing, no account.

## Features

**Vocal synthesis**
- Formant-based singing engine (Web Audio) with consonants, diphthongs, breath noise and natural pitch transitions (portamento, overshoot, onset scoop, drift)
- 6 virtual singers (soprano, alto, tenor, baritone, anime-style, robotic) plus **voice blending** between two singers
- Expression controls: gender, breathiness, tension, vibrato depth/rate, portamento, naturalness, ensemble/choir voices
- Style presets: Neutral, Soft, Powerful, Airy, Bright, Dark
- Lyrics in English, romaji or Japanese kana; `-` holds a vowel over several notes

**Editing**
- Piano roll with select / draw / erase / split tools, snapping, marquee selection, Alt-drag copy, resize, nudge and transpose
- In-place lyric editing (double-click a note, Tab to move to the next one) and bulk lyric entry with automatic syllable splitting
- Rendered pitch curve drawn over the notes
- Parameter lanes for pitch bend, volume, breath, tension, vibrato and gender, with drawing, reset, smooth and clear
- Undo/redo, copy/cut/paste/duplicate, context menus, keyboard shortcuts (press `?`)

**AI-style tools**
- Generate harmony (3rd/5th/6th/octave, diatonic to the project key) on a new track with a different singer
- Auto expression (generates dynamics, breath and tension curves from the phrasing)
- Humanize, quantize, legato, snap to key

**Arrangement and mixing**
- Multitrack: vocal tracks, instrument tracks (piano, strings, pad, lead, pluck, bass) and audio tracks for backing audio
- Arrangement overview, mute/solo, volume, pan, reverb send, master volume and reverb, track colors
- Transport with loop range, metronome, follow playhead, tempo and time signature

**Files**
- Save/open projects (`.vfx`, includes imported audio), autosave to the browser
- MIDI import (lyric meta events included) and export
- WAV export of the full mix or the active track (offline rendering)
- Drag and drop MIDI, audio or project files onto the window
- Installable as a PWA and works offline

## Running

The app uses ES modules, so serve the folder over HTTP:

```bash
npm start          # runs: npx http-server -c-1 -p 8080
# or
python3 -m http.server 8080
```

Then open http://localhost:8080. It can also be hosted as-is on GitHub Pages or any static host.

## Planning

See [docs/ACE_STUDIO_FEATURE_ANALYSIS.md](docs/ACE_STUDIO_FEATURE_ANALYSIS.md) for the ACE Studio feature comparison, gap analysis and roadmap.

## Project layout

| File | Purpose |
| --- | --- |
| `js/synth.js` | Vocal formant engine, instruments, mix graph |
| `js/lyrics.js` | Lyric → phoneme analysis, kana → romaji, syllable splitting |
| `js/engine.js` | Playback, note preview, offline WAV render |
| `js/pianoroll.js` | Piano-roll editor |
| `js/paramlane.js` | Parameter automation lanes |
| `js/arrange.js` | Track headers and arrangement view |
| `js/inspector.js` | Singer, voice, notes, AI tools, mix and project panels |
| `js/tools.js` | Harmony, auto expression, humanize, quantize, etc. |
| `js/midi.js`, `js/wav.js`, `js/io.js` | File formats, save/load, autosave |
| `js/state.js` | Project model, undo/redo, parameter curves |
| `js/app.js` | Menus, commands, shortcuts, transport, layout |

---

## Also in this repo: AutoTube Agents (Android)

[`youtube-agents/`](youtube-agents/README.md) is an Android app in which a team of AI agents
runs a YouTube channel on its own: research, scripting, review, on-device video production,
upload and comment replies. GitHub Actions builds the APK (see `.github/workflows/build-apk.yml`).
