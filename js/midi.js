import { PPQ, uid } from './util.js';

export function parseMidi(buf) {
  const d = new DataView(buf);
  let p = 0;
  const str = (n) => { let s = ''; for (let i = 0; i < n; i++) s += String.fromCharCode(d.getUint8(p + i)); p += n; return s; };
  const u32 = () => { const v = d.getUint32(p); p += 4; return v; };
  const u16 = () => { const v = d.getUint16(p); p += 2; return v; };
  const vlq = () => { let v = 0, b; do { b = d.getUint8(p++); v = (v << 7) | (b & 0x7f); } while (b & 0x80); return v; };
  if (str(4) !== 'MThd') throw new Error('Not a MIDI file');
  const hl = u32(); const format = u16(); const ntr = u16(); const div = u16(); p += hl - 6;
  if (div & 0x8000) throw new Error('SMPTE time division not supported');
  const scale = PPQ / div;
  let bpm = null; let timeSig = null;
  const tracks = [];
  for (let t = 0; t < ntr && p < d.byteLength; t++) {
    const id = str(4); const len = u32(); const end = p + len;
    if (id !== 'MTrk') { p = end; continue; }
    let tick = 0, status = 0, name = '';
    const open = {}; const notes = []; const lyrics = [];
    while (p < end) {
      tick += vlq();
      let b = d.getUint8(p);
      if (b & 0x80) { status = b; p++; }
      const type = status & 0xf0;
      if (status === 0xff) {
        const mt = d.getUint8(p++); const ml = vlq(); const ms = p;
        if (mt === 0x51 && bpm == null) bpm = 60000000 / ((d.getUint8(ms) << 16) | (d.getUint8(ms + 1) << 8) | d.getUint8(ms + 2));
        else if (mt === 0x58 && !timeSig) timeSig = [d.getUint8(ms), Math.pow(2, d.getUint8(ms + 1))];
        else if (mt === 0x03) name = new TextDecoder().decode(new Uint8Array(buf, ms, ml));
        else if (mt === 0x05 || mt === 0x01) { const txt = new TextDecoder().decode(new Uint8Array(buf, ms, ml)); if (mt === 0x05) lyrics.push({ tick, txt }); }
        p = ms + ml;
      } else if (status === 0xf0 || status === 0xf7) { const l = vlq(); p += l; }
      else if (type === 0x90 || type === 0x80) {
        const note = d.getUint8(p++), vel = d.getUint8(p++);
        const key = (status & 0x0f) * 128 + note;
        if (type === 0x90 && vel > 0) { open[key] = { tick, vel }; }
        else if (open[key]) { const o = open[key]; notes.push({ start: Math.round(o.tick * scale), dur: Math.max(10, Math.round((tick - o.tick) * scale)), pitch: note, vel: o.vel / 127 }); delete open[key]; }
      } else if (type === 0xc0 || type === 0xd0) p += 1;
      else p += 2;
    }
    p = end;
    notes.sort((a, b) => a.start - b.start);
    for (const lz of lyrics) {
      const tk = Math.round(lz.tick * scale);
      const n = notes.find((x) => Math.abs(x.start - tk) < PPQ / 16 && x.lyric == null);
      if (n) n.lyric = lz.txt.trim();
    }
    if (notes.length) tracks.push({ name: name || `MIDI ${tracks.length + 1}`, notes: notes.map((n) => ({ id: uid(), ...n, lyric: n.lyric ?? 'la' })) });
  }
  return { bpm: bpm ? Math.round(bpm * 100) / 100 : null, timeSig, tracks, format };
}

export function writeMidi(project) {
  const bytes = [];
  const push = (...a) => bytes.push(...a);
  const u32 = (v) => push((v >>> 24) & 255, (v >>> 16) & 255, (v >>> 8) & 255, v & 255);
  const u16 = (v) => push((v >> 8) & 255, v & 255);
  const vlq = (v, out) => { const st = [v & 0x7f]; while ((v >>= 7)) st.unshift((v & 0x7f) | 0x80); out.push(...st); };
  const enc = new TextEncoder();
  const tracksOut = [];
  // tempo track
  const t0 = [];
  vlq(0, t0); const us = Math.round(60000000 / project.bpm); t0.push(0xff, 0x51, 3, (us >> 16) & 255, (us >> 8) & 255, us & 255);
  vlq(0, t0); t0.push(0xff, 0x58, 4, project.timeSig[0], Math.log2(project.timeSig[1]), 24, 8);
  vlq(0, t0); t0.push(0xff, 0x2f, 0);
  tracksOut.push(t0);
  project.tracks.filter((t) => t.type !== 'audio').forEach((tr, ti) => {
    const ev = [];
    for (const n of tr.notes) {
      if (tr.type === 'vocal' && n.lyric) ev.push({ t: n.start, o: 0, d: [0xff, 0x05, ...lenText(enc.encode(n.lyric))] });
      ev.push({ t: n.start, o: 1, d: [0x90 | (ti % 16), n.pitch, Math.max(1, Math.round((n.vel ?? 0.8) * 127))] });
      ev.push({ t: n.start + n.dur, o: -1, d: [0x80 | (ti % 16), n.pitch, 0] });
    }
    ev.sort((a, b) => a.t - b.t || a.o - b.o);
    const out = [];
    const nm = enc.encode(tr.name); vlq(0, out); out.push(0xff, 0x03, ...lenText(nm));
    let last = 0;
    for (const e of ev) { vlq(e.t - last, out); out.push(...e.d); last = e.t; }
    vlq(0, out); out.push(0xff, 0x2f, 0);
    tracksOut.push(out);
  });
  function lenText(b) { const o = []; vlq(b.length, o); return [...o, ...b]; }
  push(0x4d, 0x54, 0x68, 0x64); u32(6); u16(1); u16(tracksOut.length); u16(PPQ);
  for (const t of tracksOut) { push(0x4d, 0x54, 0x72, 0x6b); u32(t.length); for (const b of t) bytes.push(b); }
  return new Blob([new Uint8Array(bytes)], { type: 'audio/midi' });
}
