// Track list + arrangement overview
import { store, activeTrack, setActiveTrack, commit, beginGesture, endGesture, emit, ticksPerBar, songEndTick, secToTick } from './state.js';
import { PPQ, clamp } from './util.js';
import { singerById } from './singers.js';
import { invalidate, showContextMenu } from './ui.js';
import { currentTick, engine, updateLiveMix } from './engine.js';
import { KEYW, rollWidth } from './pianoroll.js';

const LANE = 56, RULER = 26;
let cv, g, W = 0, H = 0, dpr = 1, drag = null;
let scrollY = 0;

export function initArrange(canvas) {
  cv = canvas; g = cv.getContext('2d');
  new ResizeObserver(resize).observe(cv); resize();
  cv.addEventListener('pointerdown', onDown);
  window.addEventListener('pointermove', onMove);
  window.addEventListener('pointerup', onUp);
  cv.addEventListener('wheel', (e) => { e.preventDefault(); setScrollY(scrollY + e.deltaY * 0.5); }, { passive: false });
  document.getElementById('trackList').addEventListener('wheel', (e) => { e.preventDefault(); setScrollY(scrollY + e.deltaY * 0.5); }, { passive: false });
  cv.addEventListener('contextmenu', (e) => e.preventDefault());
}
function setScrollY(v) {
  const max = Math.max(0, store.project.tracks.length * LANE - (H - RULER));
  scrollY = clamp(v, 0, max);
  document.getElementById('trackList').scrollTop = scrollY;
  invalidate('arrange');
}
function resize() {
  dpr = window.devicePixelRatio || 1;
  const r = cv.getBoundingClientRect(); W = r.width; H = r.height;
  cv.width = Math.round(W * dpr); cv.height = Math.round(H * dpr);
  invalidate('arrange');
}

const totalTicks = () => Math.max(songEndTick() + ticksPerBar() * 4, ticksPerBar() * 32);
const ppt = () => (W - 10) / totalTicks();
const tx = (t) => 5 + t * ppt();
const xt = (x) => (x - 5) / ppt();

// ---------- track headers (DOM) ----------
export function renderTrackList() {
  const list = document.getElementById('trackList');
  const act = activeTrack();
  list.innerHTML = '';
  for (const tr of store.project.tracks) {
    const el = document.createElement('div');
    el.className = 'track' + (act && act.id === tr.id ? ' active' : '');
    el.dataset.id = tr.id;
    let avatar;
    if (tr.type === 'vocal') { const s = singerById(tr.singer); avatar = `<div class="avatar" style="background:${s.color}">${s.name[0]}</div>`; }
    else if (tr.type === 'instrument') avatar = `<div class="avatar" style="background:#333348"><svg viewBox="0 0 24 24"><path d="M4 3h16v18H4zm2 2v14h2v-6h1V5zm5 0v8h1v6h2v-6h1V5zm5 0v8h1v6h1V5z"/></svg></div>`;
    else avatar = `<div class="avatar" style="background:#333348"><svg viewBox="0 0 24 24"><path d="M3 10h2v4H3zm4-4h2v12H7zm4-3h2v18h-2zm4 5h2v8h-2zm4 2h2v4h-2z"/></svg></div>`;
    el.innerHTML = `<div class="strip" style="background:${tr.color}"></div>${avatar}
      <div class="info"><input class="tname" value="${escapeHtml(tr.name)}" spellcheck="false">
      <div class="sub"><input type="range" class="vol" min="0" max="1.2" step="0.01" value="${tr.volume}" title="Volume"><input type="range" class="pan" min="-1" max="1" step="0.01" value="${tr.pan}" title="Pan" style="width:44px"></div></div>
      <div class="btns"><button class="m ${tr.mute ? 'on' : ''}" title="Mute">M</button><button class="s ${tr.solo ? 'on' : ''}" title="Solo">S</button></div>`;
    el.addEventListener('mousedown', (e) => { if (!e.target.closest('input,button')) setActiveTrack(tr.id); });
    el.addEventListener('contextmenu', (e) => { e.preventDefault(); setActiveTrack(tr.id); trackMenu(e, tr); });
    const nm = el.querySelector('.tname');
    nm.addEventListener('focus', () => setActiveTrackSilently(tr.id));
    nm.addEventListener('change', () => commit('Rename track', () => { tr.name = nm.value || tr.name; }));
    nm.addEventListener('keydown', (e) => { e.stopPropagation(); if (e.key === 'Enter') nm.blur(); });
    const vol = el.querySelector('.vol'), pan = el.querySelector('.pan');
    vol.addEventListener('pointerdown', beginGesture);
    vol.addEventListener('input', () => { tr.volume = +vol.value; updateLiveMix(); });
    vol.addEventListener('change', () => endGesture('Track volume'));
    vol.addEventListener('dblclick', () => commit('Track volume', () => { tr.volume = 0.8; }));
    pan.addEventListener('pointerdown', beginGesture);
    pan.addEventListener('input', () => { tr.pan = +pan.value; updateLiveMix(); });
    pan.addEventListener('change', () => endGesture('Track pan'));
    pan.addEventListener('dblclick', () => commit('Track pan', () => { tr.pan = 0; }));
    el.querySelector('.m').onclick = () => { commit('Mute', () => { tr.mute = !tr.mute; }); updateLiveMix(); };
    el.querySelector('.s').onclick = () => { commit('Solo', () => { tr.solo = !tr.solo; }); updateLiveMix(); };
    list.appendChild(el);
  }
  list.scrollTop = scrollY;
}
function setActiveTrackSilently(id) { if (store.ui.activeTrackId !== id) setActiveTrack(id); }
function escapeHtml(s) { return s.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]); }

function trackMenu(e, tr) {
  showContextMenu(e.clientX, e.clientY, [
    ['Duplicate track', () => import('./app.js').then((m) => m.runCmd('dupTrack'))],
    ['Move up', () => moveTrack(tr, -1)],
    ['Move down', () => moveTrack(tr, 1)],
    ['Delete track', () => import('./app.js').then((m) => m.runCmd('delTrack'))],
  ]);
}
function moveTrack(tr, d) {
  const ts = store.project.tracks; const i = ts.indexOf(tr); const j = i + d;
  if (j < 0 || j >= ts.length) return;
  commit('Move track', () => { ts.splice(i, 1); ts.splice(j, 0, tr); });
}

// ---------- canvas ----------
export function drawArrange() {
  if (!g) return;
  const p = store.project;
  g.setTransform(dpr, 0, 0, dpr, 0, 0);
  g.fillStyle = '#121219'; g.fillRect(0, 0, W, H);
  const tpb = ticksPerBar(), total = totalTicks();
  let barStep = 1; while (tpb * barStep * ppt() < 28) barStep *= 2;
  // lanes
  const act = activeTrack();
  p.tracks.forEach((tr, i) => {
    const y = RULER + i * LANE - scrollY;
    if (y > H || y + LANE < RULER) return;
    g.fillStyle = act && act.id === tr.id ? '#1d1d29' : '#15151d';
    g.fillRect(0, y, W, LANE - 1);
    g.fillStyle = '#22222e'; g.fillRect(0, y + LANE - 1, W, 1);
  });
  for (let b = 0; b * tpb <= total; b += barStep) { g.fillStyle = '#22222e'; g.fillRect(Math.round(tx(b * tpb)), RULER, 1, H - RULER); }
  // content
  p.tracks.forEach((tr, i) => {
    const y = RULER + i * LANE - scrollY;
    if (y > H || y + LANE < RULER) return;
    const muted = tr.mute || (p.tracks.some((t) => t.solo) && !tr.solo);
    g.globalAlpha = muted ? 0.35 : 1;
    if (tr.type !== 'audio' && tr.notes.length) {
      // regions: split at gaps of 2+ bars
      const ns = tr.notes.slice().sort((a, b) => a.start - b.start);
      let regs = [], cur = null;
      for (const n of ns) {
        const s = Math.floor(n.start / tpb) * tpb, e = Math.ceil((n.start + n.dur) / tpb) * tpb;
        if (cur && s <= cur.e + tpb) { cur.e = Math.max(cur.e, e); cur.notes.push(n); } else { cur = { s, e, notes: [n] }; regs.push(cur); }
      }
      let lo = 127, hi = 0; for (const n of ns) { lo = Math.min(lo, n.pitch); hi = Math.max(hi, n.pitch); }
      const span = Math.max(12, hi - lo + 2);
      for (const r of regs) {
        const x = tx(r.s), w = (r.e - r.s) * ppt();
        g.fillStyle = tr.color + '38'; g.fillRect(x, y + 3, w, LANE - 7);
        g.fillStyle = tr.color; g.fillRect(x, y + 3, w, 2);
        for (const n of r.notes) {
          const ny = y + 8 + (1 - (n.pitch - lo + 1) / span) * (LANE - 16);
          g.fillRect(tx(n.start), ny, Math.max(1, n.dur * ppt() - 0.5), 2);
        }
      }
    }
    if (tr.type === 'audio') {
      for (const c of tr.clips) {
        const f = store.audioFiles.get(c.fileId); if (!f || !f.buffer) continue;
        const x = tx(c.start), w = secToTick(f.buffer.duration) * ppt();
        const sel = drag && drag.clip === c;
        g.fillStyle = tr.color + (sel ? '60' : '38'); g.fillRect(x, y + 3, w, LANE - 7);
        g.fillStyle = tr.color; g.fillRect(x, y + 3, w, 2);
        if (f.peaks) {
          const mid = y + LANE / 2, amp = (LANE - 14) / 2;
          for (let px = 0; px < w; px++) {
            const pk = f.peaks[Math.floor((px / w) * f.peaks.length)] || 0;
            g.fillRect(x + px, mid - pk * amp, 1, pk * amp * 2 + 0.5);
          }
        }
        g.fillStyle = '#fff'; g.font = '10px Inter, system-ui, sans-serif'; g.textBaseline = 'top';
        g.fillText(f.name.slice(0, 40), x + 4, y + 8);
      }
    }
    g.globalAlpha = 1;
  });
  // ruler
  g.fillStyle = '#16161f'; g.fillRect(0, 0, W, RULER);
  g.font = '10px Inter, system-ui, sans-serif'; g.textBaseline = 'middle';
  const loop = store.ui.loop;
  if (loop) { g.fillStyle = loop.on ? '#7c5cff66' : '#7c5cff22'; g.fillRect(tx(loop.start), 0, (loop.end - loop.start) * ppt(), RULER); }
  for (let b = 0; b * tpb <= total; b += barStep) {
    const x = Math.round(tx(b * tpb)) + 0.5;
    g.fillStyle = '#4a4a62'; g.fillRect(x, 14, 1, RULER - 14);
    g.fillStyle = '#9393a8'; g.fillText(String(b + 1), x + 3, 9);
  }
  g.fillStyle = '#2c2c3c'; g.fillRect(0, RULER - 1, W, 1);
  // piano-roll viewport
  const v = store.ui.view;
  if (act) {
    const i = p.tracks.indexOf(act);
    const vx = tx(v.scrollX), vw = (rollWidth() - KEYW) / v.pxPerTick * ppt();
    g.strokeStyle = '#ffffff70'; g.lineWidth = 1;
    g.strokeRect(Math.round(vx) + 0.5, RULER + i * LANE - scrollY + 1.5, Math.max(4, vw), LANE - 4);
  }
  const px = tx(currentTick());
  g.fillStyle = '#ff5c8a'; g.fillRect(Math.round(px), 0, 1.5, H);
}

function onDown(e) {
  const r = cv.getBoundingClientRect(); const x = e.clientX - r.left, y = e.clientY - r.top;
  const t = Math.max(0, xt(x));
  const beat = PPQ * 4 / store.project.timeSig[1];
  if (y < RULER) {
    drag = { type: 'seek' };
    store.ui.playhead = Math.round(t / beat) * beat;
    if (engine.playing) import('./app.js').then((m) => m.restartPlayback());
    emit('playhead'); return;
  }
  const i = Math.floor((y - RULER + scrollY) / LANE);
  const tr = store.project.tracks[i];
  if (!tr) return;
  setActiveTrack(tr.id);
  if (tr.type === 'audio') {
    for (const c of tr.clips) {
      const f = store.audioFiles.get(c.fileId); if (!f || !f.buffer) continue;
      if (t >= c.start && t <= c.start + secToTick(f.buffer.duration)) {
        if (e.button === 2) {
          showContextMenu(e.clientX, e.clientY, [['Delete clip', () => commit('Delete clip', () => { tr.clips = tr.clips.filter((q) => q !== c); })]]);
          return;
        }
        beginGesture(); drag = { type: 'clip', clip: c, t0: t, s0: c.start }; return;
      }
    }
    return;
  }
  // move piano roll viewport
  const v = store.ui.view;
  const visTicks = (rollWidth() - KEYW) / v.pxPerTick;
  drag = { type: 'view' };
  v.scrollX = Math.max(0, t - visTicks / 2);
  emit('view');
}
function onMove(e) {
  if (!drag) return;
  const r = cv.getBoundingClientRect(); const x = e.clientX - r.left;
  const t = Math.max(0, xt(x));
  const beat = PPQ * 4 / store.project.timeSig[1];
  if (drag.type === 'seek') { store.ui.playhead = Math.round(t / beat) * beat; emit('playhead'); }
  if (drag.type === 'view') { const v = store.ui.view; v.scrollX = Math.max(0, t - (rollWidth() - KEYW) / v.pxPerTick / 2); emit('view'); }
  if (drag.type === 'clip') { drag.clip.start = Math.max(0, Math.round((drag.s0 + t - drag.t0) / beat) * beat); invalidate('arrange'); }
}
function onUp() {
  if (!drag) return;
  if (drag.type === 'clip') endGesture('Move clip');
  if (drag.type === 'seek' && engine.playing) import('./app.js').then((m) => m.restartPlayback());
  drag = null;
}
