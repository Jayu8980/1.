// Numeric series helpers and technical indicators (a port of trader/indicators.py).
// Every series is a Float64Array aligned with the bars; NaN marks "not enough data yet".

export const ok = (x) => typeof x === 'number' && Number.isFinite(x);
export const full = (n, v = NaN) => new Float64Array(n).fill(v);
export const last = (a) => a[a.length - 1];
export const clip = (x, lo = 0, hi = 1) => (ok(x) ? Math.max(lo, Math.min(hi, x)) : lo);
export const sign = (x) => (ok(x) ? Math.sign(x) : NaN);
export const tanh = (x) => (ok(x) ? Math.tanh(x) : NaN);

export function map(a, f) {
  const out = full(a.length);
  for (let i = 0; i < a.length; i++) out[i] = f(a[i], i);
  return out;
}
export function zip(a, b, f) {
  const out = full(a.length);
  for (let i = 0; i < a.length; i++) out[i] = f(a[i], b[i], i);
  return out;
}
export function shift(a, k = 1) {
  const out = full(a.length);
  for (let i = k; i < a.length; i++) out[i] = a[i - k];
  return out;
}
export function pct(a, k = 1) {
  const out = full(a.length);
  for (let i = k; i < a.length; i++) if (ok(a[i]) && ok(a[i - k]) && a[i - k] !== 0) out[i] = a[i] / a[i - k] - 1;
  return out;
}
// rolling window that is only defined when all `w` values are present (pandas' default)
export function rolling(a, w, f) {
  const out = full(a.length);
  let bad = 0;
  for (let i = 0; i < a.length; i++) {
    if (!ok(a[i])) bad++;
    if (i >= w && !ok(a[i - w])) bad--;
    if (i >= w - 1 && bad === 0) out[i] = f(a, i - w + 1, i + 1);
  }
  return out;
}
const sumOf = (a, s, e) => { let t = 0; for (let i = s; i < e; i++) t += a[i]; return t; };
export const sma = (a, w) => rolling(a, w, (x, s, e) => sumOf(x, s, e) / w);
export const rsum = (a, w) => rolling(a, w, sumOf);
export const rmax = (a, w) => rolling(a, w, (x, s, e) => { let m = -Infinity; for (let i = s; i < e; i++) m = Math.max(m, x[i]); return m; });
export const rmin = (a, w) => rolling(a, w, (x, s, e) => { let m = Infinity; for (let i = s; i < e; i++) m = Math.min(m, x[i]); return m; });
export function rstd(a, w) {
  return rolling(a, w, (x, s, e) => {
    const m = sumOf(x, s, e) / w;
    let v = 0;
    for (let i = s; i < e; i++) v += (x[i] - m) ** 2;
    return Math.sqrt(v / (w - 1));
  });
}
// exponentially weighted mean, adjust=False, counting only valid observations
export function ewm(a, alpha, minPeriods) {
  const out = full(a.length);
  let y = NaN, n = 0;
  for (let i = 0; i < a.length; i++) {
    const x = a[i];
    if (ok(x)) { y = ok(y) ? alpha * x + (1 - alpha) * y : x; n++; }
    if (n >= minPeriods && ok(y)) out[i] = y;
  }
  return out;
}
export const ema = (a, span) => ewm(a, 2 / (span + 1), span);
export const wilder = (a, n) => ewm(a, 1 / n, n);

export function rsi(c, n = 14) {
  const d = zip(c, shift(c), (x, y) => (ok(x) && ok(y) ? x - y : NaN));
  const gain = wilder(map(d, (x) => (ok(x) ? Math.max(x, 0) : NaN)), n);
  const loss = wilder(map(d, (x) => (ok(x) ? Math.max(-x, 0) : NaN)), n);
  return zip(gain, loss, (g, l) => (!ok(g) || !ok(l) ? NaN : l === 0 ? 100 : 100 - 100 / (1 + g / l)));
}
export function macd(c, fast = 12, slow = 26, signal = 9) {
  const line = zip(ema(c, fast), ema(c, slow), (a, b) => a - b);
  const sig = ema(line, signal);
  return { line, sig, hist: zip(line, sig, (a, b) => a - b) };
}
export function trueRange(b) {
  const out = full(b.c.length);
  for (let i = 0; i < out.length; i++) {
    const hl = b.h[i] - b.l[i];
    out[i] = i === 0 ? hl : Math.max(hl, Math.abs(b.h[i] - b.c[i - 1]), Math.abs(b.l[i] - b.c[i - 1]));
  }
  return out;
}
export const atr = (b, n = 14) => wilder(trueRange(b), n);
export function adx(b, n = 14) {
  const len = b.c.length, plus = full(len, 0), minus = full(len, 0);
  for (let i = 1; i < len; i++) {
    const up = b.h[i] - b.h[i - 1], down = b.l[i - 1] - b.l[i];
    plus[i] = up > down && up > 0 ? up : 0;
    minus[i] = down > up && down > 0 ? down : 0;
  }
  const tr = wilder(trueRange(b), n);
  const pdi = zip(wilder(plus, n), tr, (p, t) => (100 * p) / t);
  const mdi = zip(wilder(minus, n), tr, (m, t) => (100 * m) / t);
  const dx = zip(pdi, mdi, (p, m) => (p + m === 0 ? NaN : (100 * Math.abs(p - m)) / (p + m)));
  return { adx: wilder(dx, n), pdi, mdi };
}
export function bollinger(c, n = 20, k = 2) {
  const mid = sma(c, n), sd = rstd(c, n);
  const up = zip(mid, sd, (m, s) => m + k * s), low = zip(mid, sd, (m, s) => m - k * s);
  const pctb = map(c, (x, i) => (up[i] - low[i] === 0 ? NaN : (x - low[i]) / (up[i] - low[i])));
  const width = map(mid, (m, i) => (up[i] - low[i]) / m);
  return { mid, up, low, pctb, width };
}
export function stochastic(b, n = 14, d = 3) {
  const lo = rmin(b.l, n), hi = rmax(b.h, n);
  const k = map(b.c, (x, i) => (hi[i] - lo[i] === 0 ? NaN : (100 * (x - lo[i])) / (hi[i] - lo[i])));
  return { k, d: sma(k, d) };
}
export function williamsR(b, n = 14) {
  const lo = rmin(b.l, n), hi = rmax(b.h, n);
  return map(b.c, (x, i) => (hi[i] - lo[i] === 0 ? NaN : (-100 * (hi[i] - x)) / (hi[i] - lo[i])));
}
export function cci(b, n = 20) {
  const tp = map(b.c, (x, i) => (b.h[i] + b.l[i] + x) / 3);
  const mean = sma(tp, n);
  const mad = rolling(tp, n, (x, s, e) => {
    const m = sumOf(x, s, e) / n;
    let t = 0;
    for (let i = s; i < e; i++) t += Math.abs(x[i] - m);
    return t / n;
  });
  return map(tp, (x, i) => (mad[i] === 0 ? NaN : (x - mean[i]) / (0.015 * mad[i])));
}
export function obv(b) {
  const out = full(b.c.length);
  let t = 0;
  for (let i = 0; i < out.length; i++) {
    if (i > 0) t += Math.sign(b.c[i] - b.c[i - 1]) * b.v[i];
    out[i] = t;
  }
  return out;
}
export function mfi(b, n = 14) {
  const tp = map(b.c, (x, i) => (b.h[i] + b.l[i] + x) / 3);
  const pos = full(tp.length, 0), neg = full(tp.length, 0);
  for (let i = 1; i < tp.length; i++) {
    const flow = tp[i] * b.v[i];
    if (tp[i] > tp[i - 1]) pos[i] = flow; else if (tp[i] < tp[i - 1]) neg[i] = flow;
  }
  const ps = rsum(pos, n), ns = rsum(neg, n);
  return zip(ps, ns, (p, q) => (q === 0 ? NaN : 100 - 100 / (1 + p / q)));
}
export function cmf(b, n = 20) {
  const mfv = map(b.c, (c, i) => {
    const r = b.h[i] - b.l[i];
    return r === 0 ? 0 : (((c - b.l[i]) - (b.h[i] - c)) / r) * b.v[i];
  });
  const vs = rsum(b.v, n);
  return zip(rsum(mfv, n), vs, (m, v) => (v === 0 ? NaN : m / v));
}
export function supertrend(b, n = 10, mult = 3) {
  const a = atr(b, n), len = b.c.length, dir = full(len, 1);
  const fu = full(len), fl = full(len);
  for (let i = 0; i < len; i++) {
    const hl2 = (b.h[i] + b.l[i]) / 2, up = hl2 + mult * a[i], lo = hl2 - mult * a[i];
    if (i === 0) { fu[i] = up; fl[i] = lo; continue; }
    fu[i] = !ok(fu[i - 1]) || up < fu[i - 1] || b.c[i - 1] > fu[i - 1] ? up : fu[i - 1];
    fl[i] = !ok(fl[i - 1]) || lo > fl[i - 1] || b.c[i - 1] < fl[i - 1] ? lo : fl[i - 1];
    if (dir[i - 1] > 0 && b.c[i] < fl[i]) dir[i] = -1;
    else if (dir[i - 1] < 0 && b.c[i] > fu[i]) dir[i] = 1;
    else dir[i] = dir[i - 1];
  }
  return dir;
}
export function lrSlope(c, n = 50) {
  const xs = Array.from({ length: n }, (_, i) => i - (n - 1) / 2);
  const denom = xs.reduce((t, x) => t + x * x, 0);
  return rolling(map(c, Math.log), n, (w, s, e) => {
    const m = sumOf(w, s, e) / n;
    let t = 0;
    for (let i = s; i < e; i++) t += xs[i - s] * (w[i] - m);
    return t / denom;
  });
}

// ---- calendar resampling (W-FRI weeks, calendar months) -------------------------------------
export function resample(bars, kind) {
  const key = (ms) => {
    const d = new Date(ms);
    if (kind === 'M') return d.getUTCFullYear() * 12 + d.getUTCMonth();
    const shiftDays = (5 - d.getUTCDay() + 7) % 7; // to the Friday that closes the week
    return Math.floor((ms + shiftDays * 86400000) / 86400000);
  };
  const out = { t: [], c: [] };
  let cur = null;
  for (let i = 0; i < bars.c.length; i++) {
    const k = key(bars.t[i]);
    if (k !== cur) { out.t.push(bars.t[i]); out.c.push(bars.c[i]); cur = k; }
    else out.c[out.c.length - 1] = bars.c[i];
  }
  out.c = Float64Array.from(out.c);
  return out;
}

export function barsPerYear(t) {
  if (t.length < 2) return 252;
  const days = (t[t.length - 1] - t[0]) / 86400000;
  return days > 0 ? t.length / (days / 365.25) : 252;
}

// align a benchmark close series to these bars by date (forward-filled)
export function alignTo(bars, bench) {
  const out = full(bars.c.length);
  if (!bench) return out;
  let j = 0, lastVal = NaN;
  for (let i = 0; i < bars.t.length; i++) {
    while (j < bench.t.length && bench.t[j] <= bars.t[i] + 43200000) { lastVal = bench.c[j]; j++; }
    out[i] = lastVal;
  }
  return out;
}

// every indicator the agents use (a port of compute_all)
export function computeAll(bars, benchClose) {
  const { o, h, l, c, v } = bars;
  const ind = { open: o, high: h, low: l, close: c, volume: v };
  for (const n of [20, 50, 200]) ind[`sma${n}`] = sma(c, n);
  for (const n of [9, 20, 21, 50]) ind[`ema${n}`] = ema(c, n);
  ind.rsi2 = rsi(c, 2);
  ind.rsi14 = rsi(c, 14);
  const m = macd(c);
  Object.assign(ind, { macd: m.line, macd_signal: m.sig, macd_hist: m.hist });
  ind.atr = atr(bars, 14);
  const a = adx(bars, 14);
  Object.assign(ind, { adx: a.adx, plus_di: a.pdi, minus_di: a.mdi });
  const bb = bollinger(c);
  Object.assign(ind, { bb_mid: bb.mid, bb_up: bb.up, bb_low: bb.low, bb_pctb: bb.pctb, bb_width: bb.width });
  const st = stochastic(bars);
  Object.assign(ind, { stoch_k: st.k, stoch_d: st.d });
  ind.willr = williamsR(bars);
  ind.cci = cci(bars);
  ind.obv = obv(bars);
  ind.obv_ema20 = ema(ind.obv, 20);
  ind.mfi = mfi(bars);
  ind.cmf = cmf(bars);
  ind.st_dir = supertrend(bars);
  for (const n of [20, 55]) {
    ind[`dc_high${n}`] = shift(rmax(h, n));
    ind[`dc_low${n}`] = shift(rmin(l, n));
  }
  ind.vol_avg20 = sma(v, 20);
  ind.vol_ratio = zip(v, ind.vol_avg20, (x, m) => (m === 0 || !ok(m) ? NaN : x / m));
  const vsd = rstd(v, 20);
  ind.vol_z = map(v, (x, i) => (vsd[i] === 0 || !ok(vsd[i]) ? NaN : (x - ind.vol_avg20[i]) / vsd[i]));
  ind.ret1 = pct(c, 1);
  ind.ret10 = pct(c, 10);
  ind.vol21 = rstd(ind.ret1, 21);
  ind.vol63 = rstd(ind.ret1, 63);
  ind.lr_slope50 = lrSlope(c, 50);
  if (benchClose && benchClose.some(ok)) {
    ind.rs21 = zip(pct(c, 21), pct(benchClose, 21), (x, y) => x - y);
    ind.rs63 = zip(pct(c, 63), pct(benchClose, 63), (x, y) => x - y);
  } else {
    ind.rs21 = full(c.length, 0);
    ind.rs63 = full(c.length, 0);
  }
  return ind;
}
