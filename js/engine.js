import { store, tickToSec, secToTick, songEndTick, getTrack } from './state.js';
import { buildSongGraph, voiceSettings, paramFns, buildPhrases, schedulePhrase, playInstrumentNote } from './synth.js';
import { encodeWav } from './wav.js';
import { PPQ } from './util.js';

export const engine = { ctx: null, playing: false, startCtx: 0, startSec: 0, collector: [], graph: null };

export function ctx() {
  if (!engine.ctx) engine.ctx = new (window.AudioContext || window.webkitAudioContext)();
  if (engine.ctx.state === 'suspended') engine.ctx.resume();
  return engine.ctx;
}

export function play(fromTick) {
  const c = ctx();
  stopSources();
  const p = store.project;
  const startSec = tickToSec(fromTick);
  const when = c.currentTime + 0.1;
  const collector = [];
  const endSec = tickToSec(Math.max(songEndTick(), fromTick)) + 4;
  engine.graph = buildSongGraph(c, c.destination, p, store.audioFiles, startSec, when, collector, {
    metronome: store.ui.metronome, metronomeEnd: Math.max(endSec, startSec + 120),
  });
  engine.collector = collector;
  engine.startCtx = when;
  engine.startSec = startSec;
  engine.playing = true;
}

function stopSources() {
  for (const s of engine.collector) { try { s.stop(); } catch (e) { /* not started */ } }
  engine.collector = [];
  if (engine.graph) { try { engine.graph.output.disconnect(); } catch (e) { } engine.graph = null; }
}
export function stop() { stopSources(); engine.playing = false; }

export function currentTick() {
  if (!engine.playing) return store.ui.playhead;
  const t = engine.startSec + Math.max(0, engine.ctx.currentTime - engine.startCtx);
  return secToTick(t);
}

// live mixer updates while playing
export function updateLiveMix() {
  if (!engine.graph) return;
  const p = store.project;
  const anySolo = p.tracks.some((t) => t.solo);
  const now = engine.ctx.currentTime;
  for (const tr of p.tracks) {
    const n = engine.graph.trackNodes[tr.id]; if (!n) continue;
    const audible = !tr.mute && (!anySolo || tr.solo);
    n.gain.gain.setTargetAtTime(audible ? tr.volume : 0, now, 0.02);
    n.pan.pan.setTargetAtTime(tr.pan, now, 0.02);
    n.send.gain.setTargetAtTime(tr.reverbSend, now, 0.02);
  }
  engine.graph.master.gain.setTargetAtTime(p.masterVolume, now, 0.02);
  engine.graph.wet.gain.setTargetAtTime(p.reverb, now, 0.02);
}

// audition a single note through the track's voice
let previewOut = null;
export function previewNote(track, pitch, lyric = 'la', dur = 0.35) {
  if (!track || track.type === 'audio') return;
  const c = ctx();
  if (!previewOut) { previewOut = c.createGain(); previewOut.gain.value = 0.7; previewOut.connect(c.destination); }
  const at = c.currentTime + 0.03;
  if (track.type === 'instrument') { playInstrumentNote(c, previewOut, track.instrument, pitch, 0.8, at, dur); return; }
  const S = voiceSettings(track);
  S.naturalness *= 0.5;
  const spt = 60 / (store.project.bpm * PPQ);
  const P = paramFns({ params: {} }, spt);
  const phrases = buildPhrases([{ id: 'p', start: 0.3 / spt, dur: dur / spt, pitch, lyric, vel: 0.8 }], spt, S);
  schedulePhrase(c, previewOut, phrases[0], S, P, phrases[0].T0, at, []);
}

export async function renderOffline({ onlyTrack = null, sampleRate = 44100 } = {}) {
  const endTick = songEndTick();
  const dur = tickToSec(endTick) + 2.5;
  const off = new OfflineAudioContext(2, Math.ceil(dur * sampleRate), sampleRate);
  buildSongGraph(off, off.destination, store.project, store.audioFiles, 0, 0, [], { onlyTrack, skipSilent: true });
  return off.startRendering();
}

export async function exportWav(opts) {
  const buf = await renderOffline(opts);
  return encodeWav(buf);
}

export async function decodeAudio(bytes) {
  const c = ctx();
  return c.decodeAudioData(bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength));
}

export function computePeaks(buffer, n = 2000) {
  const d = buffer.getChannelData(0);
  const step = Math.max(1, Math.floor(d.length / n));
  const peaks = new Float32Array(Math.ceil(d.length / step));
  for (let i = 0; i < peaks.length; i++) {
    let m = 0; const s = i * step, e = Math.min(d.length, s + step);
    for (let j = s; j < e; j += 4) { const v = Math.abs(d[j]); if (v > m) m = v; }
    peaks[i] = m;
  }
  return peaks;
}
export { getTrack };
