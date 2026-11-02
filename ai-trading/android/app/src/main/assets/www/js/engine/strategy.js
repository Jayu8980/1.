// Trade setups, the trade simulator and the parameter search (a port of trader/strategies.py).
import { ok, shift } from './series.js';

export const SETUPS = {
  trend_pullback: { rsi2: [5, 10, 20] },
  breakout: { lookback: [20, 55], vol_mult: [1.0, 1.5] },
  momentum: { threshold: [0.3, 0.45, 0.6] },
  mean_reversion: { rsi: [25, 30, 35] },
  supertrend_flip: {},
  macd_cross: {},
};
export const WARMUP = 200;

export function expand(grid) {
  let out = [{}];
  for (const [k, values] of Object.entries(grid)) {
    out = out.flatMap((p) => values.map((v) => ({ ...p, [k]: v })));
  }
  return out;
}
export const describe = (name, p) => {
  const inner = Object.entries(p).map(([k, v]) => `${k}=${v}`).join(', ');
  return inner ? `${name}(${inner})` : name;
};

// true where the setup fires at the close of that bar (d = +1 long, -1 short)
export function setupSignal(name, p, ind, score, d) {
  const n = ind.close.length, sig = new Uint8Array(n), c = ind.close;
  const prevScore = shift(score), stPrev = shift(ind.st_dir), mPrev = shift(ind.macd), sPrev = shift(ind.macd_signal);
  for (let i = 0; i < n; i++) {
    let s = false;
    if (name === 'trend_pullback') {
      s = d > 0
        ? c[i] > ind.sma200[i] && ind.sma50[i] > ind.sma200[i] && ind.rsi2[i] < p.rsi2
        : c[i] < ind.sma200[i] && ind.sma50[i] < ind.sma200[i] && ind.rsi2[i] > 100 - p.rsi2;
    } else if (name === 'breakout') {
      const crossed = d > 0 ? c[i] > ind[`dc_high${p.lookback}`][i] : c[i] < ind[`dc_low${p.lookback}`][i];
      const vr = ind.vol_ratio[i];
      s = crossed && (!ok(vr) || vr >= p.vol_mult); // indices have no volume
    } else if (name === 'momentum') {
      s = score[i] * d >= p.threshold && prevScore[i] * d < p.threshold;
    } else if (name === 'mean_reversion') {
      s = d > 0
        ? c[i] < ind.bb_low[i] && ind.rsi14[i] < p.rsi && c[i] > ind.sma200[i]
        : c[i] > ind.bb_up[i] && ind.rsi14[i] > 100 - p.rsi && c[i] < ind.sma200[i];
    } else if (name === 'supertrend_flip') {
      s = ind.st_dir[i] === d && stPrev[i] === -d;
    } else if (name === 'macd_cross') {
      const m = ind.macd[i], sg = ind.macd_signal[i];
      s = d > 0
        ? m > sg && mPrev[i] <= sPrev[i] && c[i] > ind.sma200[i]
        : m < sg && mPrev[i] >= sPrev[i] && c[i] < ind.sma200[i];
    }
    sig[i] = s ? 1 : 0; // comparisons with NaN are false, like pandas
  }
  return sig;
}

// walk a trade entered at the open of bar e; stop assumed first when both levels sit in one bar
export function tradeExit(o, h, l, c, e, last, d, stop, target) {
  for (let j = e; j <= last; j++) {
    if (d > 0) {
      if (l[j] <= stop) return [j === e ? stop : Math.min(o[j], stop), j];
      if (h[j] >= target) return [j === e ? target : Math.max(o[j], target), j];
    } else {
      if (h[j] >= stop) return [j === e ? stop : Math.max(o[j], stop), j];
      if (l[j] <= target) return [j === e ? target : Math.min(o[j], target), j];
    }
  }
  return [c[last], last];
}

export function simulate(ind, signalIdx, d, stopAtr, rr, maxHold, fee = 0.0005) {
  const { open: o, high: h, low: l, close: c, atr } = ind, n = c.length;
  const rs = [], entries = [];
  let freeFrom = 0;
  for (const i of signalIdx) {
    if (i < freeFrom) continue;
    const e = i + 1;
    if (e >= n) break;
    const a = atr[i];
    if (!(a > 0)) continue;
    const entry = o[e], risk = stopAtr * a;
    const [px, bar] = tradeExit(o, h, l, c, e, Math.min(e + maxHold - 1, n - 1), d, entry - d * risk, entry + d * rr * risk);
    rs.push((d * (px - entry)) / risk - (fee * (entry + px)) / risk);
    entries.push(e);
    freeFrom = bar;
  }
  return { rs, entries };
}

export function stats(r) {
  const n = r.length;
  if (!n) return { trades: 0, win_rate: 0, expectancy: 0, profit_factor: 0, total_r: 0, max_dd_r: 0, sqn: 0 };
  let win = 0, loss = 0, wins = 0, eq = 0, peak = 0, dd = 0, sum = 0;
  for (const x of r) {
    if (x > 0) { win += x; wins++; } else loss -= x;
    sum += x; eq += x; peak = Math.max(peak, eq); dd = Math.max(dd, peak - eq);
  }
  const mean = sum / n;
  const sd = n > 1 ? Math.sqrt(r.reduce((t, x) => t + (x - mean) ** 2, 0) / (n - 1)) : 0;
  const pf = loss > 0 ? win / loss : win > 0 ? 99 : 0;
  return { trades: n, win_rate: wins / n, expectancy: mean, profit_factor: Math.min(pf, 99), total_r: sum, max_dd_r: dd,
    sqn: sd > 0 ? (Math.sqrt(Math.min(n, 100)) * mean) / sd : 0 };
}

function validate(v, oc) {
  const ins = v.in_sample, oos = v.out_of_sample;
  if (ins.expectancy <= 0) return [false, 'no in-sample edge'];
  if (oos.trades < oc.min_trades_oos) return [false, `only ${oos.trades} out-of-sample trades`];
  if (oos.expectancy < oc.min_expectancy_oos) return [false, `out-of-sample expectancy ${oos.expectancy.toFixed(2)}R`];
  if (oos.profit_factor < oc.min_profit_factor_oos) return [false, `out-of-sample profit factor ${oos.profit_factor.toFixed(2)}`];
  if (oos.max_dd_r > oc.max_drawdown_r) return [false, `out-of-sample drawdown ${oos.max_dd_r.toFixed(1)}R`];
  if (v.robustness < oc.min_robustness) return [false, `fragile: only ${Math.round(v.robustness * 100)}% of exit settings profitable`];
  return [true, 'validated'];
}

export function countBacktests(oc) {
  const variants = Object.values(SETUPS).reduce((t, g) => t + expand(g).length, 0);
  return variants * oc.stop_atr.length * oc.reward_risk.length * oc.max_hold.length;
}

// every setup x exit combination; the winner is chosen in sample and validated out of sample
export function optimize(ind, score, d, oc) {
  const n = ind.close.length, split = Math.floor(n * oc.in_sample_frac), fresh = Math.max(1, oc.signal_freshness);
  const exits = [];
  for (const s of oc.stop_atr) for (const r of oc.reward_risk) for (const h of oc.max_hold) exits.push([s, r, h]);
  const variants = [];
  for (const [name, grid] of Object.entries(SETUPS)) {
    for (const params of expand(grid)) {
      const sig = setupSignal(name, params, ind, score, d);
      const idx = [];
      for (let i = WARMUP; i < n - 1; i++) if (sig[i]) idx.push(i);
      let active = false;
      for (let i = n - fresh; i < n; i++) if (sig[i]) active = true;
      let best = null, evaluated = 0, profitable = 0;
      for (const [stopAtr, rr, hold] of exits) {
        const { rs, entries } = simulate(ind, idx, d, stopAtr, rr, hold, oc.fee_pct);
        const insR = [], oosR = [];
        entries.forEach((e, k) => (e < split ? insR : oosR).push(rs[k]));
        const sIn = stats(insR);
        if (sIn.trades < oc.min_trades_is) continue;
        evaluated++;
        if (sIn.expectancy > 0) profitable++;
        if (!best || sIn.sqn > best.in_sample.sqn) {
          best = { stop_atr: stopAtr, reward_risk: rr, max_hold: hold, in_sample: sIn, out_of_sample: stats(oosR), all: stats(rs) };
        }
      }
      if (!best) continue;
      const v = { setup: name, params, label: describe(name, params), active, robustness: profitable / evaluated, ...best };
      [v.validated, v.why] = validate(v, oc);
      variants.push(v);
    }
  }
  return variants.sort((a, b) => b.in_sample.sqn - a.in_sample.sqn);
}

// R multiple of the trade a signal at the close of each bar would have produced (NaN if unknown)
export function tradeOutcomes(ind, d, stopAtr, rr, maxHold, fee = 0.0005) {
  const { open: o, high: h, low: l, close: c, atr } = ind, n = c.length;
  const out = new Float64Array(n).fill(NaN);
  for (let i = 0; i < n - maxHold - 1; i++) {
    const a = atr[i];
    if (!(a > 0)) continue;
    const e = i + 1, entry = o[e], risk = stopAtr * a;
    const [px] = tradeExit(o, h, l, c, e, e + maxHold - 1, d, entry - d * risk, entry + d * rr * risk);
    out[i] = (d * (px - entry)) / risk - (fee * (entry + px)) / risk;
  }
  return out;
}
