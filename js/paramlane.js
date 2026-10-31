// Parameter automation lane (pitch bend, volume, breath, tension, vibrato, gender)
import { store, activeTrack, beginGesture, endGesture, commit, emit, PARAM_KINDS, paramKind, evalParam, ticksPerBar } from './state.js';
import { PPQ, clamp } from './util.js';
import { KEYW, tickToX, xToTick } from './pianoroll.js';
import { invalidate } from './ui.js';
import { currentTick } from './engine.js';

let cv, g, W = 0, H = 0, dpr = 1, drag = null, hoverVal = null;
const STEP = 15; // ticks between drawn points

export function initParams(canvas, tabs) {
  cv = canvas; g = cv.getContext('2d');
  new ResizeObserver(resize).observe(cv); resize();
  renderTabs(tabs);
  cv.addEventListener('pointerdown', onDown);
  window.addEventListener('pointermove', onMove);
  window.addEventListener('pointerup', () => { if (drag) { drag = null; endGesture('Draw ' + paramKind(store.ui.paramKind).name); } });
  cv.addEventListener('contextmenu', (e) => e.preventDefault());
  cv.addEventListener('mousemove', (e) => { const r = cv.getBoundingClientRect(); hoverVal = yToVal(e.clientY - r.top); invalidate('param'); });
  cv.addEventListener('mouseleave', () => { hoverVal = null; invalidate('param'); });
  cv.addEventListener('wheel', (e) => {
    e.preventDefault();
    const v = store.ui.view;
    if (e.ctrlKey || e.metaKey) {
      const r = cv.getBoundingClientRect(); const x = e.clientX - r.left; const t = xToTick(x);
      v.pxPerTick = clamp(v.pxPerTick * Math.exp(-e.deltaY * 0.002), 0.01, 2);
      v.scrollX = Math.max(0, t - (x - KEYW) / v.pxPerTick);
    } else v.scrollX = Math.max(0, v.scrollX + (e.deltaX || e.deltaY) / v.pxPerTick);
    emit('view');
  }, { passive: false });
}

export function renderTabs(tabs = document.getElementById('paramTabs')) {
  tabs.innerHTML = '';
  for (const k of PARAM_KINDS) {
    const b = document.createElement('button');
    b.textContent = k.name; b.className = store.ui.paramKind === k.id ? 'on' : '';
    b.onclick = () => { store.ui.paramKind = k.id; renderTabs(tabs); invalidate('param'); };
    tabs.appendChild(b);
  }
  const t = document.createElement('div'); t.className = 'ptools';
  t.innerHTML = '<span>Drag to draw · Right-drag to reset</span>';
  const clr = document.createElement('button'); clr.textContent = 'Clear'; clr.className = 'chip';
  clr.onclick = () => { const tr = activeTrack(); if (tr && tr.params) commit('Clear curve', () => { tr.params[store.ui.paramKind] = []; }); };
  const smooth = document.createElement('button'); smooth.textContent = 'Smooth'; smooth.className = 'chip';
  smooth.onclick = smoothCurve;
  t.append(smooth, clr); tabs.appendChild(t);
}

function resize() {
  dpr = window.devicePixelRatio || 1;
  const r = cv.getBoundingClientRect(); W = r.width; H = r.height;
  cv.width = Math.round(W * dpr); cv.height = Math.round(H * dpr);
  invalidate('param');
}

const PAD = 8;
function valToY(v) { const k = paramKind(store.ui.paramKind); return PAD + (1 - (v - k.min) / (k.max - k.min)) * (H - PAD * 2); }
function yToVal(y) { const k = paramKind(store.ui.paramKind); return clamp(k.min + (1 - (y - PAD) / (H - PAD * 2)) * (k.max - k.min), k.min, k.max); }

export function drawParams() {
  if (!g) return;
  const tr = activeTrack(); const k = paramKind(store.ui.paramKind);
  g.setTransform(dpr, 0, 0, dpr, 0, 0);
  g.fillStyle = '#121219'; g.fillRect(0, 0, W, H);
  const t0 = xToTick(KEYW), t1 = xToTick(W);
  const tpb = ticksPerBar();
  for (let t = Math.floor(t0 / PPQ) * PPQ; t <= t1; t += PPQ) {
    g.fillStyle = t % tpb === 0 ? '#2e2e42' : '#1c1c26';
    g.fillRect(Math.round(tickToX(t)), 0, 1, H);
  }
  // horizontal guides
  g.fillStyle = '#22222e';
  if (k.id === 'pitch') { for (let c = -1200; c <= 1200; c += 300) g.fillRect(KEYW, Math.round(valToY(c)), W, 1); }
  else for (let v = 0; v <= 1; v += 0.25) g.fillRect(KEYW, Math.round(valToY(v)), W, 1);
  g.fillStyle = '#3a3a52'; g.fillRect(KEYW, Math.round(valToY(k.def)), W, 1);
  if (tr && tr.type === 'vocal') {
    // note shadows
    g.fillStyle = tr.color + '22';
    for (const n of tr.notes) { const x = tickToX(n.start); if (x > W) continue; g.fillRect(x, 0, n.dur * store.ui.view.pxPerTick, H); }
    const pts = tr.params[k.id] || [];
    // curve
    g.strokeStyle = tr.color; g.lineWidth = 1.6;
    g.fillStyle = tr.color + '40';
    const yDef = valToY(k.def);
    g.beginPath();
    let started = false;
    const step = 2;
    let runStart = null;
    for (let x = KEYW; x <= W; x += step) {
      const t = xToTick(x);
      const v = evalParam(pts, t, null);
      if (v == null) {
        if (started) { g.lineTo(x - step, yDef); g.closePath(); g.fill(); g.stroke(); g.beginPath(); started = false; }
        continue;
      }
      const y = valToY(v);
      if (!started) { g.moveTo(x, yDef); g.lineTo(x, y); started = true; runStart = x; } else g.lineTo(x, y);
    }
    if (started) { g.lineTo(W, yDef); g.closePath(); g.fill(); g.stroke(); }
  }
  // label area
  g.fillStyle = '#16161f'; g.fillRect(0, 0, KEYW, H);
  g.fillStyle = '#2c2c3c'; g.fillRect(KEYW - 1, 0, 1, H);
  g.font = '10px Inter, system-ui, sans-serif'; g.fillStyle = '#9393a8'; g.textBaseline = 'middle';
  if (k.id === 'pitch') { g.fillText('+12 st', 6, valToY(1200) + 4); g.fillText('0', 6, valToY(0)); g.fillText('-12 st', 6, valToY(-1200) - 4); }
  else { g.fillText('max', 6, valToY(1) + 4); g.fillText('def', 6, valToY(0.5)); g.fillText('min', 6, valToY(0) - 4); }
  if (hoverVal != null) { g.fillStyle = '#a48bff'; g.fillText(k.id === 'pitch' ? `${Math.round(hoverVal)} ct` : hoverVal.toFixed(2), 6, H - 10); }
  if (!tr || tr.type !== 'vocal') {
    g.fillStyle = '#66667a'; g.font = '12px Inter, system-ui, sans-serif'; g.textAlign = 'center';
    g.fillText('Parameter curves are available on vocal tracks', (W + KEYW) / 2, H / 2); g.textAlign = 'left';
  }
  const px = tickToX(currentTick());
  if (px >= KEYW) { g.fillStyle = '#ff5c8a'; g.fillRect(Math.round(px), 0, 1.5, H); }
}

function onDown(e) {
  const tr = activeTrack(); if (!tr || tr.type !== 'vocal') return;
  const r = cv.getBoundingClientRect(); const x = e.clientX - r.left, y = e.clientY - r.top;
  if (x < KEYW) return;
  beginGesture();
  drag = { erase: e.button === 2, lastT: xToTick(x), lastV: yToVal(y) };
  apply(drag.lastT, drag.lastV, drag.lastT, drag.lastV);
}
function onMove(e) {
  if (!drag) return;
  const r = cv.getBoundingClientRect(); const x = e.clientX - r.left, y = e.clientY - r.top;
  const t = xToTick(Math.max(KEYW, x)), v = yToVal(y);
  apply(drag.lastT, drag.lastV, t, v);
  drag.lastT = t; drag.lastV = v;
}
function apply(ta, va, tb, vb) {
  const tr = activeTrack(); const kind = store.ui.paramKind;
  let pts = tr.params[kind] || (tr.params[kind] = []);
  if (tb < ta) { [ta, tb] = [tb, ta]; [va, vb] = [vb, va]; }
  ta = Math.max(0, Math.floor(ta / STEP) * STEP); tb = Math.max(0, Math.ceil(tb / STEP) * STEP);
  pts = pts.filter((p) => p[0] < ta || p[0] > tb);
  if (!drag.erase) {
    const k = paramKind(kind);
    for (let t = ta; t <= tb; t += STEP) {
      const f = tb === ta ? 0 : (t - ta) / (tb - ta);
      let v = va + (vb - va) * f;
      v = k.id === 'pitch' ? Math.round(v) : Math.round(v * 1000) / 1000;
      pts.push([t, v]);
    }
    pts.sort((a, b) => a[0] - b[0]);
  }
  tr.params[kind] = pts;
  emit('params');
}

function smoothCurve() {
  const tr = activeTrack(); if (!tr || tr.type !== 'vocal') return;
  const kind = store.ui.paramKind;
  const pts = tr.params[kind]; if (!pts || pts.length < 3) return;
  commit('Smooth curve', () => {
    tr.params[kind] = pts.map((p, i) => {
      let s = 0, c = 0;
      for (let j = Math.max(0, i - 3); j <= Math.min(pts.length - 1, i + 3); j++) if (Math.abs(pts[j][0] - p[0]) <= STEP * 4) { s += pts[j][1]; c++; }
      return [p[0], kind === 'pitch' ? Math.round(s / c) : s / c];
    });
  });
}
