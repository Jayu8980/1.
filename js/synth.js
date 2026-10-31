// Audio engine: formant-based singing synthesizer, instruments, mixing graph.
import { midiToFreq, clamp, smoothstep, PPQ } from './util.js';
import { FORMANTS, CONS, analyzeLyric } from './lyrics.js';
import { singerById } from './singers.js';
import { evalParam } from './state.js';

const RATE = 200; // control-curve samples per second
const F_GAINS = [1.0, 0.7, 0.45, 0.22];
const F_BW = [90, 110, 160, 220];

// ---------- voice settings ----------
export function voiceSettings(track) {
  const a = singerById(track.singer);
  const b = track.blendSinger ? singerById(track.blendSinger) : null;
  const k = b ? clamp(track.blend || 0, 0, 1) : 0;
  const mix = (key) => (b ? a[key] * (1 - k) + b[key] * k : a[key]);
  const v = track.voice;
  return {
    formant: mix('formant') * Math.pow(2, (v.gender - 0.5) * 0.5),
    brightness: mix('brightness'),
    breath: clamp(mix('breath') * (v.breath * 2), 0, 1.2),
    tension: clamp(mix('tension') + (v.tension - 0.5) * 0.8, 0, 1),
    vibDepth: mix('vibDepth') * v.vibDepth * 2,
    vibRate: Math.max(0.1, mix('vibRate') * (0.6 + v.vibRate * 0.8)),
    portamento: 0.02 + v.portamento * 0.16,
    naturalness: v.naturalness,
    voices: clamp(Math.round(v.voices || 1), 1, 6),
    wave: k > 0.5 && b ? b.wave : a.wave,
  };
}

// param functions in song seconds
export function paramFns(track, spt) {
  const P = track.params || {};
  const f = (key, def) => (t) => evalParam(P[key], t / spt, def);
  return { pitch: f('pitch', 0), volume: f('volume', 0.5), breath: f('breath', 0.5), tension: f('tension', 0.5), vibrato: f('vibrato', 0.5), gender: f('gender', 0.5) };
}

// ---------- phrase analysis ----------
export function buildPhrases(notes, spt, S) {
  const ns = notes.slice().sort((a, b) => a.start - b.start || b.pitch - a.pitch);
  const list = [];
  let prevAn = null;
  for (let i = 0; i < ns.length; i++) {
    const n = ns[i];
    const s = n.start * spt;
    let e = (n.start + n.dur) * spt;
    const next = ns[i + 1];
    if (next && next.start * spt < e) e = Math.max(s + 0.02, next.start * spt); // monophonic
    const an = analyzeLyric(n.lyric, prevAn);
    prevAn = an;
    list.push({ id: n.id, s, e, pitch: n.pitch, vel: n.vel ?? 0.8, vib: n.vib ?? 1, an });
  }
  const phrases = [];
  let cur = null;
  for (const n of list) {
    if (cur && n.s - cur.notes[cur.notes.length - 1].e < 0.06) cur.notes.push(n);
    else { cur = { notes: [n] }; phrases.push(cur); }
  }
  for (const ph of phrases) prepPhrase(ph, S);
  return phrases;
}

function prepPhrase(ph, S) {
  const ns = ph.notes;
  for (let i = 0; i < ns.length; i++) {
    const n = ns[i], prev = ns[i - 1];
    const dur = n.e - n.s;
    // onset consonants
    let D = n.an.onset.reduce((a, c) => a + CONS[c].d, 0);
    const maxD = prev ? (prev.e - prev.s) * 0.45 : 0.25;
    const scale = D > maxD ? maxD / D : 1;
    n.onsetSegs = [];
    let t = n.s - D * scale;
    for (const c of n.an.onset) { const d = CONS[c].d * scale; n.onsetSegs.push({ t0: t, t1: t + d, c }); t += d; }
    n.r = n.s - D * scale;
    // coda consonants
    let Dc = n.an.coda.reduce((a, c) => a + CONS[c].d, 0);
    const cs = Dc > dur * 0.4 ? (dur * 0.4) / Dc : 1;
    n.codaSegs = [];
    t = n.e - Dc * cs;
    n.codaStart = t;
    for (const c of n.an.coda) { const d = CONS[c].d * cs; n.codaSegs.push({ t0: t, t1: t + d, c }); t += d; }
  }
  ph.T0 = ns[0].r - 0.03;
  ph.T1 = ns[ns.length - 1].e + 0.2;
}

// Pitch (in fractional MIDI) at time t inside phrase
export function pitchAt(ph, t, S, P) {
  const ns = ph.notes;
  const nat = S.naturalness;
  let p = ns[0].pitch;
  // initial scoop
  const s0 = ns[0].s;
  p += -0.45 * nat * (t < s0 ? 1 : Math.exp(-(t - s0) / 0.06));
  let cur = ns[0];
  for (let i = 1; i < ns.length; i++) {
    const a = ns[i - 1], b = ns[i];
    const d = b.pitch - a.pitch;
    if (t >= b.r) cur = b;
    if (!d) continue;
    const P0 = Math.min(S.portamento * (1 + Math.min(Math.abs(d), 12) / 12), (a.e - a.s) * 0.5, (b.e - b.s) * 0.5);
    const c = b.s - P0 * 0.25;
    if (t < c - P0) break;
    p += d * smoothstep((t - (c - P0 * 0.5)) / Math.max(P0, 0.005));
    const y = t - (c + P0 * 0.5);
    if (y > 0) p += d * 0.12 * nat * (y / 0.05) * Math.exp(1 - y / 0.05);
  }
  // vibrato on current note
  let cents = 0;
  const dur = cur.e - cur.s;
  if (dur > 0.3 && S.vibDepth > 0) {
    const tv = t - (cur.s + Math.max(0.16, dur * 0.28));
    if (tv > 0 && t < cur.e + 0.08) {
      const fade = Math.min(1, tv / 0.3) * (t > cur.e ? Math.max(0, 1 - (t - cur.e) / 0.08) : 1);
      cents += S.vibDepth * cur.vib * P.vibrato(t) * 2 * fade * Math.sin(2 * Math.PI * S.vibRate * tv);
    }
  }
  // drift
  cents += nat * 7 * (Math.sin(2 * Math.PI * 0.7 * t + 1.3) + 0.6 * Math.sin(2 * Math.PI * 1.9 * t + 0.4) + 0.3 * Math.sin(2 * Math.PI * 3.7 * t)) / 1.9;
  cents += P.pitch(t);
  return p + cents / 100;
}

function smooth(arr, tc) {
  const a = Math.exp(-1 / (RATE * tc));
  let y = arr[0];
  for (let i = 0; i < arr.length; i++) { y = a * y + (1 - a) * arr[i]; arr[i] = y; }
  return arr;
}

// Compute all control curves for a phrase starting from max(T0, from)
export function phraseCurves(ph, S, P, from) {
  const t0 = Math.max(ph.T0, from);
  const N = Math.max(2, Math.ceil((ph.T1 - t0) * RATE) + 1);
  const freq = new Float32Array(N), A = new Float32Array(N), B = new Float32Array(N), C = new Float32Array(N);
  const CF = new Float32Array(N), LP = new Float32Array(N);
  const F = [0, 1, 2, 3].map(() => new Float32Array(N));
  const ns = ph.notes;
  let idx = 0;
  const pre = 0.25; // simulate a little before t0 for smoothing continuity
  const preN = Math.min(Math.round(pre * RATE), Math.max(0, Math.round((t0 - ph.T0) * RATE)));
  const total = N + preN;
  const tmp = { A: new Float32Array(total), B: new Float32Array(total), C: new Float32Array(total), CF: new Float32Array(total), LP: new Float32Array(total), F: [0, 1, 2, 3].map(() => new Float32Array(total)) };
  for (let j = 0; j < total; j++) {
    const t = t0 + (j - preN) / RATE;
    while (idx < ns.length - 1 && t >= ns[idx + 1].r) idx++;
    const n = ns[idx];
    const tens = clamp(S.tension + (P.tension(t) - 0.5), 0, 1);
    const g = Math.pow(2, (P.gender(t) - 0.5) * 0.6) * S.formant;
    let amp = 0, cons = 0, cf = 3000, asp = 0, lp = 900 + 9000 * tens;
    let fm = null;
    // vowel target
    let vow = n.an.vowel;
    if (n.an.vowelEnd) {
      const vs = n.s, ve = n.codaStart;
      if (t > vs + (ve - vs) * 0.55) vow = n.an.vowelEnd;
    }
    fm = FORMANTS[vow] || FORMANTS.a;
    const vel = n.vel * (0.25 + 1.5 * P.volume(t)) * (0.85 + tens * 0.3);
    const seg = (t < n.s) ? n.onsetSegs.find((q) => t >= q.t0 && t < q.t1) : (t >= n.codaStart && t < n.e ? n.codaSegs.find((q) => t >= q.t0 && t < q.t1) : null);
    if (seg) {
      const c = CONS[seg.c];
      const x = (t - seg.t0) / Math.max(1e-4, seg.t1 - seg.t0);
      switch (c.type) {
        case 'fric': amp = c.voiced ? 0.25 : 0.0; cons = c.g; cf = c.f; break;
        case 'affr': if (x < 0.3) { amp = c.voiced ? 0.1 : 0; } else { cons = c.g; cf = c.f; amp = c.voiced ? 0.2 : 0; } break;
        case 'asp': amp = 0.04; asp = 0.9; cons = c.g; cf = c.f; break;
        case 'stop': if (x < 0.6) { amp = c.voiced ? 0.12 : 0; if (c.voiced) lp = 400; } else { cons = c.g * (1 - (x - 0.6) / 0.4 * 0.6); cf = c.f; amp = c.voiced ? 0.3 : 0; } break;
        case 'nasal': amp = 0.5; lp = 500; fm = FORMANTS[c.formant]; break;
        case 'liquid': case 'glide': amp = 0.6; fm = FORMANTS[c.formant]; break;
      }
      amp *= n.vel;
    } else if (t >= n.s && t < n.e) {
      amp = vel;
      if (vow === 'm' || vow === 'n') { lp = Math.min(lp, 600); amp *= 0.7; }
    } else if (t >= n.e) {
      amp = 0; // release / gap
    }
    tmp.A[j] = amp; tmp.C[j] = cons; tmp.CF[j] = cf; tmp.LP[j] = lp;
    tmp.B[j] = asp;
    for (let k = 0; k < 4; k++) tmp.F[k][j] = fm[k] * (k < 3 ? g : Math.sqrt(g));
    if (j >= preN) freq[j - preN] = midiToFreq(pitchAt(ph, t, S, P));
  }
  smooth(tmp.A, 0.012); smooth(tmp.C, 0.006); smooth(tmp.CF, 0.01); smooth(tmp.LP, 0.02); smooth(tmp.B, 0.01);
  for (let k = 0; k < 4; k++) smooth(tmp.F[k], 0.028);
  for (let j = 0; j < N; j++) {
    const t = t0 + j / RATE;
    const jj = j + preN;
    A[j] = tmp.A[jj]; C[j] = tmp.C[jj]; CF[j] = tmp.CF[jj]; LP[j] = tmp.LP[jj];
    B[j] = tmp.B[jj] + S.breath * 2 * P.breath(t) * tmp.A[jj] * 0.35;
    for (let k = 0; k < 4; k++) F[k][j] = tmp.F[k][jj];
  }
  return { t0, N, dur: (N - 1) / RATE, freq, A, B, C, CF, LP, F };
}

// ---------- shared resources ----------
const resCache = new WeakMap();
function res(ctx) {
  let r = resCache.get(ctx);
  if (r) return r;
  const len = ctx.sampleRate * 2;
  const noise = ctx.createBuffer(1, len, ctx.sampleRate);
  const d = noise.getChannelData(0);
  for (let i = 0; i < len; i++) d[i] = Math.random() * 2 - 1;
  r = { noise, waves: {} };
  resCache.set(ctx, r);
  return r;
}
function waveFor(ctx, type, brightness) {
  const r = res(ctx);
  const key = type + ':' + brightness.toFixed(2);
  if (r.waves[key]) return r.waves[key];
  const H = 80;
  const real = new Float32Array(H), imag = new Float32Array(H);
  const tilt = 1.0 + (1 - brightness) * 0.7;
  for (let n = 1; n < H; n++) {
    if (type === 'square') imag[n] = n % 2 ? 1 / n : 0.08 / n;
    else imag[n] = 1 / Math.pow(n, tilt) * (n === 1 ? 0.7 : 1);
  }
  return (r.waves[key] = ctx.createPeriodicWave(real, imag));
}
export function makeImpulse(ctx, seconds = 2.4, decay = 3) {
  const len = Math.floor(ctx.sampleRate * seconds);
  const buf = ctx.createBuffer(2, len, ctx.sampleRate);
  for (let ch = 0; ch < 2; ch++) {
    const d = buf.getChannelData(ch);
    for (let i = 0; i < len; i++) d[i] = (Math.random() * 2 - 1) * Math.pow(1 - i / len, decay) * (i < 200 ? i / 200 : 1);
  }
  return buf;
}

function curve(param, arr, when, dur, nyq) {
  if (nyq) for (let i = 0; i < arr.length; i++) if (arr[i] > nyq) arr[i] = nyq;
  param.value = arr[0];
  param.setValueCurveAtTime(arr, when, Math.max(dur, 0.01));
}

// ---------- vocal scheduling ----------
// songStart: song-time (sec) which maps to ctx time `when`
export function scheduleVocal(ctx, out, track, spt, songStart, when, collector, songEnd = Infinity) {
  if (!track.notes.length) return;
  const S = voiceSettings(track);
  const P = paramFns(track, spt);
  const phrases = buildPhrases(track.notes, spt, S);
  for (const ph of phrases) {
    if (ph.T1 <= songStart || ph.T0 >= songEnd) continue;
    schedulePhrase(ctx, out, ph, S, P, songStart, when, collector);
  }
}

export function schedulePhrase(ctx, out, ph, S, P, songStart, when, collector) {
  const cv = phraseCurves(ph, S, P, songStart);
  const at = when + (cv.t0 - songStart);
  const end = at + cv.dur;
  const nyq = ctx.sampleRate * 0.45;
  const r = res(ctx);
  const V = S.voices;
  for (let v = 0; v < V; v++) {
    const det = V > 1 ? Math.pow(2, ((v - (V - 1) / 2) * 11) / 1200) : 1;
    const fmul = V > 1 ? 1 + (v - (V - 1) / 2) * 0.025 : 1;
    const vg = 1 / Math.sqrt(V);
    const osc = ctx.createOscillator();
    osc.setPeriodicWave(waveFor(ctx, S.wave, S.brightness));
    const fr = det === 1 ? cv.freq : cv.freq.map((x) => x * det);
    curve(osc.frequency, fr, at, cv.dur);
    const lp = ctx.createBiquadFilter(); lp.type = 'lowpass'; lp.Q.value = 0.5;
    curve(lp.frequency, cv.LP.slice(), at, cv.dur, nyq);
    const voiceGain = ctx.createGain();
    curve(voiceGain.gain, cv.A.map((x) => x * vg), at, cv.dur);
    osc.connect(lp); lp.connect(voiceGain);
    // breath noise into formant bank
    const noise = ctx.createBufferSource(); noise.buffer = r.noise; noise.loop = true;
    const breathGain = ctx.createGain();
    curve(breathGain.gain, cv.B.map((x) => x * vg * 0.6), at, cv.dur);
    noise.connect(breathGain);
    const bank = ctx.createGain(); bank.gain.value = 2.2;
    for (let k = 0; k < 4; k++) {
      const bp = ctx.createBiquadFilter(); bp.type = 'bandpass';
      const fk = fmul === 1 ? cv.F[k].slice() : cv.F[k].map((x) => x * fmul);
      curve(bp.frequency, fk, at, cv.dur, nyq);
      bp.Q.value = (FORMANTS.a[k] / F_BW[k]) * 0.9;
      const g = ctx.createGain(); g.gain.value = F_GAINS[k];
      voiceGain.connect(bp); breathGain.connect(bp); bp.connect(g); g.connect(bank);
    }
    // a little direct source for body
    const body = ctx.createGain(); body.gain.value = 0.12; voiceGain.connect(body); body.connect(bank);
    bank.connect(out);
    osc.start(at); osc.stop(end + 0.05);
    noise.start(at, Math.random()); noise.stop(end + 0.05);
    collector.push(osc, noise);
    if (v === 0) {
      // consonant noise
      const cn = ctx.createBufferSource(); cn.buffer = r.noise; cn.loop = true;
      const cf = ctx.createBiquadFilter(); cf.type = 'bandpass'; cf.Q.value = 1.2;
      curve(cf.frequency, cv.CF, at, cv.dur, nyq);
      const cg = ctx.createGain(); curve(cg.gain, cv.C.map((x) => x * 0.9), at, cv.dur);
      cn.connect(cf); cf.connect(cg); cg.connect(out);
      cn.start(at, Math.random()); cn.stop(end + 0.05);
      collector.push(cn);
    }
  }
}

// ---------- instruments ----------
export function scheduleInstrument(ctx, out, track, spt, songStart, when, collector, songEnd = Infinity) {
  for (const n of track.notes) {
    const s = n.start * spt, e = (n.start + n.dur) * spt;
    if (e + 1 < songStart || s >= songEnd) continue;
    if (s < songStart) continue; // don't retrigger notes already sounding
    playInstrumentNote(ctx, out, track.instrument, n.pitch, n.vel ?? 0.8, when + (s - songStart), e - s, collector);
  }
}

export function playInstrumentNote(ctx, out, inst, pitch, vel, at, dur, collector = []) {
  const f = midiToFreq(pitch);
  const g = ctx.createGain(); g.gain.value = 0; g.connect(out);
  const oscs = [];
  const mk = (type, freq, gain, detune = 0) => {
    const o = ctx.createOscillator(); o.type = type; o.frequency.value = freq; o.detune.value = detune;
    const og = ctx.createGain(); og.gain.value = gain; o.connect(og); oscs.push({ o, og }); return og;
  };
  let tail = 0.15;
  const v = vel * 0.35;
  switch (inst) {
    case 'piano': {
      mk('triangle', f, 0.8).connect(g); mk('sine', f * 2, 0.25).connect(g); mk('sine', f * 3, 0.08).connect(g);
      g.gain.setValueAtTime(0, at); g.gain.linearRampToValueAtTime(v, at + 0.005);
      g.gain.setTargetAtTime(v * 0.25, at + 0.01, 0.35 + 40 / f);
      g.gain.setTargetAtTime(0, at + dur, 0.08); tail = 0.5; break;
    }
    case 'strings': case 'pad': {
      const lp = ctx.createBiquadFilter(); lp.type = 'lowpass'; lp.frequency.value = inst === 'pad' ? 1400 : 2600; lp.connect(g);
      mk('sawtooth', f, 0.5, -7).connect(lp); mk('sawtooth', f, 0.5, 7).connect(lp);
      if (inst === 'pad') mk('triangle', f / 2, 0.4).connect(lp);
      const atk = inst === 'pad' ? 0.35 : 0.15, rel = inst === 'pad' ? 0.8 : 0.35;
      g.gain.setValueAtTime(0, at); g.gain.linearRampToValueAtTime(v * 0.7, at + atk);
      g.gain.setValueAtTime(v * 0.7, at + Math.max(atk, dur)); g.gain.setTargetAtTime(0, at + Math.max(atk, dur), rel / 3); tail = rel * 2; break;
    }
    case 'lead': {
      const lp = ctx.createBiquadFilter(); lp.type = 'lowpass'; lp.frequency.value = 3200; lp.Q.value = 3; lp.connect(g);
      mk('square', f, 0.4).connect(lp); mk('sawtooth', f, 0.3, 5).connect(lp);
      g.gain.setValueAtTime(0, at); g.gain.linearRampToValueAtTime(v * 0.6, at + 0.01);
      g.gain.setValueAtTime(v * 0.6, at + dur); g.gain.setTargetAtTime(0, at + dur, 0.05); tail = 0.3; break;
    }
    case 'pluck': {
      const lp = ctx.createBiquadFilter(); lp.type = 'lowpass'; lp.Q.value = 2; lp.connect(g);
      lp.frequency.setValueAtTime(f * 12, at); lp.frequency.setTargetAtTime(f * 1.5, at, 0.12);
      mk('sawtooth', f, 0.7).connect(lp);
      g.gain.setValueAtTime(0, at); g.gain.linearRampToValueAtTime(v, at + 0.003);
      g.gain.setTargetAtTime(0, at + 0.005, 0.25); tail = 1.2; break;
    }
    case 'bass': {
      const lp = ctx.createBiquadFilter(); lp.type = 'lowpass'; lp.frequency.value = 700; lp.connect(g);
      mk('sawtooth', f, 0.5).connect(lp); mk('sine', f, 0.8).connect(lp);
      g.gain.setValueAtTime(0, at); g.gain.linearRampToValueAtTime(v, at + 0.008);
      g.gain.setTargetAtTime(v * 0.6, at + 0.02, 0.2); g.gain.setTargetAtTime(0, at + dur, 0.04); tail = 0.3; break;
    }
    default: {
      mk('sine', f, 1).connect(g);
      g.gain.setValueAtTime(0, at); g.gain.linearRampToValueAtTime(v, at + 0.01); g.gain.setTargetAtTime(0, at + dur, 0.05);
    }
  }
  for (const { o } of oscs) { o.start(at); o.stop(at + dur + tail + 0.5); collector.push(o); }
}

// ---------- full mix graph ----------
// Returns { output, trackNodes }
export function buildSongGraph(ctx, destination, project, audioFiles, songStart, when, collector, opts = {}) {
  const spt = 60 / (project.bpm * PPQ);
  const master = ctx.createGain(); master.gain.value = project.masterVolume;
  const comp = ctx.createDynamicsCompressor();
  comp.threshold.value = -8; comp.ratio.value = 4; comp.knee.value = 8; comp.attack.value = 0.005; comp.release.value = 0.15;
  master.connect(comp); comp.connect(destination);
  const reverb = ctx.createConvolver(); reverb.buffer = makeImpulse(ctx, 2.6, 3.2);
  const wet = ctx.createGain(); wet.gain.value = project.reverb; reverb.connect(wet); wet.connect(master);
  const anySolo = project.tracks.some((t) => t.solo);
  const trackNodes = {};
  for (const tr of project.tracks) {
    if (opts.onlyTrack && tr.id !== opts.onlyTrack) continue;
    const tg = ctx.createGain();
    const audible = !tr.mute && (!anySolo || tr.solo);
    tg.gain.value = audible || opts.onlyTrack ? tr.volume : 0;
    const pan = ctx.createStereoPanner(); pan.pan.value = tr.pan;
    const send = ctx.createGain(); send.gain.value = tr.reverbSend;
    const input = ctx.createGain();
    input.connect(tg); tg.connect(pan); pan.connect(master); pan.connect(send); send.connect(reverb);
    trackNodes[tr.id] = { gain: tg, pan, send };
    if (!audible && !opts.onlyTrack && opts.skipSilent) continue;
    if (tr.type === 'vocal') scheduleVocal(ctx, input, tr, spt, songStart, when, collector, opts.songEnd);
    else if (tr.type === 'instrument') scheduleInstrument(ctx, input, tr, spt, songStart, when, collector, opts.songEnd);
    else if (tr.type === 'audio') {
      for (const c of tr.clips) {
        const f = audioFiles.get(c.fileId);
        if (!f || !f.buffer) continue;
        const cs = c.start * spt;
        const ce = cs + f.buffer.duration;
        if (ce <= songStart) continue;
        const src = ctx.createBufferSource(); src.buffer = f.buffer;
        const cg = ctx.createGain(); cg.gain.value = c.gain ?? 1;
        src.connect(cg); cg.connect(input);
        if (cs >= songStart) src.start(when + (cs - songStart));
        else src.start(when, songStart - cs);
        collector.push(src);
      }
    }
  }
  if (opts.metronome) {
    const beat = 60 / project.bpm * (4 / project.timeSig[1]);
    const endSec = opts.metronomeEnd || songStart + 600;
    const first = Math.ceil(songStart / beat - 1e-6);
    for (let b = first; b * beat < endSec; b++) {
      const at = when + (b * beat - songStart);
      const o = ctx.createOscillator(); const g = ctx.createGain();
      o.frequency.value = b % project.timeSig[0] === 0 ? 1600 : 1000;
      g.gain.setValueAtTime(0.25, at); g.gain.exponentialRampToValueAtTime(0.001, at + 0.05);
      o.connect(g); g.connect(comp); o.start(at); o.stop(at + 0.06); collector.push(o);
    }
  }
  return { output: comp, master, trackNodes, wet };
}
