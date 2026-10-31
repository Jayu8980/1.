// Piano-roll note editor (canvas)
import { store, activeTrack, commit, beginGesture, endGesture, ticksPerBar, emit, tickToSec, secPerTick } from './state.js';
import { PPQ, clamp, uid, isBlack, noteName, inScale } from './util.js';
import { previewNote, currentTick, engine } from './engine.js';
import { buildPhrases, voiceSettings, paramFns, pitchAt } from './synth.js';
import { invalidate, showContextMenu, setStatus } from './ui.js';

export const KEYW = 60;
export const RULER = 24;
let cv, g, W = 0, H = 0, dpr = 1;
let drag = null;
let hover = null;
let keyDown = null;
let lyricEdit = null;
let pitchCache = { key: '', phrases: null, S: null, P: null };

const V = () => store.ui.view;
export const tickToX = (t) => KEYW + (t - V().scrollX) * V().pxPerTick;
export const xToTick = (x) => (x - KEYW) / V().pxPerTick + V().scrollX;
const pitchToY = (p) => RULER + (V().topPitch - p) * V().noteH;
const yToPitch = (y) => Math.floor(V().topPitch - (y - RULER) / V().noteH) + 1;
const snapTick = (t, mode = 'round') => {
  const s = store.ui.snap; if (s <= 1) return Math.round(t);
  return Math[mode](t / s) * s;
};

export function initRoll(canvas) {
  cv = canvas; g = cv.getContext('2d');
  new ResizeObserver(resize).observe(cv);
  resize();
  cv.addEventListener('pointerdown', onDown);
  window.addEventListener('pointermove', onMove);
  window.addEventListener('pointerup', onUp);
  cv.addEventListener('mousemove', onHover);
  cv.addEventListener('dblclick', onDbl);
  cv.addEventListener('wheel', onWheel, { passive: false });
  cv.addEventListener('contextmenu', (e) => e.preventDefault());
  const ed = document.getElementById('lyricEditor');
  ed.addEventListener('keydown', (e) => {
    e.stopPropagation();
    if (e.key === 'Enter') { finishLyric(true); cv.focus(); }
    else if (e.key === 'Escape') { finishLyric(false); cv.focus(); }
    else if (e.key === 'Tab') { e.preventDefault(); const id = lyricEdit?.id; finishLyric(true); editNextLyric(id, e.shiftKey ? -1 : 1); }
  });
  ed.addEventListener('blur', () => finishLyric(true));
}

function resize() {
  dpr = window.devicePixelRatio || 1;
  const r = cv.getBoundingClientRect();
  W = r.width; H = r.height;
  cv.width = Math.round(W * dpr); cv.height = Math.round(H * dpr);
  invalidate('roll');
}
export const rollWidth = () => W;

// ---------- drawing ----------
export function drawRoll() {
  if (!g) return;
  const p = store.project, v = V(), tr = activeTrack();
  g.setTransform(dpr, 0, 0, dpr, 0, 0);
  g.fillStyle = '#121219'; g.fillRect(0, 0, W, H);
  const pMin = Math.floor(v.topPitch - (H - RULER) / v.noteH), pMax = Math.ceil(v.topPitch) + 1;
  // rows
  for (let pt = pMax; pt >= pMin; pt--) {
    const y = pitchToY(pt);
    const black = isBlack(pt);
    const scaleOk = inScale(pt, p.key, p.scale);
    g.fillStyle = black ? '#16161e' : '#1b1b24';
    if (!scaleOk && p.scale !== 'chromatic') g.fillStyle = black ? '#131319' : '#17171f';
    g.fillRect(KEYW, y, W - KEYW, v.noteH);
    if (pt % 12 === 0) { g.fillStyle = '#2e2e40'; g.fillRect(KEYW, y + v.noteH - 1, W - KEYW, 1); }
    else { g.fillStyle = '#20202b'; g.fillRect(KEYW, y + v.noteH - 1, W - KEYW, 1); }
  }
  // vertical grid
  const tpb = ticksPerBar();
  const beat = PPQ * 4 / p.timeSig[1];
  const t0 = xToTick(KEYW), t1 = xToTick(W);
  let step = store.ui.snap > 1 ? store.ui.snap : PPQ / 4;
  while (step * v.pxPerTick < 8) step *= 2;
  for (let t = Math.floor(t0 / step) * step; t <= t1; t += step) {
    const x = Math.round(tickToX(t)) + 0.5;
    g.fillStyle = t % tpb === 0 ? '#3a3a52' : t % beat === 0 ? '#2a2a3a' : '#1f1f2a';
    g.fillRect(x, RULER, 1, H - RULER);
  }
  // loop region
  const loop = store.ui.loop;
  if (loop && loop.on) {
    g.fillStyle = '#7c5cff12';
    g.fillRect(tickToX(loop.start), RULER, (loop.end - loop.start) * v.pxPerTick, H - RULER);
  }
  // ghost notes
  if (store.ui.showGhosts) {
    for (const ot of p.tracks) {
      if (ot === tr || ot.type === 'audio') continue;
      g.fillStyle = ot.color + '30';
      for (const n of ot.notes) {
        const x = tickToX(n.start), w = n.dur * v.pxPerTick;
        if (x > W || x + w < KEYW) continue;
        g.fillRect(x, pitchToY(n.pitch) + 2, w, v.noteH - 4);
      }
    }
  }
  // notes
  if (tr && tr.type !== 'audio') {
    g.font = '11px Inter, system-ui, sans-serif';
    g.textBaseline = 'middle';
    for (const n of tr.notes) {
      const x = tickToX(n.start), w = Math.max(3, n.dur * v.pxPerTick), y = pitchToY(n.pitch);
      if (x > W || x + w < KEYW || y > H || y + v.noteH < RULER) continue;
      const sel = store.ui.selection.has(n.id);
      g.globalAlpha = 0.55 + 0.45 * (n.vel ?? 0.8);
      g.fillStyle = sel ? lighten(tr.color) : tr.color;
      roundRect(x + 0.5, y + 1, w - 1, v.noteH - 2, 3); g.fill();
      g.globalAlpha = 1;
      if (sel) { g.strokeStyle = '#fff'; g.lineWidth = 1.5; roundRect(x + 0.5, y + 1, w - 1, v.noteH - 2, 3); g.stroke(); }
      if (tr.type === 'vocal' && w > 14 && v.noteH >= 11) {
        g.save(); g.beginPath(); g.rect(x + 3, y, w - 6, v.noteH); g.clip();
        g.fillStyle = '#fff';
        g.fillText(n.lyric || '·', x + 5, y + v.noteH / 2 + 0.5);
        g.restore();
      }
    }
    if (tr.type === 'vocal' && store.ui.showPitch && tr.notes.length) drawPitchCurve(tr, t0, t1);
  }
  // marquee
  if (drag && drag.type === 'marquee') {
    const x = Math.min(drag.x0, drag.x1), y = Math.min(drag.y0, drag.y1);
    g.fillStyle = '#7c5cff22'; g.strokeStyle = '#a48bff'; g.lineWidth = 1;
    g.fillRect(x, y, Math.abs(drag.x1 - drag.x0), Math.abs(drag.y1 - drag.y0));
    g.strokeRect(x + 0.5, y + 0.5, Math.abs(drag.x1 - drag.x0), Math.abs(drag.y1 - drag.y0));
  }
  // split hover line
  if (store.ui.tool === 'split' && hover && hover.note) {
    const x = tickToX(snapTick(hover.tick)); g.fillStyle = '#fff'; g.fillRect(x, pitchToY(hover.note.pitch), 1, v.noteH);
  }
  drawKeyboard(pMin, pMax);
  drawRuler(t0, t1, tpb, beat);
  // playhead
  const ph = currentTick();
  const px = tickToX(ph);
  if (px >= KEYW) { g.fillStyle = '#ff5c8a'; g.fillRect(Math.round(px), 0, 1.5, H); g.beginPath(); g.moveTo(px - 5, 0); g.lineTo(px + 6, 0); g.lineTo(px + 0.5, 8); g.fill(); }
  // empty hint
  const empty = document.getElementById('rollEmpty');
  if (tr && tr.type === 'audio') { empty.innerHTML = '<div>Audio track — drag clips in the arrangement above.<br>Use <b>File → Import Audio</b> to add a backing track.</div>'; empty.classList.add('show'); }
  else if (tr && !tr.notes.length) { empty.innerHTML = '<div>Press <b>2</b> for the Draw tool and click to add notes,<br>or double-click anywhere. Double-click a note to type its lyric.</div>'; empty.classList.add('show'); }
  else empty.classList.remove('show');
}

function drawPitchCurve(tr, t0, t1) {
  const spt = secPerTick();
  const key = JSON.stringify([tr.notes, tr.voice, tr.singer, tr.blend, tr.blendSinger, tr.params, store.project.bpm]);
  if (pitchCache.key !== key) {
    const S = voiceSettings(tr);
    pitchCache = { key, S, P: paramFns(tr, spt), phrases: buildPhrases(tr.notes, spt, S) };
  }
  const { S, P, phrases } = pitchCache;
  const s0 = tickToSec(t0), s1 = tickToSec(t1);
  const v = V();
  g.strokeStyle = '#ffffffb0'; g.lineWidth = 1.3;
  for (const ph of phrases) {
    const a = Math.max(ph.notes[0].s - 0.05, s0), b = Math.min(ph.notes[ph.notes.length - 1].e, s1);
    if (b <= a) continue;
    g.beginPath();
    const stepSec = 2 / (v.pxPerTick / spt);
    let first = true;
    for (let s = a; s <= b; s += stepSec) {
      const m = pitchAt(ph, s, S, P);
      const x = tickToX(s / spt), y = RULER + (v.topPitch - m + 0.5) * v.noteH;
      if (first) { g.moveTo(x, y); first = false; } else g.lineTo(x, y);
    }
    g.stroke();
  }
}

function drawKeyboard(pMin, pMax) {
  const v = V();
  g.fillStyle = '#0e0e14'; g.fillRect(0, RULER, KEYW, H - RULER);
  for (let pt = pMax; pt >= pMin; pt--) {
    const y = pitchToY(pt);
    const black = isBlack(pt);
    const pressed = keyDown && keyDown.pitch === pt;
    g.fillStyle = pressed ? '#a48bff' : black ? '#24242e' : '#e4e4ec';
    g.fillRect(black ? 0 : 0, y, black ? KEYW * 0.62 : KEYW, v.noteH - (black ? 0 : 1));
    if (pt % 12 === 0 && v.noteH >= 9) {
      g.fillStyle = '#555'; g.font = '10px Inter, system-ui, sans-serif'; g.textBaseline = 'middle';
      g.fillText(noteName(pt), KEYW - 26, y + v.noteH / 2);
    }
  }
  g.fillStyle = '#2c2c3c'; g.fillRect(KEYW - 1, RULER, 1, H - RULER);
}

function drawRuler(t0, t1, tpb, beat) {
  const v = V();
  g.fillStyle = '#16161f'; g.fillRect(0, 0, W, RULER);
  const loop = store.ui.loop;
  if (loop) {
    g.fillStyle = loop.on ? '#7c5cff88' : '#7c5cff30';
    g.fillRect(tickToX(loop.start), 0, (loop.end - loop.start) * v.pxPerTick, 6);
  }
  g.font = '10px Inter, system-ui, sans-serif'; g.textBaseline = 'middle';
  let barStep = 1;
  while (tpb * barStep * v.pxPerTick < 40) barStep *= 2;
  for (let t = Math.max(0, Math.floor(t0 / tpb) * tpb); t <= t1; t += tpb) {
    const bar = Math.round(t / tpb);
    const x = Math.round(tickToX(t)) + 0.5;
    if (bar % barStep === 0) {
      g.fillStyle = '#4a4a62'; g.fillRect(x, 8, 1, RULER - 8);
      g.fillStyle = '#9393a8'; g.fillText(String(bar + 1), x + 4, 15);
    }
    if (beat * v.pxPerTick > 14) for (let b = beat; b < tpb; b += beat) { g.fillStyle = '#33334a'; g.fillRect(Math.round(tickToX(t + b)), RULER - 6, 1, 6); }
  }
  g.fillStyle = '#2c2c3c'; g.fillRect(0, RULER - 1, W, 1);
  g.fillStyle = '#16161f'; g.fillRect(0, 0, KEYW, RULER);
}

function roundRect(x, y, w, h, r) {
  g.beginPath(); g.moveTo(x + r, y); g.arcTo(x + w, y, x + w, y + h, r); g.arcTo(x + w, y + h, x, y + h, r);
  g.arcTo(x, y + h, x, y, r); g.arcTo(x, y, x + w, y, r); g.closePath();
}
function lighten(hex) {
  const n = parseInt(hex.slice(1), 16);
  const f = (c) => Math.min(255, Math.round(c + (255 - c) * 0.35));
  return `rgb(${f(n >> 16)},${f((n >> 8) & 255)},${f(n & 255)})`;
}

// ---------- hit testing ----------
function hit(x, y) {
  const tr = activeTrack(); if (!tr || tr.type === 'audio') return null;
  const pitch = yToPitch(y);
  const tick = xToTick(x);
  for (let i = tr.notes.length - 1; i >= 0; i--) {
    const n = tr.notes[i];
    if (n.pitch !== pitch) continue;
    const nx = tickToX(n.start), nw = Math.max(3, n.dur * V().pxPerTick);
    if (x >= nx && x <= nx + nw) {
      const edge = Math.min(7, nw / 3);
      return { note: n, zone: x > nx + nw - edge ? 'right' : x < nx + edge ? 'left' : 'body', tick, pitch };
    }
  }
  return { note: null, tick, pitch };
}

function pos(e) { const r = cv.getBoundingClientRect(); return { x: e.clientX - r.left, y: e.clientY - r.top }; }

// ---------- mouse ----------
function onDown(e) {
  cv.focus();
  finishLyric(true);
  const { x, y } = pos(e);
  const tr = activeTrack(); if (!tr) return;
  const ui = store.ui;
  if (e.button === 1 || (e.button === 0 && e.code === 'Space')) { drag = { type: 'pan', x, y, sx: V().scrollX, tp: V().topPitch }; return; }
  if (y < RULER) {
    if (x < KEYW) return;
    const t = Math.max(0, snapTick(xToTick(x)));
    if (e.shiftKey || y < 7) { drag = { type: 'loop', a: t }; ui.loop = { on: true, start: t, end: t + store.ui.snap }; emit('loop'); }
    else { drag = { type: 'seek' }; seek(t); }
    return;
  }
  if (x < KEYW) {
    const pt = yToPitch(y); keyDown = { pitch: pt }; previewNote(tr, pt, 'la'); drag = { type: 'keys' }; invalidate('roll'); return;
  }
  if (tr.type === 'audio') return;
  const h = hit(x, y);
  const tool = ui.tool;
  if (e.button === 2) {
    if (tool === 'draw' || tool === 'erase') { beginGesture(); drag = { type: 'erase' }; eraseAt(x, y); return; }
    if (h.note && !ui.selection.has(h.note.id)) { ui.selection.clear(); ui.selection.add(h.note.id); emit('selection'); }
    noteContextMenu(e, h);
    return;
  }
  if (tool === 'erase') { beginGesture(); drag = { type: 'erase' }; eraseAt(x, y); return; }
  if (tool === 'split') { if (h.note) splitNote(h.note, snapTick(h.tick)); return; }
  if (h.note) {
    const n = h.note;
    if (e.shiftKey) { if (ui.selection.has(n.id)) ui.selection.delete(n.id); else ui.selection.add(n.id); emit('selection'); if (!ui.selection.has(n.id)) return; }
    else if (!ui.selection.has(n.id)) { ui.selection.clear(); ui.selection.add(n.id); emit('selection'); }
    beginGesture();
    if ((e.altKey || e.ctrlKey || e.metaKey) && h.zone === 'body') {
      // duplicate selection, keep dragging the copies
      const copies = [];
      for (const s of tr.notes.filter((q) => ui.selection.has(q.id))) copies.push({ ...s, id: uid() });
      tr.notes.push(...copies);
      ui.selection = new Set(copies.map((c) => c.id));
      const anchor = copies.find((c) => c.start === n.start && c.pitch === n.pitch) || copies[0];
      startNoteDrag('move', anchor, x, y, tr);
      return;
    }
    startNoteDrag(h.zone === 'body' ? 'move' : h.zone === 'right' ? 'resize-r' : 'resize-l', n, x, y, tr);
    previewNote(tr, n.pitch, n.lyric);
    return;
  }
  // empty space
  if (tool === 'draw') {
    beginGesture();
    const start = Math.max(0, snapTick(h.tick, 'floor'));
    const n = { id: uid(), start, dur: ui.lastDur, pitch: h.pitch, lyric: tr.type === 'vocal' ? defaultLyric(tr, start) : '', vel: 0.8 };
    tr.notes.push(n);
    ui.selection = new Set([n.id]);
    previewNote(tr, n.pitch, n.lyric);
    startNoteDrag('resize-r', n, x, y, tr, true);
    emit('notes');
    return;
  }
  if (!e.shiftKey) { ui.selection.clear(); emit('selection'); }
  drag = { type: 'marquee', x0: x, y0: y, x1: x, y1: y, base: new Set(ui.selection) };
}

function defaultLyric(tr, start) {
  // continue a sensible default: "la"
  return 'la';
}

function startNoteDrag(type, anchor, x, y, tr, created = false) {
  const sel = tr.notes.filter((q) => store.ui.selection.has(q.id));
  drag = { type, anchor, x0: x, y0: y, created, orig: sel.map((q) => ({ n: q, start: q.start, dur: q.dur, pitch: q.pitch })), lastPitch: anchor.pitch, moved: false };
}

function onMove(e) {
  if (!drag) return;
  const { x, y } = pos(e);
  const v = V(), tr = activeTrack();
  switch (drag.type) {
    case 'pan': {
      v.scrollX = Math.max(0, drag.sx - (x - drag.x) / v.pxPerTick);
      v.topPitch = clamp(drag.tp + (y - drag.y) / v.noteH, 20, 127);
      emit('view'); break;
    }
    case 'seek': seek(Math.max(0, snapTick(xToTick(x)))); break;
    case 'loop': {
      const t = Math.max(0, snapTick(xToTick(x)));
      store.ui.loop = { on: true, start: Math.min(drag.a, t), end: Math.max(drag.a, t, Math.min(drag.a, t) + Math.max(1, store.ui.snap)) };
      emit('loop'); break;
    }
    case 'keys': {
      const pt = yToPitch(y);
      if (keyDown && pt !== keyDown.pitch) { keyDown.pitch = pt; previewNote(tr, pt, 'la'); invalidate('roll'); }
      break;
    }
    case 'erase': eraseAt(x, y); break;
    case 'marquee': {
      drag.x1 = x; drag.y1 = y;
      const ta = xToTick(Math.min(drag.x0, x)), tb = xToTick(Math.max(drag.x0, x));
      const pa = yToPitch(Math.max(drag.y0, y)), pb = yToPitch(Math.min(drag.y0, y));
      const s = new Set(drag.base);
      for (const n of tr.notes) if (n.start < tb && n.start + n.dur > ta && n.pitch >= pa && n.pitch <= pb) s.add(n.id);
      store.ui.selection = s; emit('selection'); break;
    }
    case 'move': {
      const a = drag.orig.find((o) => o.n === drag.anchor) || drag.orig[0];
      const rawT = (x - drag.x0) / v.pxPerTick;
      let dT = snapTick(a.start + rawT) - a.start;
      if (e.shiftKey) dT = 0;
      const dP = Math.round(-(y - drag.y0) / v.noteH);
      const minStart = Math.min(...drag.orig.map((o) => o.start));
      dT = Math.max(dT, -minStart);
      for (const o of drag.orig) { o.n.start = o.start + dT; o.n.pitch = clamp(o.pitch + dP, 0, 127); }
      if (dT || dP) drag.moved = true;
      if (drag.anchor.pitch !== drag.lastPitch) { drag.lastPitch = drag.anchor.pitch; previewNote(tr, drag.anchor.pitch, drag.anchor.lyric); }
      setStatus(`${noteName(drag.anchor.pitch)}  ·  start ${fmtPos(drag.anchor.start)}`);
      emit('notes'); break;
    }
    case 'resize-r': {
      const a = drag.orig.find((o) => o.n === drag.anchor) || drag.orig[0];
      const minD = store.ui.snap > 1 ? store.ui.snap : 10;
      let end = snapTick(xToTick(x));
      if (drag.created) end = Math.max(end, a.start + minD);
      const dD = end - (a.start + a.dur);
      for (const o of drag.orig) o.n.dur = Math.max(minD, o.dur + dD);
      drag.moved = true; emit('notes'); break;
    }
    case 'resize-l': {
      const a = drag.orig.find((o) => o.n === drag.anchor) || drag.orig[0];
      const minD = store.ui.snap > 1 ? store.ui.snap : 10;
      const st = snapTick(xToTick(x));
      const dS = st - a.start;
      for (const o of drag.orig) {
        const ns = clamp(o.start + dS, 0, o.start + o.dur - minD);
        o.n.dur = o.dur - (ns - o.start); o.n.start = ns;
      }
      drag.moved = true; emit('notes'); break;
    }
  }
}

function onUp() {
  if (!drag) return;
  const d = drag; drag = null;
  if (d.type === 'keys') { keyDown = null; invalidate('roll'); }
  if (d.type === 'erase') endGesture('Erase notes');
  if (d.type === 'marquee') invalidate('roll');
  if (d.type === 'move' || d.type === 'resize-r' || d.type === 'resize-l') {
    if (d.type.startsWith('resize') && d.anchor) store.ui.lastDur = d.anchor.dur;
    if (d.created) store.ui.lastDur = d.anchor.dur;
    sortNotes();
    endGesture(d.created ? 'Add note' : d.type === 'move' ? 'Move notes' : 'Resize notes');
    setStatus('');
  }
}

function onHover(e) {
  if (drag) return;
  const { x, y } = pos(e);
  if (y < RULER) { cv.style.cursor = 'pointer'; return; }
  if (x < KEYW) { cv.style.cursor = 'pointer'; return; }
  const h = hit(x, y);
  hover = h;
  const tool = store.ui.tool;
  if (!h) { cv.style.cursor = 'default'; return; }
  if (tool === 'erase') cv.style.cursor = 'not-allowed';
  else if (tool === 'split') { cv.style.cursor = h.note ? 'col-resize' : 'default'; invalidate('roll'); }
  else if (h.note) cv.style.cursor = h.zone === 'body' ? 'grab' : 'ew-resize';
  else cv.style.cursor = tool === 'draw' ? 'crosshair' : 'default';
}

function onDbl(e) {
  const { x, y } = pos(e);
  if (y < RULER || x < KEYW) return;
  const tr = activeTrack(); if (!tr || tr.type === 'audio') return;
  const h = hit(x, y);
  if (h.note) { if (tr.type === 'vocal') startLyricEdit(h.note); return; }
  if (store.ui.tool === 'select') {
    commit('Add note', () => {
      const n = { id: uid(), start: Math.max(0, snapTick(h.tick, 'floor')), dur: store.ui.lastDur, pitch: h.pitch, lyric: tr.type === 'vocal' ? 'la' : '', vel: 0.8 };
      tr.notes.push(n); sortNotes(); store.ui.selection = new Set([n.id]);
      previewNote(tr, n.pitch, n.lyric);
    });
  }
}

function onWheel(e) {
  e.preventDefault();
  const v = V();
  const { x, y } = pos(e);
  if (e.ctrlKey || e.metaKey) {
    const t = xToTick(x);
    const f = Math.exp(-e.deltaY * 0.002);
    v.pxPerTick = clamp(v.pxPerTick * f, 0.01, 2);
    v.scrollX = Math.max(0, t - (x - KEYW) / v.pxPerTick);
  } else if (e.altKey) {
    const pt = V().topPitch - (y - RULER) / v.noteH;
    v.noteH = clamp(v.noteH * Math.exp(-e.deltaY * 0.002), 6, 40);
    v.topPitch = clamp(pt + (y - RULER) / v.noteH, 20, 127);
  } else if (e.shiftKey) {
    v.scrollX = Math.max(0, v.scrollX + (e.deltaY || e.deltaX) / v.pxPerTick);
  } else {
    v.scrollX = Math.max(0, v.scrollX + e.deltaX / v.pxPerTick);
    v.topPitch = clamp(v.topPitch - e.deltaY / v.noteH / 2.5, (H - RULER) / v.noteH, 127);
  }
  emit('view');
}

function eraseAt(x, y) {
  const h = hit(x, y); if (!h || !h.note) return;
  const tr = activeTrack();
  tr.notes = tr.notes.filter((n) => n !== h.note);
  store.ui.selection.delete(h.note.id);
  emit('notes');
}

export function splitNote(n, at) {
  const tr = activeTrack();
  if (at <= n.start || at >= n.start + n.dur) return;
  commit('Split note', () => {
    const b = { ...n, id: uid(), start: at, dur: n.start + n.dur - at, lyric: tr.type === 'vocal' ? '-' : '' };
    const target = tr.notes.find((q) => q.id === n.id);
    target.dur = at - n.start;
    tr.notes.push(b); sortNotes();
  });
}

export function sortNotes() { const tr = activeTrack(); if (tr) tr.notes.sort((a, b) => a.start - b.start || a.pitch - b.pitch); }

function seek(t) {
  store.ui.playhead = t;
  if (engine.playing) { import('./app.js').then((m) => m.restartPlayback()); }
  emit('playhead');
}

function fmtPos(t) {
  const tpb = ticksPerBar(), beat = PPQ * 4 / store.project.timeSig[1];
  return `${Math.floor(t / tpb) + 1}.${Math.floor((t % tpb) / beat) + 1}.${String(Math.round(t % beat)).padStart(3, '0')}`;
}
export { fmtPos };

// ---------- lyric editing ----------
export function startLyricEdit(n) {
  const tr = activeTrack(); if (!tr || tr.type !== 'vocal') return;
  ensureVisible(n);
  const ed = document.getElementById('lyricEditor');
  lyricEdit = { id: n.id };
  ed.value = n.lyric || '';
  ed.style.left = tickToX(n.start) + 'px';
  ed.style.top = (pitchToY(n.pitch) - 2) + 'px';
  ed.style.width = Math.max(70, n.dur * V().pxPerTick) + 'px';
  ed.style.height = (V().noteH + 4) + 'px';
  ed.style.display = 'block';
  ed.focus(); ed.select();
  store.ui.selection = new Set([n.id]); emit('selection');
}
function finishLyric(save) {
  if (!lyricEdit) return;
  const ed = document.getElementById('lyricEditor');
  const id = lyricEdit.id; lyricEdit = null;
  ed.style.display = 'none';
  if (!save) return;
  const tr = activeTrack(); const n = tr && tr.notes.find((q) => q.id === id);
  if (n && n.lyric !== ed.value.trim()) commit('Edit lyric', () => { n.lyric = ed.value.trim(); });
}
function editNextLyric(id, dir) {
  const tr = activeTrack();
  const i = tr.notes.findIndex((q) => q.id === id);
  const n = tr.notes[i + dir];
  if (n) startLyricEdit(n); else cv.focus();
}

export function ensureVisible(n) {
  const v = V();
  const x = tickToX(n.start);
  if (x < KEYW || x > W - 40) v.scrollX = Math.max(0, n.start - (W - KEYW) / v.pxPerTick / 3);
  const y = pitchToY(n.pitch);
  if (y < RULER || y > H - v.noteH) v.topPitch = n.pitch + (H - RULER) / v.noteH / 2;
  emit('view');
}

export function zoomToNotes() {
  const tr = activeTrack(); const v = V();
  const notes = tr && tr.notes.length ? tr.notes : store.project.tracks.flatMap((t) => t.notes);
  if (!notes.length) { v.scrollX = 0; v.topPitch = 84; emit('view'); return; }
  let a = Infinity, b = 0, lo = 127, hi = 0;
  for (const n of notes) { a = Math.min(a, n.start); b = Math.max(b, n.start + n.dur); lo = Math.min(lo, n.pitch); hi = Math.max(hi, n.pitch); }
  v.pxPerTick = clamp((W - KEYW - 40) / Math.max(PPQ * 4, b - a), 0.01, 1);
  v.scrollX = Math.max(0, a - 20 / v.pxPerTick);
  const rows = (H - RULER) / v.noteH;
  v.topPitch = Math.min(127, Math.round((lo + hi) / 2 + rows / 2));
  emit('view');
}

export function followPlayhead(t) {
  const v = V();
  const x = tickToX(t);
  if (x > W - 30 || x < KEYW) { v.scrollX = Math.max(0, t - 20 / v.pxPerTick); emit('view'); }
}

function noteContextMenu(e, h) {
  const items = [];
  const tr = activeTrack();
  if (h.note) {
    if (tr.type === 'vocal') items.push(['Edit lyric', () => startLyricEdit(h.note)]);
    items.push(['Split here', () => splitNote(h.note, snapTick(h.tick))]);
    items.push(['Duplicate', () => import('./app.js').then((m) => m.runCmd('duplicate'))]);
    items.push(['Quantize', () => import('./app.js').then((m) => m.runCmd('quantize'))]);
    items.push(['Delete', () => import('./app.js').then((m) => m.runCmd('delete'))]);
  } else {
    items.push(['Paste here', () => { store.ui.playhead = Math.max(0, snapTick(h.tick, 'floor')); import('./app.js').then((m) => m.runCmd('paste')); }]);
    items.push(['Select all', () => import('./app.js').then((m) => m.runCmd('selectAll'))]);
  }
  showContextMenu(e.clientX, e.clientY, items);
}
