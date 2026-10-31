// Small UI helpers: redraw scheduling, context menus, modals, toasts
const dirty = new Set();
let drawers = {};
let raf = 0;
export function setDrawers(d) { drawers = d; }
export function invalidate(...which) {
  for (const w of which.length ? which : ['roll', 'param', 'arrange']) dirty.add(w);
  if (!raf) raf = requestAnimationFrame(flush);
}
function flush() {
  raf = 0;
  for (const w of dirty) drawers[w] && drawers[w]();
  dirty.clear();
}

export function showContextMenu(x, y, items) {
  const m = document.getElementById('ctxMenu');
  m.innerHTML = '';
  for (const [label, fn] of items) {
    const b = document.createElement('button'); b.textContent = label;
    b.onclick = () => { hideContextMenu(); fn(); };
    m.appendChild(b);
  }
  m.classList.add('show');
  const r = m.getBoundingClientRect();
  m.style.left = Math.min(x, innerWidth - r.width - 8) + 'px';
  m.style.top = Math.min(y, innerHeight - r.height - 8) + 'px';
}
export function hideContextMenu() { document.getElementById('ctxMenu').classList.remove('show'); }
window.addEventListener('mousedown', (e) => { if (!e.target.closest('#ctxMenu')) hideContextMenu(); });

export function modal({ title, body, buttons = [{ label: 'Close' }], onOpen }) {
  const root = document.getElementById('modalRoot');
  const back = document.createElement('div'); back.className = 'modal-back';
  const m = document.createElement('div'); m.className = 'modal';
  m.innerHTML = `<header></header><div class="content"></div><footer></footer>`;
  m.querySelector('header').textContent = title;
  const content = m.querySelector('.content');
  if (typeof body === 'string') content.innerHTML = body; else content.appendChild(body);
  const close = () => { back.remove(); document.removeEventListener('keydown', onKey, true); };
  const foot = m.querySelector('footer');
  for (const b of buttons) {
    const el = document.createElement('button'); el.className = 'btn' + (b.primary ? ' primary' : ''); el.textContent = b.label;
    el.onclick = async () => { if (b.action) { const r = await b.action(content); if (r === false) return; } close(); };
    foot.appendChild(el);
  }
  function onKey(e) {
    if (e.key === 'Escape') { e.stopPropagation(); close(); }
    else e.stopPropagation();
  }
  document.addEventListener('keydown', onKey, true);
  back.addEventListener('mousedown', (e) => { if (e.target === back) close(); });
  back.appendChild(m); root.appendChild(back);
  onOpen && onOpen(content, close);
  return { close, content };
}

export function toast(msg, err = false) {
  const t = document.createElement('div'); t.className = 'toast' + (err ? ' err' : ''); t.textContent = msg;
  document.getElementById('toasts').appendChild(t);
  setTimeout(() => t.remove(), 2600);
}

let statusTimer;
export function setStatus(msg, sticky = false) {
  const el = document.getElementById('statusMsg');
  el.textContent = msg || 'Ready';
  clearTimeout(statusTimer);
  if (msg && !sticky) statusTimer = setTimeout(() => { el.textContent = 'Ready'; }, 4000);
}
