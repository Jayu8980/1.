// Native bridge: HTTP without CORS limits, theme and back-button hooks. In a desktop browser
// (development, screenshots) it falls back to fetch().
const pending = new Map();
let seq = 0;
const Native = window.NativeApp || null;
export const isNative = !!Native;

window.__nativeHttp = (id, status, body, error) => {
  const p = pending.get(id);
  if (!p) return;
  pending.delete(id);
  clearTimeout(p.timer);
  if (error) p.reject(new Error(error));
  else p.resolve({ status, body, ok: status >= 200 && status < 300, json: () => JSON.parse(body) });
};

/** http({url, method, headers, body, timeout}) -> {status, ok, body, json()} */
export function http({ url, method = 'GET', headers = {}, body = null, timeout = 30000 }) {
  if (Native) {
    return new Promise((resolve, reject) => {
      const id = `h${++seq}`;
      const timer = setTimeout(() => { pending.delete(id); reject(new Error('request timed out')); }, timeout + 5000);
      pending.set(id, { resolve, reject, timer });
      Native.http(id, JSON.stringify({ url, method, headers, body, timeout }));
    });
  }
  const ctl = new AbortController();
  const t = setTimeout(() => ctl.abort(), timeout);
  return fetch(url, { method, headers, body, signal: ctl.signal })
    .then(async (r) => { const text = await r.text(); return { status: r.status, ok: r.ok, body: text, json: () => JSON.parse(text) }; })
    .catch((e) => { throw new Error(e.name === 'AbortError' ? 'request timed out' : `network error: ${e.message}`); })
    .finally(() => clearTimeout(t));
}

export function setNativeTheme(dark) { try { Native?.setTheme(dark ? 'dark' : 'light'); } catch { /* older shell */ } }
export function openExternal(url) { if (Native) Native.openUrl(url); else window.open(url, '_blank'); }
export function appVersion() { try { return Native?.version() || 'web'; } catch { return 'web'; } }
export function insets() {
  try { return JSON.parse(Native?.insets() || '{"top":0,"bottom":0}'); } catch { return { top: 0, bottom: 0 }; }
}

// ---- storage: small JSON in localStorage, market data in IndexedDB -----------------------------
export const store = {
  get(key, fallback = null) {
    try { const v = localStorage.getItem(key); return v === null ? fallback : JSON.parse(v); } catch { return fallback; }
  },
  set(key, value) {
    try { localStorage.setItem(key, JSON.stringify(value)); return true; } catch { return false; }
  },
  remove(key) { try { localStorage.removeItem(key); } catch { /* ignore */ } },
};

let dbp = null;
function db() {
  if (!dbp) {
    dbp = new Promise((resolve, reject) => {
      const req = indexedDB.open('trader', 1);
      req.onupgradeneeded = () => req.result.createObjectStore('bars');
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    }).catch(() => null);
  }
  return dbp;
}
export async function cacheGet(key) {
  const d = await db();
  if (!d) return null;
  return new Promise((resolve) => {
    const r = d.transaction('bars').objectStore('bars').get(key);
    r.onsuccess = () => resolve(r.result || null);
    r.onerror = () => resolve(null);
  });
}
export async function cachePut(key, value) {
  const d = await db();
  if (!d) return;
  return new Promise((resolve) => {
    const tx = d.transaction('bars', 'readwrite');
    tx.objectStore('bars').put(value, key);
    tx.oncomplete = tx.onerror = () => resolve();
  });
}
export async function cacheClear() {
  const d = await db();
  if (!d) return;
  return new Promise((resolve) => {
    const tx = d.transaction('bars', 'readwrite');
    tx.objectStore('bars').clear();
    tx.oncomplete = tx.onerror = () => resolve();
  });
}
