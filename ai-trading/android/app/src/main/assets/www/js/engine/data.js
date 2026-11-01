// Market data: Yahoo Finance daily bars (fetched through the app's native bridge, which has no CORS
// limits) and a deterministic synthetic market for offline demos (a port of trader/data.py).

// ---- Yahoo v8 chart API ----------------------------------------------------------------------
export function chartUrl(symbol, years = 6, now = Date.now()) {
  const p2 = Math.floor(now / 1000) + 86400, p1 = p2 - Math.ceil(years * 365.25 * 86400);
  return `https://query1.finance.yahoo.com/v8/finance/chart/${encodeURIComponent(symbol)}?period1=${p1}&period2=${p2}&interval=1d&events=div%2Csplits&includeAdjustedClose=true`;
}

/** Parse a Yahoo chart response into bars {t,o,h,l,c,v}; prices are split/dividend adjusted. */
export function parseChart(json) {
  const r = json?.chart?.result?.[0];
  if (!r || !r.timestamp) return null;
  const q = r.indicators?.quote?.[0] || {}, adj = r.indicators?.adjclose?.[0]?.adjclose;
  const offset = (r.meta?.gmtoffset || 0) * 1000;
  const rows = new Map();
  r.timestamp.forEach((ts, i) => {
    const o = q.open?.[i], h = q.high?.[i], l = q.low?.[i], c = q.close?.[i];
    if (![o, h, l, c].every((x) => typeof x === 'number' && Number.isFinite(x)) || c <= 0) return;
    const f = adj && Number.isFinite(adj[i]) && adj[i] > 0 ? adj[i] / c : 1;
    // key by the exchange's local calendar day, stored as midnight UTC like pandas' naive index
    const day = Math.floor((ts * 1000 + offset) / 86400000) * 86400000;
    rows.set(day, [o * f, h * f, l * f, c * f, Number.isFinite(q.volume?.[i]) ? q.volume[i] : 0]);
  });
  const days = [...rows.keys()].sort((a, b) => a - b);
  if (!days.length) return null;
  const mk = () => new Float64Array(days.length);
  const bars = { t: days, o: mk(), h: mk(), l: mk(), c: mk(), v: mk() };
  days.forEach((d, i) => {
    const [o, h, l, c, v] = rows.get(d);
    bars.o[i] = o; bars.h[i] = Math.max(h, o, c); bars.l[i] = Math.min(l, o, c); bars.c[i] = c; bars.v[i] = v;
  });
  return bars;
}

// ---- synthetic market --------------------------------------------------------------------------
const CRC = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) { let c = n; for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1; t[n] = c >>> 0; }
  return t;
})();
export function crc32(s) {
  let c = 0xffffffff;
  for (const b of new TextEncoder().encode(s)) c = CRC[(c ^ b) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}
function mulberry32(seed) {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/** Deterministic regime-switching market with GARCH volatility (offline demo data only). */
export function syntheticBars(symbol, bars = 1500, now = Date.now()) {
  const rnd = mulberry32(crc32(symbol));
  const uni = (a, b) => a + (b - a) * rnd();
  let spare = null;
  const gauss = () => {
    if (spare !== null) { const s = spare; spare = null; return s; }
    let u = 0; while (u === 0) u = rnd();
    const v = rnd(), m = Math.sqrt(-2 * Math.log(u));
    spare = m * Math.sin(2 * Math.PI * v);
    return m * Math.cos(2 * Math.PI * v);
  };
  const trans = [[0.99, 0.007, 0.003], [0.008, 0.984, 0.008], [0.004, 0.01, 0.986]];
  const k = uni(0.6, 1.5), drift = [0.0011 * k, 0.0001 * k, -0.0009 * k];
  const baseVol = 0.016 * uni(0.6, 1.6);
  let state = Math.floor(rnd() * 3), variance = baseVol ** 2, logp = Math.log(20 * uni(1, 10));
  const crypto = symbol.toUpperCase().endsWith('-USD');
  // trading days ending today (weekdays only unless crypto)
  const days = [];
  let d = Math.floor(now / 86400000) * 86400000;
  while (days.length < bars) {
    const wd = new Date(d).getUTCDay();
    if (crypto || (wd !== 0 && wd !== 6)) days.push(d);
    d -= 86400000;
  }
  days.reverse();
  const mk = () => new Float64Array(bars);
  const out = { t: days, o: mk(), h: mk(), l: mk(), c: mk(), v: mk() };
  let prev = Math.exp(logp);
  for (let i = 0; i < bars; i++) {
    const u = rnd(), row = trans[state];
    state = u < row[0] ? 0 : u < row[0] + row[1] ? 1 : 2;
    const sig = Math.sqrt(variance), ret = drift[state] + sig * gauss();
    variance = 0.05 * baseVol ** 2 + 0.1 * ret ** 2 + 0.85 * variance;
    logp += ret;
    const c = Math.exp(logp), o = (i ? prev : c) * Math.exp(0.25 * gauss() * sig);
    out.o[i] = o; out.c[i] = c;
    out.h[i] = Math.max(o, c) * Math.exp(Math.abs(0.5 * gauss()) * sig);
    out.l[i] = Math.min(o, c) * Math.exp(-Math.abs(0.5 * gauss()) * sig);
    out.v[i] = Math.exp(14 + 0.3 * gauss()) * (1 + 25 * Math.abs(ret));
    prev = c;
  }
  return out;
}

/** bars -> plain JSON (for caching) and back */
export const packBars = (b) => ({ t: b.t, o: Array.from(b.o), h: Array.from(b.h), l: Array.from(b.l), c: Array.from(b.c), v: Array.from(b.v) });
export const unpackBars = (j) => ({ t: j.t, o: Float64Array.from(j.o), h: Float64Array.from(j.h), l: Float64Array.from(j.l), c: Float64Array.from(j.c), v: Float64Array.from(j.v) });
