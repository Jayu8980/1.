// Machine-learning forecaster (a port of trader/agents/ml.py). The scikit-learn ensemble is
// replaced by two models written here: gradient-boosted trees (histogram based, like
// HistGradientBoosting) and an L2 logistic regression. Validation is the same purged walk-forward.
import { ok, pct, full } from './series.js';

export const FEATURES = ['ret_1', 'ret_5', 'ret_10', 'ret_21', 'ret_63', 'dist_sma20', 'dist_sma50', 'dist_sma200',
  'sma50_vs_200', 'rsi14', 'rsi2', 'macd_hist', 'atr_pct', 'vol21', 'vol_ratio_21_63', 'bb_pctb', 'bb_width',
  'stoch_k', 'adx', 'di_spread', 'cmf', 'mfi', 'donchian_pos', 'volume_z', 'rs21', 'rs63', 'lr_slope',
  'drawdown_252', 'supertrend'];

export function makeFeatures(ind) {
  const c = ind.close, n = c.length;
  const cols = {};
  for (const k of [1, 5, 10, 21, 63]) cols[`ret_${k}`] = pct(c, k);
  const at = (f) => { const a = full(n); for (let i = 0; i < n; i++) a[i] = f(i); return a; };
  for (const k of [20, 50, 200]) cols[`dist_sma${k}`] = at((i) => c[i] / ind[`sma${k}`][i] - 1);
  cols.sma50_vs_200 = at((i) => ind.sma50[i] / ind.sma200[i] - 1);
  cols.rsi14 = at((i) => ind.rsi14[i] / 100);
  cols.rsi2 = at((i) => ind.rsi2[i] / 100);
  cols.macd_hist = at((i) => ind.macd_hist[i] / c[i]);
  cols.atr_pct = at((i) => ind.atr[i] / c[i]);
  cols.vol21 = ind.vol21;
  cols.vol_ratio_21_63 = at((i) => ind.vol21[i] / ind.vol63[i]);
  cols.bb_pctb = ind.bb_pctb;
  cols.bb_width = ind.bb_width;
  cols.stoch_k = at((i) => ind.stoch_k[i] / 100);
  cols.adx = at((i) => ind.adx[i] / 100);
  cols.di_spread = at((i) => (ind.plus_di[i] - ind.minus_di[i]) / 100);
  cols.cmf = ind.cmf;
  cols.mfi = at((i) => ind.mfi[i] / 100);
  cols.donchian_pos = at((i) => (c[i] - ind.dc_low20[i]) / (ind.dc_high20[i] - ind.dc_low20[i]));
  cols.volume_z = ind.vol_z;
  cols.rs21 = ind.rs21;
  cols.rs63 = ind.rs63;
  cols.lr_slope = at((i) => ind.lr_slope50[i] * 252);
  let peak = -Infinity;
  cols.drawdown_252 = at((i) => {
    let m = -Infinity, cnt = 0;
    for (let j = Math.max(0, i - 251); j <= i; j++) if (ok(c[j])) { m = Math.max(m, c[j]); cnt++; }
    peak = m;
    return cnt >= 50 ? c[i] / peak - 1 : NaN;
  });
  cols.supertrend = ind.st_dir;
  // row-major matrix, infinities turned into NaN
  return Array.from({ length: n }, (_, i) => FEATURES.map((f) => (ok(cols[f][i]) ? cols[f][i] : NaN)));
}

// ---- shared preprocessing -------------------------------------------------------------------
function medians(X) {
  const m = X[0].length;
  return Array.from({ length: m }, (_, j) => {
    const v = X.map((r) => r[j]).filter(ok).sort((a, b) => a - b);
    return v.length ? v[Math.floor(v.length / 2)] : 0;
  });
}
const impute = (X, med) => X.map((r) => r.map((x, j) => (ok(x) ? x : med[j])));
const sigmoid = (z) => 1 / (1 + Math.exp(-Math.max(-35, Math.min(35, z))));

// ---- L2 logistic regression on standardized features ---------------------------------------
function fitLogit(X, y, C = 0.3, iters = 250) {
  const med = medians(X), Xi = impute(X, med), n = Xi.length, m = Xi[0].length;
  const mu = Array(m).fill(0), sd = Array(m).fill(0);
  for (const r of Xi) r.forEach((x, j) => (mu[j] += x / n));
  for (const r of Xi) r.forEach((x, j) => (sd[j] += (x - mu[j]) ** 2 / n));
  for (let j = 0; j < m; j++) sd[j] = Math.sqrt(sd[j]) || 1;
  const Z = Xi.map((r) => r.map((x, j) => (x - mu[j]) / sd[j]));
  const w = Array(m).fill(0);
  let b = 0;
  const lam = 1 / (C * n), lr = 0.5;
  for (let it = 0; it < iters; it++) {
    const gw = Array(m).fill(0);
    let gb = 0;
    for (let i = 0; i < n; i++) {
      let z = b;
      for (let j = 0; j < m; j++) z += w[j] * Z[i][j];
      const e = sigmoid(z) - y[i];
      gb += e / n;
      for (let j = 0; j < m; j++) gw[j] += (e * Z[i][j]) / n;
    }
    for (let j = 0; j < m; j++) w[j] -= lr * (gw[j] + lam * w[j]);
    b -= lr * gb;
  }
  return (rows) => impute(rows, med).map((r) => {
    let z = b;
    for (let j = 0; j < m; j++) z += (w[j] * (r[j] - mu[j])) / sd[j];
    return sigmoid(z);
  });
}

// ---- histogram gradient boosting (log loss, Newton leaves) --------------------------------
function fitBoost(X, y, { rounds = 100, lr = 0.06, depth = 3, minLeaf = 30, lambda = 1, bins = 32 } = {}) {
  const med = medians(X), Xi = impute(X, med), n = Xi.length, m = Xi[0].length;
  const edges = Array.from({ length: m }, (_, j) => {
    const v = Xi.map((r) => r[j]).sort((a, b) => a - b), cuts = [];
    for (let k = 1; k < bins; k++) {
      const q = v[Math.floor((k * (n - 1)) / bins)];
      if (cuts.length === 0 || q > cuts[cuts.length - 1]) cuts.push(q);
    }
    return cuts;
  });
  const binOf = (x, j) => { const e = edges[j]; let lo = 0, hi = e.length; while (lo < hi) { const md = (lo + hi) >> 1; if (x <= e[md]) hi = md; else lo = md + 1; } return lo; };
  const B = Xi.map((r) => r.map((x, j) => binOf(x, j)));
  const p0 = Math.min(0.99, Math.max(0.01, y.reduce((t, v) => t + v, 0) / n));
  const base = Math.log(p0 / (1 - p0));
  const F = new Float64Array(n).fill(base), trees = [], gain = new Float64Array(m);
  for (let r = 0; r < rounds; r++) {
    const g = new Float64Array(n), h = new Float64Array(n);
    for (let i = 0; i < n; i++) { const p = sigmoid(F[i]); g[i] = p - y[i]; h[i] = Math.max(p * (1 - p), 1e-6); }
    const build = (rows, level) => {
      let G = 0, H = 0;
      for (const i of rows) { G += g[i]; H += h[i]; }
      const leaf = { value: (-G / (H + lambda)) * lr };
      if (level >= depth || rows.length < 2 * minLeaf) return leaf;
      let best = null;
      for (let j = 0; j < m; j++) {
        const nb = edges[j].length + 1, hg = new Float64Array(nb), hh = new Float64Array(nb), hc = new Int32Array(nb);
        for (const i of rows) { const k = B[i][j]; hg[k] += g[i]; hh[k] += h[i]; hc[k]++; }
        let gl = 0, hl = 0, cl = 0;
        for (let k = 0; k < nb - 1; k++) {
          gl += hg[k]; hl += hh[k]; cl += hc[k];
          const cr = rows.length - cl;
          if (cl < minLeaf || cr < minLeaf) continue;
          const gr = G - gl, hr = H - hl;
          const score = (gl * gl) / (hl + lambda) + (gr * gr) / (hr + lambda) - (G * G) / (H + lambda);
          if (!best || score > best.score) best = { score, j, k };
        }
      }
      if (!best || best.score <= 1e-9) return leaf;
      gain[best.j] += best.score;
      const left = [], right = [];
      for (const i of rows) (B[i][best.j] <= best.k ? left : right).push(i);
      return { j: best.j, k: best.k, left: build(left, level + 1), right: build(right, level + 1) };
    };
    const tree = build(Array.from({ length: n }, (_, i) => i), 0);
    const walk = (node, bRow) => (node.left ? walk(bRow[node.j] <= node.k ? node.left : node.right, bRow) : node.value);
    for (let i = 0; i < n; i++) F[i] += walk(tree, B[i]);
    trees.push(tree);
  }
  const predict = (rows) => impute(rows, med).map((r) => {
    const bRow = r.map((x, j) => binOf(x, j));
    let z = base;
    for (const t of trees) {
      let node = t;
      while (node.left) node = bRow[node.j] <= node.k ? node.left : node.right;
      z += node.value;
    }
    return sigmoid(z);
  });
  predict.gain = gain;
  return predict;
}

function ensemble(Xtr, ytr, Xte) {
  const boost = fitBoost(Xtr, ytr), logit = fitLogit(Xtr, ytr);
  const pb = boost(Xte), pl = logit(Xte);
  return { p: pb.map((x, i) => (x + pl[i]) / 2), gain: boost.gain };
}

export function auc(y, p) {
  const idx = p.map((v, i) => i).sort((a, b) => p[a] - p[b]);
  const ranks = new Float64Array(p.length);
  for (let i = 0; i < idx.length;) {
    let j = i;
    while (j + 1 < idx.length && p[idx[j + 1]] === p[idx[i]]) j++;
    for (let k = i; k <= j; k++) ranks[idx[k]] = (i + j) / 2 + 1;
    i = j + 1;
  }
  let pos = 0, sum = 0;
  y.forEach((v, i) => { if (v === 1) { pos++; sum += ranks[i]; } });
  const neg = y.length - pos;
  return pos && neg ? (sum - (pos * (pos + 1)) / 2) / (pos * neg) : NaN;
}

// purged walk-forward evaluation, then a final fit on all labelled history
export function walkForward(X, y, { nSplits = 4, minTrain = 400, purge = 11 } = {}) {
  const rows = [];
  for (let i = 0; i < X.length; i++) {
    const valid = X[i].filter(ok).length / X[i].length;
    if (ok(y[i]) && valid >= 0.9) rows.push(i);
  }
  const Xl = rows.map((i) => X[i]), yl = rows.map((i) => y[i]), n = rows.length;
  if (n < minTrain + 100 || new Set(yl).size < 2) return null;
  const fold = Math.floor((n - minTrain) / nSplits), oos = new Float64Array(n).fill(NaN);
  for (let k = 0; k < nSplits; k++) {
    const start = minTrain + k * fold, end = k === nSplits - 1 ? n : start + fold, trainEnd = start - purge;
    const ytr = yl.slice(0, trainEnd);
    if (new Set(ytr).size < 2) continue;
    const { p } = ensemble(Xl.slice(0, trainEnd), ytr, Xl.slice(start, end));
    p.forEach((v, i) => (oos[start + i] = v));
  }
  const ys = [], ps = [], oosRows = [];
  for (let i = 0; i < n; i++) if (ok(oos[i])) { ys.push(yl[i]); ps.push(oos[i]); oosRows.push(rows[i]); }
  if (ps.length < 50 || new Set(ys).size < 2) return null;
  const final = ensemble(Xl, yl, [X[X.length - 1]]);
  const top = Array.from(final.gain).map((g, j) => [g, FEATURES[j]]).sort((a, b) => b[0] - a[0]).slice(0, 5).map((x) => x[1]);
  return { prob: final.p[0], auc: auc(ys, ps), base_rate: yl.reduce((t, v) => t + v, 0) / n, samples: n,
    oos_samples: ps.length, top_features: top, oos: { rows: oosRows, p: ps } };
}
