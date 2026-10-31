// Project save/load, autosave, audio import
import { store, resetHistory, emit } from './state.js';
import { bytesToBase64, base64ToBytes, downloadBlob, uid } from './util.js';
import { decodeAudio, computePeaks } from './engine.js';

const AUTOSAVE_KEY = 'voxforge.autosave';

export function serialize(includeAudio = true) {
  const audio = {};
  if (includeAudio) for (const [id, f] of store.audioFiles) audio[id] = { name: f.name, data: bytesToBase64(f.bytes) };
  return JSON.stringify({ app: 'VoxForge', format: 1, project: store.project, audio });
}

export async function loadSerialized(text) {
  const data = JSON.parse(text);
  const project = data.project || data;
  if (!project || !Array.isArray(project.tracks)) throw new Error('Invalid project file');
  for (const t of project.tracks) {
    t.params = Object.assign({ pitch: [], volume: [], breath: [], tension: [], vibrato: [], gender: [] }, t.params || {});
    t.clips = t.clips || []; t.notes = t.notes || [];
    t.voice = Object.assign({ gender: 0.5, breath: 0.5, tension: 0.5, vibDepth: 0.5, vibRate: 0.5, portamento: 0.5, naturalness: 0.6, voices: 1 }, t.voice || {});
  }
  store.audioFiles.clear();
  for (const [id, a] of Object.entries(data.audio || {})) {
    const bytes = base64ToBytes(a.data);
    const f = { name: a.name, bytes, buffer: null, peaks: null };
    store.audioFiles.set(id, f);
    try { f.buffer = await decodeAudio(bytes); f.peaks = computePeaks(f.buffer); } catch (e) { console.warn('audio decode failed', e); }
  }
  store.project = project;
  store.ui.activeTrackId = project.tracks[0]?.id || null;
  store.ui.selection.clear();
  store.ui.playhead = 0;
  store.ui.dirty = false;
  resetHistory();
  emit('project'); emit('track');
}

export function saveToFile() {
  const name = (store.project.name || 'project').replace(/[^\w\- ]+/g, ' ').replace(/\s+/g, ' ').trim() || 'project';
  downloadBlob(new Blob([serialize(true)], { type: 'application/json' }), name + '.vfx');
  store.ui.dirty = false;
}

export function autosave() {
  try { localStorage.setItem(AUTOSAVE_KEY, serialize(store.audioFiles.size < 3)); }
  catch (e) { try { localStorage.setItem(AUTOSAVE_KEY, serialize(false)); } catch (e2) { /* quota */ } }
}
export function loadAutosave() {
  try { return localStorage.getItem(AUTOSAVE_KEY); } catch (e) { return null; }
}

export async function importAudioFile(file) {
  const bytes = new Uint8Array(await file.arrayBuffer());
  const buffer = await decodeAudio(bytes);
  const id = uid();
  store.audioFiles.set(id, { name: file.name, bytes, buffer, peaks: computePeaks(buffer) });
  return id;
}
