import { PPQ, uid, TRACK_COLORS } from './util.js';

export const PARAM_KINDS = [
  { id: 'pitch', name: 'Pitch', min: -1200, max: 1200, def: 0, unit: 'ct' },
  { id: 'volume', name: 'Volume', min: 0, max: 1, def: 0.5 },
  { id: 'breath', name: 'Breath', min: 0, max: 1, def: 0.5 },
  { id: 'tension', name: 'Tension', min: 0, max: 1, def: 0.5 },
  { id: 'vibrato', name: 'Vibrato', min: 0, max: 1, def: 0.5 },
  { id: 'gender', name: 'Gender', min: 0, max: 1, def: 0.5 },
];
export const paramKind = (id) => PARAM_KINDS.find((k) => k.id === id);

const listeners = new Set();
export const store = {
  project: null,
  audioFiles: new Map(), // fileId -> { name, bytes: Uint8Array, buffer: AudioBuffer|null, peaks }
  undoStack: [],
  redoStack: [],
  ui: {
    activeTrackId: null,
    selection: new Set(),
    tool: 'select',
    snap: PPQ / 4,
    playhead: 0,
    playing: false,
    paramKind: 'pitch',
    showPitch: true,
    showGhosts: true,
    metronome: false,
    follow: true,
    view: { pxPerTick: 0.2, scrollX: 0, noteH: 16, topPitch: 84 },
    clipboard: null,
    lastDur: PPQ,
    dirty: false,
  },
};

export function subscribe(fn) { listeners.add(fn); return () => listeners.delete(fn); }
export function emit(what = 'project') { for (const fn of listeners) fn(what); }

export function newTrack(type = 'vocal', opts = {}) {
  const n = store.project ? store.project.tracks.length : 0;
  return {
    id: uid(),
    type,
    name: opts.name || (type === 'vocal' ? `Vocal ${n + 1}` : type === 'instrument' ? `Instrument ${n + 1}` : `Audio ${n + 1}`),
    color: opts.color || TRACK_COLORS[n % TRACK_COLORS.length],
    singer: opts.singer || 'aria',
    blendSinger: null,
    blend: 0,
    voice: { gender: 0.5, breath: 0.5, tension: 0.5, vibDepth: 0.5, vibRate: 0.5, portamento: 0.5, naturalness: 0.6, voices: 1 },
    instrument: opts.instrument || 'piano',
    notes: [],
    params: { pitch: [], volume: [], breath: [], tension: [], vibrato: [], gender: [] },
    clips: [],
    volume: 0.8,
    pan: 0,
    reverbSend: 0.25,
    mute: false,
    solo: false,
  };
}

export function newProject() {
  const p = {
    version: 1,
    name: 'Untitled Song',
    bpm: 110,
    timeSig: [4, 4],
    key: 0,
    scale: 'major',
    masterVolume: 0.85,
    reverb: 0.35,
    tracks: [],
  };
  store.project = p;
  p.tracks.push(newTrack('vocal', { name: 'Lead Vocal' }));
  return p;
}

export function demoProject() {
  const p = newProject();
  p.name = 'Demo – Twinkle';
  const t = p.tracks[0];
  const mel = [60, 60, 67, 67, 69, 69, 67, -1, 65, 65, 64, 64, 62, 62, 60, -1];
  const lyr = ['twin', 'kle', 'twin', 'kle', 'lit', 'tle', 'star', '', 'how', 'i', 'won', 'der', 'what', 'you', 'are', ''];
  let tick = 0;
  mel.forEach((m, i) => {
    const dur = (i % 8 === 6) ? PPQ * 2 : PPQ;
    if (m > 0) t.notes.push({ id: uid(), start: tick, dur: i % 8 === 6 ? dur - PPQ / 4 : dur, pitch: m + 7, lyric: lyr[i], vel: 0.8 });
    tick += (i % 8 === 7) ? 0 : dur;
  });
  const inst = newTrack('instrument', { name: 'Piano', instrument: 'piano' });
  inst.volume = 0.5;
  const chords = [[60, 64, 67], [60, 65, 69], [60, 64, 67], [59, 62, 67], [57, 60, 65], [55, 60, 64], [55, 59, 62], [48, 55, 60]];
  chords.forEach((c, i) => c.forEach((m) => inst.notes.push({ id: uid(), start: i * PPQ * 2, dur: PPQ * 2, pitch: m, lyric: '', vel: 0.6 })));
  p.tracks.push(inst);
  return p;
}

export const activeTrack = () => store.project.tracks.find((t) => t.id === store.ui.activeTrackId) || store.project.tracks[0] || null;
export const getTrack = (id) => store.project.tracks.find((t) => t.id === id);
export function selectedNotes() {
  const t = activeTrack();
  if (!t) return [];
  return t.notes.filter((n) => store.ui.selection.has(n.id));
}
export function setActiveTrack(id) {
  if (store.ui.activeTrackId !== id) { store.ui.activeTrackId = id; store.ui.selection.clear(); }
  emit('track');
}

export const secPerTick = () => 60 / (store.project.bpm * PPQ);
export const tickToSec = (t) => t * secPerTick();
export const secToTick = (s) => s / secPerTick();
export const ticksPerBar = () => PPQ * 4 * store.project.timeSig[0] / store.project.timeSig[1];

export function songEndTick() {
  let end = 0;
  for (const t of store.project.tracks) {
    for (const n of t.notes) end = Math.max(end, n.start + n.dur);
    for (const c of t.clips) {
      const f = store.audioFiles.get(c.fileId);
      if (f && f.buffer) end = Math.max(end, c.start + secToTick(f.buffer.duration));
    }
  }
  return end;
}

// ---------- undo / redo (snapshot based) ----------
const MAX_UNDO = 200;
let gestureSnap = null;
const snapshot = () => JSON.stringify(store.project);

export function commit(label, fn) {
  const before = snapshot();
  fn();
  const after = snapshot();
  if (before !== after) {
    store.undoStack.push({ label, snap: before });
    if (store.undoStack.length > MAX_UNDO) store.undoStack.shift();
    store.redoStack.length = 0;
    store.ui.dirty = true;
  }
  emit('project');
}
export function beginGesture() { gestureSnap = snapshot(); }
export function endGesture(label) {
  if (gestureSnap == null) return;
  if (gestureSnap !== snapshot()) {
    store.undoStack.push({ label, snap: gestureSnap });
    if (store.undoStack.length > MAX_UNDO) store.undoStack.shift();
    store.redoStack.length = 0;
    store.ui.dirty = true;
  }
  gestureSnap = null;
  emit('project');
}
function restore(snap) {
  store.project = JSON.parse(snap);
  if (!getTrack(store.ui.activeTrackId)) store.ui.activeTrackId = store.project.tracks[0]?.id || null;
  const t = activeTrack();
  const ids = new Set(t ? t.notes.map((n) => n.id) : []);
  for (const id of [...store.ui.selection]) if (!ids.has(id)) store.ui.selection.delete(id);
  emit('project'); emit('track');
}
export function undo() {
  const e = store.undoStack.pop(); if (!e) return null;
  store.redoStack.push({ label: e.label, snap: snapshot() });
  restore(e.snap); return e.label;
}
export function redo() {
  const e = store.redoStack.pop(); if (!e) return null;
  store.undoStack.push({ label: e.label, snap: snapshot() });
  restore(e.snap); return e.label;
}
export function resetHistory() { store.undoStack.length = 0; store.redoStack.length = 0; }

// ---------- parameter curves ----------
// points: [[tick, value], ...] sorted by tick
export function evalParam(points, tick, def) {
  if (!points || !points.length) return def;
  if (tick <= points[0][0]) return points[0][0] - tick < 30 ? points[0][1] : def;
  const last = points[points.length - 1];
  if (tick >= last[0]) return tick - last[0] < 30 ? last[1] : def;
  let lo = 0, hi = points.length - 1;
  while (hi - lo > 1) { const mid = (lo + hi) >> 1; if (points[mid][0] <= tick) lo = mid; else hi = mid; }
  const a = points[lo], b = points[hi];
  if (b[0] - a[0] > 60) return def; // gap = default
  return a[1] + (b[1] - a[1]) * ((tick - a[0]) / (b[0] - a[0]));
}
