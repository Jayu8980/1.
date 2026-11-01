// Small UI helpers: escaping, formatting, icons, SVG charts, toasts and dialogs.
export const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
export const $ = (sel, root = document) => root.querySelector(sel);
export const $$ = (sel, root = document) => [...root.querySelectorAll(sel)];

const isFiniteNum = (x) => typeof x === 'number' && Number.isFinite(x);
export function num(x, digits = 2) {
  if (!isFiniteNum(x)) return '-';
  return x.toLocaleString('en-IN', { minimumFractionDigits: digits, maximumFractionDigits: digits });
}
export function money(x, cur = '₹', digits = 0) {
  if (!isFiniteNum(x)) return '-';
  const a = Math.abs(x);
  const s = a >= 1e7 && cur === '₹' ? `${(a / 1e7).toFixed(2)} Cr` : a >= 1e5 && cur === '₹' ? `${(a / 1e5).toFixed(2)} L` : a.toLocaleString('en-IN', { maximumFractionDigits: digits, minimumFractionDigits: digits });
  return `${x < 0 ? '-' : ''}${cur}${s}`;
}
export const moneyFull = (x, cur = '₹') => (isFiniteNum(x) ? `${x < 0 ? '-' : ''}${cur}${Math.abs(x).toLocaleString('en-IN', { maximumFractionDigits: 2, minimumFractionDigits: 2 })}` : '-');
export const pct = (x, digits = 1, plus = true) => (isFiniteNum(x) ? `${plus && x > 0 ? '+' : ''}${(x * 100).toFixed(digits)}%` : '-');
export const signed = (x, digits = 2, suffix = '') => (isFiniteNum(x) ? `${x > 0 ? '+' : ''}${x.toFixed(digits)}${suffix}` : '-');
export const price = (x) => (!isFiniteNum(x) ? '-' : x >= 1000 ? num(x, 1) : x >= 1 ? num(x, 2) : x.toPrecision(4));
export const short = (sym) => String(sym || '').replace(/\.(NS|BO)$/, '').replace(/^\^/, '');
export const cls = (x) => (x > 0 ? 'up' : x < 0 ? 'down' : '');
export function ago(iso) {
  if (!iso) return '';
  const s = (Date.now() - new Date(iso).getTime()) / 1000;
  if (s < 60) return 'just now';
  if (s < 3600) return `${Math.floor(s / 60)} min ago`;
  if (s < 86400) return `${Math.floor(s / 3600)} h ago`;
  return `${Math.floor(s / 86400)} d ago`;
}
const PALETTE = ['#34d3b4', '#5aa9ff', '#7c6cff', '#f5b84a', '#f25f9b', '#22c58b', '#ff8a4c', '#4cc9f0'];
export function avatarColor(s) {
  let h = 0;
  for (const ch of String(s)) h = (h * 31 + ch.charCodeAt(0)) >>> 0;
  return PALETTE[h % PALETTE.length];
}

// ---- icons (24x24 stroke) ----------------------------------------------------------------------
const P = {
  home: '<path d="M3 10.5 12 3l9 7.5"/><path d="M5 9.5V20h5v-6h4v6h5V9.5"/>',
  scan: '<path d="M3 4h18l-7 8.5V19l-4 2v-8.5z"/>',
  wallet: '<rect x="3" y="6" width="18" height="14" rx="3"/><path d="M3 10h18"/><path d="M8 3h8"/><circle cx="16.5" cy="15" r="1.2"/>',
  server: '<rect x="3" y="4" width="18" height="7" rx="2"/><rect x="3" y="13" width="18" height="7" rx="2"/><path d="M7 7.5h.01M7 16.5h.01"/>',
  gear: '<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"/>',
  bolt: '<path d="M13 2 4 14h7l-1 8 9-12h-7z"/>',
  play: '<path d="M7 4v16l13-8z"/>',
  stop: '<rect x="6" y="6" width="12" height="12" rx="2"/>',
  refresh: '<path d="M20 11a8 8 0 1 0-2.3 5.7"/><path d="M20 4v7h-7"/>',
  up: '<path d="m4 16 6-6 4 4 6-7"/><path d="M15 7h5v5"/>',
  down: '<path d="m4 8 6 6 4-4 6 7"/><path d="M15 17h5v-5"/>',
  flat: '<path d="M4 12h16"/><path d="m16 8 4 4-4 4"/>',
  alert: '<path d="M12 3 2 20h20z"/><path d="M12 10v4M12 17h.01"/>',
  info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v5M12 8h.01"/>',
  check: '<path d="m5 12 5 5L20 7"/>',
  x: '<path d="M6 6l12 12M18 6 6 18"/>',
  brain: '<path d="M9 4a3 3 0 0 0-3 3v.5A3 3 0 0 0 4 10.3 3 3 0 0 0 5 15a3 3 0 0 0 4 4.5V4z"/><path d="M15 4a3 3 0 0 1 3 3v.5a3 3 0 0 1 2 2.8 3 3 0 0 1-1 4.7 3 3 0 0 1-4 4.5V4z"/>',
  link: '<path d="M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1"/><path d="M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1"/>',
  send: '<path d="M4 12 20 4l-6 16-3-7z"/>',
  moon: '<path d="M20 14.5A8 8 0 1 1 9.5 4a6.5 6.5 0 0 0 10.5 10.5z"/>',
  trash: '<path d="M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13"/>',
  chart: '<path d="M4 20V4"/><path d="M4 20h16"/><path d="m7 15 4-4 3 3 5-6"/>',
  shield: '<path d="M12 3 4 6v6c0 4.5 3.4 8.3 8 9 4.6-.7 8-4.5 8-9V6z"/>',
};
export const icon = (name, extra = '') => `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round" ${extra}>${P[name] || ''}</svg>`;

// ---- charts ------------------------------------------------------------------------------------
export function sparkline(values, { w = 84, h = 34, color = null } = {}) {
  const v = (values || []).filter(isFiniteNum);
  if (v.length < 2) return `<svg class="spark" viewBox="0 0 ${w} ${h}"></svg>`;
  const lo = Math.min(...v), hi = Math.max(...v), r = hi - lo || 1;
  const pts = v.map((x, i) => `${((i / (v.length - 1)) * w).toFixed(1)},${(h - 3 - ((x - lo) / r) * (h - 6)).toFixed(1)}`);
  const c = color || (v[v.length - 1] >= v[0] ? 'var(--up)' : 'var(--down)');
  const id = `g${Math.random().toString(36).slice(2, 8)}`;
  return `<svg class="spark" viewBox="0 0 ${w} ${h}" preserveAspectRatio="none"><defs><linearGradient id="${id}" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="${c}" stop-opacity=".28"/><stop offset="1" stop-color="${c}" stop-opacity="0"/></linearGradient></defs><path d="M0,${h} L${pts.join(' L')} L${w},${h}Z" fill="url(#${id})"/><polyline points="${pts.join(' ')}" fill="none" stroke="${c}" stroke-width="1.8" stroke-linejoin="round" stroke-linecap="round"/></svg>`;
}

/** price chart with entry / stop / target lines */
export function priceChart(closes, levels = {}, { w = 360, h = 190 } = {}) {
  const v = (closes || []).filter(isFiniteNum);
  if (v.length < 2) return '<div class="empty small">No chart data</div>';
  const extra = Object.values(levels).filter(isFiniteNum);
  const lo = Math.min(...v, ...extra), hi = Math.max(...v, ...extra), pad = (hi - lo) * 0.08 || 1;
  const y = (x) => h - 18 - ((x - (lo - pad)) / (hi - lo + 2 * pad)) * (h - 30);
  const xw = w - 52;
  const pts = v.map((x, i) => `${((i / (v.length - 1)) * xw).toFixed(1)},${y(x).toFixed(1)}`);
  const up = v[v.length - 1] >= v[0], c = up ? 'var(--up)' : 'var(--down)';
  const lines = [['target', 'var(--up)'], ['entry', 'var(--info)'], ['stop', 'var(--down)']].filter(([k]) => isFiniteNum(levels[k])).map(([k, col]) => {
    const yy = y(levels[k]).toFixed(1);
    return `<line x1="0" x2="${xw}" y1="${yy}" y2="${yy}" stroke="${col}" stroke-dasharray="4 4" stroke-width="1.2"/><rect x="${xw + 3}" y="${yy - 9}" width="48" height="18" rx="5" fill="${col}" opacity=".16"/><text x="${xw + 27}" y="${+yy + 4}" text-anchor="middle" font-size="10" font-weight="700" fill="${col}">${price(levels[k])}</text>`;
  }).join('');
  const zone = isFiniteNum(levels.entry) && isFiniteNum(levels.target) && isFiniteNum(levels.stop)
    ? `<rect x="0" y="${Math.min(y(levels.target), y(levels.entry))}" width="${xw}" height="${Math.abs(y(levels.target) - y(levels.entry))}" fill="var(--up)" opacity=".06"/><rect x="0" y="${Math.min(y(levels.stop), y(levels.entry))}" width="${xw}" height="${Math.abs(y(levels.stop) - y(levels.entry))}" fill="var(--down)" opacity=".06"/>` : '';
  const grid = [0.25, 0.5, 0.75].map((f) => `<line x1="0" x2="${xw}" y1="${(h - 18) * f + 6}" y2="${(h - 18) * f + 6}" stroke="var(--line)" stroke-width="1"/>`).join('');
  return `<svg class="chart" viewBox="0 0 ${w} ${h}" preserveAspectRatio="none">${grid}${zone}<defs><linearGradient id="pc" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="${c}" stop-opacity=".25"/><stop offset="1" stop-color="${c}" stop-opacity="0"/></linearGradient></defs><path d="M0,${h - 18} L${pts.join(' L')} L${xw},${h - 18}Z" fill="url(#pc)"/><polyline points="${pts.join(' ')}" fill="none" stroke="${c}" stroke-width="2" stroke-linejoin="round"/>${lines}<text x="0" y="${h - 3}" font-size="10" fill="var(--faint)">${v.length} sessions</text></svg>`;
}

export function equityCurve(points, { w = 220, h = 90 } = {}) {
  if (!points || points.length < 2) return '';
  const lo = Math.min(...points), hi = Math.max(...points), r = hi - lo || 1;
  const pts = points.map((x, i) => `${((i / (points.length - 1)) * w).toFixed(1)},${(h - 4 - ((x - lo) / r) * (h - 10)).toFixed(1)}`);
  return `<svg class="curve" viewBox="0 0 ${w} ${h}" preserveAspectRatio="none"><path d="M0,${h} L${pts.join(' L')} L${w},${h}Z" fill="rgba(255,255,255,.10)"/><polyline points="${pts.join(' ')}" fill="none" stroke="rgba(255,255,255,.85)" stroke-width="2"/></svg>`;
}

// ---- overlays ----------------------------------------------------------------------------------
let toastTimer = null;
export function toast(msg, { error = false, ms = 3200 } = {}) {
  $('.toast')?.remove();
  const el = document.createElement('div');
  el.className = `toast${error ? ' err' : ''}`;
  el.innerHTML = esc(msg);
  document.body.appendChild(el);
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.remove(), ms);
}

const overlays = [];
export function closeTop() {
  const o = overlays.pop();
  if (!o) return false;
  o.close();
  return true;
}
export function sheet(html, { onOpen } = {}) {
  const scrim = document.createElement('div'), el = document.createElement('div');
  scrim.className = 'scrim';
  el.className = 'sheet';
  el.innerHTML = `<div class="grab"></div>${html}`;
  const close = () => { scrim.remove(); el.remove(); const i = overlays.findIndex((o) => o.el === el); if (i >= 0) overlays.splice(i, 1); };
  scrim.onclick = close;
  document.body.append(scrim, el);
  overlays.push({ el, close });
  onOpen?.(el, close);
  return { el, close };
}
export function dialog({ title, body = '', confirm = 'OK', cancel = 'Cancel', danger = false, input = null }) {
  return new Promise((resolve) => {
    const scrim = document.createElement('div'), el = document.createElement('div');
    scrim.className = 'scrim';
    scrim.style.zIndex = 60;
    el.className = 'dialog';
    el.innerHTML = `<h3>${esc(title)}</h3><p>${body}</p>${input ? `<input class="input" placeholder="${esc(input)}" autocapitalize="characters" style="margin-bottom:12px">` : ''}
      <div class="actions">${cancel ? `<button class="btn ghost small" data-r="0">${esc(cancel)}</button>` : ''}<button class="btn small ${danger ? 'danger' : 'primary'}" data-r="1">${esc(confirm)}</button></div>`;
    const finish = (v) => { scrim.remove(); el.remove(); const i = overlays.findIndex((o) => o.el === el); if (i >= 0) overlays.splice(i, 1); resolve(v); };
    scrim.onclick = () => finish(false);
    el.addEventListener('click', (e) => {
      const b = e.target.closest('[data-r]');
      if (!b) return;
      if (b.dataset.r === '1' && input) return finish(el.querySelector('input').value.trim());
      finish(b.dataset.r === '1');
    });
    document.body.append(scrim, el);
    overlays.push({ el, close: () => finish(false) });
    el.querySelector('input')?.focus();
  });
}
