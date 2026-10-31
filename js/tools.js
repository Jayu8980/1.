// Note-processing and "AI" assist tools
import { store, newTrack, tickToSec } from './state.js';
import { PPQ, uid, clamp, SCALES, inScale } from './util.js';
import { SINGERS } from './singers.js';

export function quantize(notes, snap, strength = 1) {
  if (snap <= 1) snap = PPQ / 4;
  for (const n of notes) {
    const s = Math.round(n.start / snap) * snap;
    const e = Math.max(s + snap, Math.round((n.start + n.dur) / snap) * snap);
    n.start = Math.round(n.start + (s - n.start) * strength);
    n.dur = Math.max(10, Math.round(e - n.start));
  }
}

export function humanize(notes, amount = 1) {
  for (const n of notes) {
    const j = Math.round((Math.random() - 0.5) * 24 * amount);
    n.start = Math.max(0, n.start + j);
    n.vel = clamp((n.vel ?? 0.8) + (Math.random() - 0.5) * 0.2 * amount, 0.3, 1);
  }
}

export function legato(notes) {
  const ns = notes.slice().sort((a, b) => a.start - b.start);
  for (let i = 0; i < ns.length - 1; i++) {
    const gap = ns[i + 1].start - (ns[i].start + ns[i].dur);
    if (gap > 0 && gap <= PPQ * 2) ns[i].dur += gap;
  }
}

function scaleNotes(key, scale) {
  const sc = SCALES[scale] || SCALES.major;
  const out = [];
  for (let o = -1; o < 11; o++) for (const d of sc) out.push(o * 12 + key + d);
  return out.filter((p) => p >= 0 && p <= 127);
}
export function nearestInScale(pitch, key, scale) {
  if (inScale(pitch, key, scale)) return pitch;
  for (let d = 1; d < 6; d++) { if (inScale(pitch - d, key, scale)) return pitch - d; if (inScale(pitch + d, key, scale)) return pitch + d; }
  return pitch;
}
export function fitScale(notes, key, scale) { for (const n of notes) n.pitch = nearestInScale(n.pitch, key, scale); }

export function diatonicShift(pitch, steps, key, scale) {
  const list = scaleNotes(key, scale === 'chromatic' ? 'major' : scale);
  const p = nearestInScale(pitch, key, scale === 'chromatic' ? 'major' : scale);
  const i = list.indexOf(p);
  if (i < 0) return pitch + steps * 2;
  return list[clamp(i + steps, 0, list.length - 1)] + (pitch - p);
}

export const HARMONY_TYPES = {
  'third-up': { label: '3rd above', steps: 2 },
  'third-down': { label: '3rd below', steps: -2 },
  'fifth-up': { label: '5th above', steps: 4 },
  'sixth-down': { label: '6th below', steps: -5 },
  'octave-up': { label: 'Octave above', steps: 7 },
  'octave-down': { label: 'Octave below', steps: -7 },
};

export function makeHarmony(src, type, singerId) {
  const p = store.project;
  const h = HARMONY_TYPES[type];
  const tr = newTrack('vocal', { name: `${src.name} – ${h.label}`, singer: singerId || pickHarmonySinger(src.singer) });
  tr.voice = { ...src.voice, breath: Math.min(1, src.voice.breath + 0.1) };
  tr.volume = src.volume * 0.7;
  tr.pan = type.includes('down') ? -0.35 : 0.35;
  tr.reverbSend = Math.min(1, src.reverbSend + 0.1);
  tr.notes = src.notes.map((n) => ({ ...n, id: uid(), pitch: clamp(diatonicShift(n.pitch, h.steps, p.key, p.scale), 0, 127), vel: (n.vel ?? 0.8) * 0.9 }));
  tr.params = JSON.parse(JSON.stringify(src.params));
  tr.params.pitch = [];
  return tr;
}
function pickHarmonySinger(id) {
  const i = SINGERS.findIndex((s) => s.id === id);
  return SINGERS[(i + 1) % (SINGERS.length - 1)].id;
}

// generate natural dynamics/breath curves from phrase structure
export function autoExpression(track) {
  const ns = track.notes.slice().sort((a, b) => a.start - b.start);
  if (!ns.length) return;
  const phrases = []; let cur = null;
  for (const n of ns) {
    if (cur && n.start - cur.end < PPQ / 2) { cur.notes.push(n); cur.end = Math.max(cur.end, n.start + n.dur); }
    else { cur = { start: n.start, end: n.start + n.dur, notes: [n] }; phrases.push(cur); }
  }
  const vol = [], breath = [], tension = [];
  const STEP = 15;
  let lo = 127, hi = 0; for (const n of ns) { lo = Math.min(lo, n.pitch); hi = Math.max(hi, n.pitch); }
  for (const ph of phrases) {
    const len = ph.end - ph.start;
    for (let t = Math.floor(ph.start / STEP) * STEP; t <= ph.end; t += STEP) {
      const x = (t - ph.start) / len;
      // arch shape: swell to 60% then gentle fade
      const arch = x < 0.6 ? 0.42 + 0.18 * Math.sin((x / 0.6) * Math.PI / 2) : 0.6 - 0.2 * ((x - 0.6) / 0.4);
      vol.push([t, Math.round(arch * 1000) / 1000]);
      breath.push([t, Math.round((x > 0.8 ? 0.5 + (x - 0.8) * 1.6 : x < 0.08 ? 0.7 : 0.45) * 1000) / 1000]);
      const n = ph.notes.find((q) => t >= q.start && t < q.start + q.dur) || ph.notes[ph.notes.length - 1];
      const hgt = hi > lo ? (n.pitch - lo) / (hi - lo) : 0.5;
      tension.push([t, Math.round((0.38 + hgt * 0.3) * 1000) / 1000]);
    }
  }
  track.params.volume = vol; track.params.breath = breath; track.params.tension = tension;
}

export function applyLyrics(track, syllables, startIndex = 0) {
  const ns = track.notes.slice().sort((a, b) => a.start - b.start);
  let k = 0;
  for (let i = startIndex; i < ns.length && k < syllables.length; i++, k++) ns[i].lyric = syllables[k];
  return k;
}
