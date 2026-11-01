// The twelve agents and the layer-by-layer funnel (a port of trader/agents/* and trader/desk.py).
// News sentiment, fundamentals and the Claude review need data a phone can't fetch reliably; on the
// phone they pass through and say so, and they run in full on the desk server.
import { ok, clip, sign, tanh, last, full, resample, ema, macd, rsi, barsPerYear, computeAll, alignTo, pct } from './series.js';
import { optimize, countBacktests, tradeOutcomes } from './strategy.js';
import { makeFeatures, walkForward } from './ml.js';

export const INDEX_SYMBOLS = new Set(['^NSEI', '^NSEBANK', 'NIFTY_FIN_SERVICE.NS', 'NIFTY_MID_SELECT.NS', '^NSMIDCP', '^BSESN']);
export const isIndex = (s) => s.startsWith('^') || INDEX_SYMBOLS.has(s);
export const isCrypto = (s) => s.includes('/') || /-(USD|USDT|USDC|EUR|GBP|INR|JPY|BTC|ETH)$/i.test(s);

export const LAYERS = [
  { name: 'liquidity', layer: 1, title: 'Liquidity & Data-Quality Screener', role: 'Drops missing, stale or corrupt data, short histories, very low prices and thin trading.' },
  { name: 'regime', layer: 2, title: 'Market Regime Analyst', role: "Reads the benchmark's trend, volatility and breadth, tells every agent the regime, and sets each symbol's direction." },
  { name: 'technical', layer: 3, title: 'Technical Indicator Analyst', role: '19 indicators across trend, momentum, volume, bands and relative strength must agree with the trade.' },
  { name: 'timeframe', layer: 4, title: 'Multi-Timeframe Analyst', role: 'Weekly and monthly trends must point the same way as the daily trade.' },
  { name: 'price_action', layer: 5, title: 'Price Action & Levels Analyst', role: 'Room to the next support/resistance, no over-extended entries, candlestick patterns.' },
  { name: 'optimizer', layer: 6, title: 'Strategy Optimizer & Backtester', role: '540 backtests per symbol; the best setup is chosen in sample, validated out of sample, and must fire now.' },
  { name: 'ml', layer: 7, title: 'Machine-Learning Forecaster', role: 'Boosted trees + logistic regression with purged walk-forward validation estimate P(profit).' },
  { name: 'sentiment', layer: 8, title: 'News & Sentiment Analyst', role: 'Recent headlines scored by Claude (runs on the desk server).' },
  { name: 'fundamental', layer: 9, title: 'Fundamental & Event-Risk Analyst', role: 'Business quality and the earnings blackout (runs on the desk server).' },
  { name: 'risk', layer: 10, title: 'Risk Manager', role: 'Exact entry, stop, target and size from the risk budget; position, heat and cash limits.' },
  { name: 'decision', layer: 11, title: 'Chief Decision Maker', role: 'Conviction x expected value ranking; keeps the best few that are not too correlated.' },
  { name: 'ai_review', layer: 12, title: 'AI Chief Analyst (Claude)', role: 'Reads every report and approves or vetoes (runs on the desk server).' },
];

const pass = (score, reasons, data = {}) => ({ passed: true, score, reasons, veto: '', data });
const fail = (veto, data = {}, score = 0) => ({ passed: false, score, reasons: [], veto, data });
const skipped = (why) => ({ passed: true, score: null, reasons: [why], veto: '', data: {} });
const pctFmt = (x, digits = 1) => `${x >= 0 ? '+' : ''}${(x * 100).toFixed(digits)}%`;
const money = (x) => (Math.abs(x) >= 1e9 ? `${(x / 1e9).toFixed(1)}B` : Math.abs(x) >= 1e6 ? `${(x / 1e6).toFixed(1)}M` : Math.abs(x) >= 1e3 ? `${(x / 1e3).toFixed(1)}K` : x.toFixed(0));
const mean = (a) => a.reduce((t, x) => t + x, 0) / a.length;

// ---- L1 liquidity ----------------------------------------------------------------------------
function liquidity(c, cfg, ctx) {
  const lc = cfg.liquidity, b = c.bars;
  if (!b || !b.c.length) return fail('no market data returned');
  const n = b.c.length;
  if (n < lc.min_bars) return fail(`only ${n} bars of history (needs ${lc.min_bars})`);
  const age = Math.floor((ctx.now - last(b.t)) / 86400000);
  if (age > lc.max_stale_days) return fail(`stale data: last bar is ${age} days old`);
  const price = last(b.c);
  if (price < lc.min_price) return fail(`price ${price.toFixed(2)} below the ${lc.min_price} minimum`);
  const noVolume = c.symbol.endsWith('=X') || isIndex(c.symbol);
  const turnoverArr = [];
  for (let i = Math.max(0, n - 20); i < n; i++) turnoverArr.push(b.c[i] * b.v[i]);
  const turnover = mean(turnoverArr), minTurnover = ctx.minTurnover;
  if (!noVolume) {
    let zero = 0;
    for (let i = Math.max(0, n - 60); i < n; i++) if (!(b.v[i] > 0)) zero++;
    if (zero / Math.min(60, n) > 0.2) return fail('volume missing on more than 20% of recent bars');
    if (minTurnover && turnover < minTurnover) return fail(`average turnover ${money(turnover)} below ${money(minTurnover)}`);
  }
  let worst = 0;
  for (let i = Math.max(1, n - 252); i < n; i++) worst = Math.max(worst, Math.abs(b.c[i] / b.c[i - 1] - 1));
  if (worst > lc.max_daily_move) return fail(`suspicious ${(worst * 100).toFixed(0)}% one-bar move in the last year`);
  const depth = minTurnover && !noVolume ? clip(Math.log10(Math.max(turnover, 1) / minTurnover) / 2) : 0.5;
  return pass(0.5 + 0.5 * depth, [`${n} bars, price ${price.toFixed(2)}, avg turnover ${noVolume ? 'n/a' : money(turnover)}`], { bars: n, price, turnover });
}

// ---- L2 regime --------------------------------------------------------------------------------
const THRESHOLD_BOOST = { bull: { long: 0, short: 0.15 }, bear: { long: 0.15, short: 0 }, neutral: { long: 0.05, short: 0.05 }, crisis: { long: 0.2, short: 0.1 } };
const RISK_BY_VOL = { low: 1, normal: 1, high: 0.6, crisis: 0.3 };

export function analyseMarket(bench, volIndex, frames, rc) {
  const ctx = { regime: 'neutral', trend_score: 0, volatility: 'normal', vol_measure: null, breadth: null, notes: [] };
  let trend = 0, volPct = null;
  if (bench && bench.c.length >= 260) {
    const c = bench.c, n = c.length;
    const s50 = mean(Array.from(c.slice(n - 50))), s200 = mean(Array.from(c.slice(n - 200)));
    const r63 = c[n - 1] / c[n - 64] - 1;
    trend = (Math.sign(c[n - 1] - s200) + Math.sign(s50 - s200) + Math.sign(r63)) / 3;
    let peak = -Infinity;
    for (let i = Math.max(0, n - 252); i < n; i++) peak = Math.max(peak, c[i]);
    Object.assign(ctx, { trend_score: trend, benchmark_return_63d: r63, benchmark_drawdown: c[n - 1] / peak - 1 });
    const rets = pct(c), ann = Math.sqrt(barsPerYear(bench.t)), realized = [];
    for (let i = 22; i < n; i++) {
      const w = Array.from(rets.slice(i - 20, i + 1)), m = mean(w);
      realized.push(Math.sqrt(w.reduce((t, x) => t + (x - m) ** 2, 0) / 20) * ann);
    }
    const hist = realized.slice(-756);
    if (hist.length > 100) {
      const cur = last(hist);
      volPct = hist.filter((x) => x < cur).length / hist.length;
      ctx.realized_vol = cur;
    }
  } else ctx.notes.push('benchmark data unavailable - assuming a neutral market');
  let vol = 'normal';
  if (volIndex && volIndex.c.length) {
    const level = last(volIndex.c);
    ctx.vol_measure = `volatility index ${level.toFixed(1)}`;
    vol = level >= rc.crisis_vol_index ? 'crisis' : level >= rc.high_vol_index ? 'high' : level < 15 ? 'low' : 'normal';
  } else if (volPct !== null) {
    ctx.vol_measure = `realized vol ${(ctx.realized_vol * 100).toFixed(0)}% (${(volPct * 100).toFixed(0)}th pct)`;
    vol = volPct >= 0.97 ? 'crisis' : volPct >= 0.85 ? 'high' : volPct < 0.25 ? 'low' : 'normal';
  }
  ctx.volatility = vol;
  const above = frames.filter((b) => b && b.c.length >= 50).map((b) => (last(b.c) > mean(Array.from(b.c.slice(-50))) ? 1 : 0));
  ctx.breadth = above.length ? mean(above) : null;
  const regime = vol === 'crisis' && trend < 0 ? 'crisis' : trend >= 0.33 && (ctx.breadth === null || ctx.breadth >= 0.4) ? 'bull' : trend <= -0.33 ? 'bear' : 'neutral';
  ctx.regime = regime;
  ctx.risk_multiplier = RISK_BY_VOL[vol] * (regime === 'bear' ? 0.75 : 1);
  ctx.threshold_boost = THRESHOLD_BOOST[regime];
  return ctx;
}

function regimeAgent(c, cfg, ctx) {
  const rc = cfg.regime, regime = ctx.market.regime, ind = c.ind, n = ind.close.length;
  if (regime === 'crisis' && rc.halt_on_crisis) return fail('market is in a crisis regime - new trades halted');
  const close = ind.close[n - 1], s50 = ind.sma50[n - 1], s200 = ind.sma200[n - 1];
  const slope = ind.sma200[n - 1] / ind.sma200[n - 21] - 1;
  const total = [sign(close - s200), sign(s50 - s200), sign(slope)].filter(ok).reduce((t, x) => t + x, 0);
  const rs = ok(ind.rs63[n - 1]) ? ind.rs63[n - 1] : 0;
  const data = { trend_votes: total, rs63: rs, market: regime };
  if (total === 0) return fail('no clear primary trend', data);
  const side = total > 0 ? 'long' : 'short';
  if (side === 'short' && !rc.allow_short) return fail(`primary trend is down (${-total}/3 bearish) and short selling is off`, data);
  if (side === 'long' && regime === 'bear' && rc.bear_longs_need_rs && rs <= 0) return fail(`bear market and lagging the benchmark (63-day RS ${pctFmt(rs)})`, data);
  if (side === 'short' && regime === 'bull' && rs >= 0) return fail(`bull market and beating the benchmark (${pctFmt(rs)}) - no short`, data);
  c.side = side;
  c.d = side === 'long' ? 1 : -1;
  const align = ({ bull: 1, bear: -1 }[regime] || 0) * c.d;
  return pass(clip(0.5 + (0.25 * Math.abs(total)) / 3 + 0.25 * align), [`primary trend ${side} (${Math.abs(total)}/3 signals)`, `market ${regime}`, `63-day relative strength ${pctFmt(rs)}`], data);
}

// ---- L3 technical -----------------------------------------------------------------------------
const GROUPS = {
  trend: [0.35, ['ma_stack', 'ema_cross', 'adx_dmi', 'supertrend', 'lr_slope']],
  momentum: [0.3, ['rsi', 'macd', 'stochastic', 'roc', 'williams_r', 'cci']],
  volume: [0.15, ['obv', 'mfi', 'cmf', 'volume_thrust']],
  position: [0.1, ['bollinger', 'donchian']],
  relative: [0.1, ['rel_strength']],
};
export function indicatorVotes(ind, i) {
  const a = ind.atr[i] || NaN, c = ind.close[i], cl = (x) => (ok(x) ? Math.max(-1, Math.min(1, x)) : NaN);
  const vr = ind.vol_ratio[i];
  return {
    ma_stack: (sign(c - ind.sma20[i]) + sign(ind.sma20[i] - ind.sma50[i]) + sign(ind.sma50[i] - ind.sma200[i])) / 3,
    ema_cross: tanh((ind.ema9[i] - ind.ema21[i]) / a),
    adx_dmi: sign(ind.plus_di[i] - ind.minus_di[i]) * Math.max(0, Math.min(1, (ind.adx[i] - 15) / 20)),
    supertrend: ind.st_dir[i],
    lr_slope: tanh((ind.lr_slope50[i] * 252) / 0.4),
    rsi: cl((ind.rsi14[i] - 50) / 20),
    macd: tanh(ind.macd_hist[i] / (0.2 * a)),
    stochastic: 0.5 * cl((ind.stoch_k[i] - 50) / 50) + 0.5 * sign(ind.stoch_k[i] - ind.stoch_d[i]),
    roc: tanh(ind.ret10[i] / (ind.vol21[i] * Math.sqrt(10))),
    williams_r: cl((ind.willr[i] + 50) / 50),
    cci: tanh(ind.cci[i] / 100),
    obv: sign(ind.obv[i] - ind.obv_ema20[i]),
    mfi: cl((ind.mfi[i] - 50) / 30),
    cmf: cl(ind.cmf[i] / 0.2),
    volume_thrust: vr > 1.2 ? sign(ind.ret1[i]) * Math.max(0, Math.min(1, vr - 1)) : 0,
    bollinger: cl((ind.bb_pctb[i] - 0.5) * 2),
    donchian: cl((2 * (c - ind.dc_low20[i])) / (ind.dc_high20[i] - ind.dc_low20[i]) - 1),
    rel_strength: tanh(ind.rs63[i] / 0.1),
  };
}
const groupMean = (v, cols) => { const xs = cols.map((k) => v[k]).filter(ok); return xs.length ? mean(xs) : NaN; };
export function compositeAt(v) {
  let t = 0;
  for (const [w, cols] of Object.values(GROUPS)) { const m = groupMean(v, cols); t += w * (ok(m) ? m : 0); }
  return t;
}
function technical(c, cfg, ctx) {
  const ind = c.ind, n = ind.close.length, score = full(n);
  let lastVotes = null;
  for (let i = 0; i < n; i++) { const v = indicatorVotes(ind, i); score[i] = compositeAt(v); if (i === n - 1) lastVotes = v; }
  c.cache.score = score;
  const s = score[n - 1] * c.d, need = cfg.technical.min_score + (ctx.market.threshold_boost[c.side] || 0);
  const signed = Object.entries(lastVotes).filter(([, x]) => ok(x)).map(([k, x]) => [k, x * c.d]);
  const agree = signed.filter(([, x]) => x > 0.1).length, disagree = signed.filter(([, x]) => x < -0.1).length;
  const groups = Object.fromEntries(Object.entries(GROUPS).map(([g, [, cols]]) => [g, groupMean(lastVotes, cols) * c.d]));
  const data = { confluence: s, threshold: need, agree, disagree, groups, votes: Object.fromEntries(signed) };
  if (s < need) return fail(`indicator confluence ${s.toFixed(2)} below ${need.toFixed(2)} (${agree} agree, ${disagree} disagree)`, data);
  const strongest = signed.sort((a, b) => b[1] - a[1]).slice(0, 3).map(([k, x]) => `${k} ${x >= 0 ? '+' : ''}${x.toFixed(2)}`).join(', ');
  return pass(clip((s + 1) / 2), [`confluence ${s >= 0 ? '+' : ''}${s.toFixed(2)} (needs ${need.toFixed(2)})`, `${agree}/${signed.length} indicators agree, ${disagree} disagree`, `strongest: ${strongest}`], data);
}

// ---- L4 timeframe -----------------------------------------------------------------------------
function timeframe(c, cfg) {
  const d = c.d, wk = resample(c.bars, 'W').c, mo = resample(c.bars, 'M').c, votes = {};
  if (wk.length >= 35) {
    const e10 = ema(wk, 10), e30 = ema(wk, 30), m = macd(wk), r = rsi(wk, 14), n = wk.length - 1;
    votes['weekly close vs 10-week EMA'] = sign(wk[n] - e10[n]);
    votes['weekly 10/30 EMA'] = sign(e10[n] - e30[n]);
    votes['weekly MACD'] = sign(m.hist[n]);
    votes['weekly RSI vs 50'] = sign(r[n] - 50);
  }
  if (mo.length >= 13) {
    const n = mo.length - 1, s10 = mean(Array.from(mo.slice(n - 9)));
    votes['monthly close vs 10-month SMA'] = sign(mo[n] - s10);
    votes['12-month momentum'] = sign(mo[n] / mo[n - 12] - 1);
  }
  const valid = Object.entries(votes).filter(([, v]) => ok(v));
  if (!valid.length) return fail('not enough weekly and monthly history');
  const agreeing = valid.filter(([, v]) => v * d > 0).map(([k]) => k), against = valid.filter(([, v]) => v * d <= 0).map(([k]) => k);
  const share = agreeing.length / valid.length, data = { agreement: share, votes: Object.fromEntries(valid) };
  if (share < cfg.timeframe.min_agreement) return fail(`higher timeframes disagree (${agreeing.length}/${valid.length} agree; against: ${against.slice(0, 3).join(', ')})`, data);
  return pass(share, [`${agreeing.length}/${valid.length} weekly and monthly signals agree`, ...(against.length ? [`against: ${against.join(', ')}`] : [])], data);
}

// ---- L5 price action --------------------------------------------------------------------------
function swingLevels(h, l, k = 8) {
  const highs = [], lows = [];
  for (let i = k; i < h.length - k; i++) {
    let isH = true, isL = true;
    for (let j = i - k; j <= i + k; j++) { if (h[j] > h[i]) isH = false; if (l[j] < l[i]) isL = false; }
    if (isH) highs.push(h[i]);
    if (isL) lows.push(l[i]);
  }
  return { highs, lows };
}
function candlePatterns(b) {
  const n = b.c.length, [o1, h1, l1, c1] = [b.o[n - 2], b.h[n - 2], b.l[n - 2], b.c[n - 2]], [o, h, l, c] = [b.o[n - 1], b.h[n - 1], b.l[n - 1], b.c[n - 1]];
  const rng = h - l, pats = {};
  if (!(rng > 0)) return pats;
  const body = Math.abs(c - o), upper = h - Math.max(o, c), lower = Math.min(o, c) - l;
  if (c1 < o1 && c > o && c >= o1 && o <= c1) pats['bullish engulfing'] = 1;
  if (c1 > o1 && c < o && c <= o1 && o >= c1) pats['bearish engulfing'] = -1;
  if (lower >= 2 * body && upper <= body) pats.hammer = 0.7;
  if (upper >= 2 * body && lower <= body) pats['shooting star'] = -0.7;
  if (h < h1 && l > l1) pats['inside bar'] = 0;
  if (body / rng < 0.1) pats.doji = 0;
  if (c > o && (c - l) / rng > 0.8) pats['strong close'] = 0.5;
  if (c < o && (h - c) / rng > 0.8) pats['weak close'] = -0.5;
  return pats;
}
function priceAction(c, cfg) {
  const pc = cfg.price_action, ind = c.ind, b = c.bars, d = c.d, n = b.c.length;
  const close = ind.close[n - 1], atr = ind.atr[n - 1];
  if (!(atr > 0)) return fail('ATR unavailable');
  const s = Math.max(0, n - 260), H = b.h.slice(s), L = b.l.slice(s);
  const { highs, lows } = swingLevels(H, L);
  const above = highs.filter((x) => x > close * 1.001), below = lows.filter((x) => x < close * 0.999);
  const resistance = above.length ? Math.min(...above) : null, support = below.length ? Math.max(...below) : null;
  const obstacle = d > 0 ? resistance : support;
  const room = obstacle === null ? Infinity : Math.abs(obstacle - close) / atr;
  const extension = (d * (close - ind.ema20[n - 1])) / atr;
  const hi = Math.max(...H), lo = Math.min(...L);
  let pos = hi > lo ? (close - lo) / (hi - lo) : 0.5;
  if (d < 0) pos = 1 - pos;
  const pats = candlePatterns(b), patternScore = Math.max(-1, Math.min(1, d * Object.values(pats).reduce((t, x) => t + x, 0)));
  const gap = Math.abs(b.o[n - 1] - b.c[n - 2]) / atr;
  const data = { support, resistance, room_atr: Number.isFinite(room) ? room : null, extension_atr: extension, range_position: pos, patterns: Object.keys(pats), gap_atr: gap };
  if (extension > pc.max_extension_atr) return fail(`over-extended: ${extension.toFixed(1)} ATR from the 20-day EMA (max ${pc.max_extension_atr})`, data);
  const level = d > 0 ? 'resistance' : 'support';
  if (room < pc.min_room_atr) return fail(`only ${room.toFixed(1)} ATR to ${level} at ${obstacle.toFixed(2)}`, data);
  const reasons = [Number.isFinite(room) ? `${room.toFixed(1)} ATR of room to ${level} at ${obstacle.toFixed(2)}` : `no ${level} in the way in the last year`,
    `${extension >= 0 ? '+' : ''}${extension.toFixed(1)} ATR from the 20-day EMA`, `at ${(pos * 100).toFixed(0)}% of the 52-week range`];
  if (Object.keys(pats).length) reasons.push(`candles: ${Object.keys(pats).join(', ')}`);
  if (gap > 2) reasons.push(`large opening gap (${gap.toFixed(1)} ATR)`);
  const score = 0.35 * clip(room / 4) + 0.25 * (1 - clip(Math.max(extension, 0) / pc.max_extension_atr)) + 0.2 * (0.5 + 0.5 * patternScore) + 0.2 * pos - (gap > 2 ? 0.1 : 0);
  return pass(clip(score), reasons, data);
}

// ---- L6 optimizer -----------------------------------------------------------------------------
function optimizerAgent(c, cfg) {
  const oc = cfg.optimizer, variants = optimize(c.ind, c.cache.score, c.d, oc), backtests = countBacktests(oc);
  const validated = variants.filter((v) => v.validated), firing = validated.filter((v) => v.active);
  const data = { backtests, validated: validated.map((v) => v.label) };
  if (!validated.length) {
    const why = variants.length ? `best was ${variants[0].label}: ${variants[0].why}` : 'too few trades';
    return fail(`no setup survived out-of-sample validation (${why})`, data);
  }
  if (!firing.length) return fail(`${validated.length} validated setup(s) (${validated.slice(0, 3).map((v) => v.label).join(', ')}) but none is firing now`, data);
  const best = firing.reduce((a, b) => (b.all.sqn > a.all.sqn ? b : a));
  c.cache.setup = best;
  const ins = best.in_sample, oos = best.out_of_sample;
  Object.assign(data, { setup: best.label, stop_atr: best.stop_atr, reward_risk: best.reward_risk, max_hold: best.max_hold, in_sample: ins, out_of_sample: oos, robustness: best.robustness });
  const quality = 0.4 * clip(oos.expectancy / 0.6) + 0.3 * clip((oos.profit_factor - 1) / 2) + 0.3 * best.robustness;
  return pass(clip(quality), [
    `${best.label} is firing; stop ${best.stop_atr}xATR, target ${best.reward_risk}R, max ${best.max_hold} bars (best of ${backtests} backtests)`,
    `in-sample ${ins.trades} trades, PF ${ins.profit_factor.toFixed(2)}, ${ins.expectancy >= 0 ? '+' : ''}${ins.expectancy.toFixed(2)}R/trade`,
    `out-of-sample ${oos.trades} trades, PF ${oos.profit_factor.toFixed(2)}, ${oos.expectancy >= 0 ? '+' : ''}${oos.expectancy.toFixed(2)}R/trade, win ${(oos.win_rate * 100).toFixed(0)}%`,
    `profitable across ${(best.robustness * 100).toFixed(0)}% of exit settings`,
  ], data);
}

// ---- L7 machine learning ----------------------------------------------------------------------
function mlAgent(c, cfg) {
  const mc = cfg.ml, s = c.cache.setup;
  const X = makeFeatures(c.ind), outcomes = tradeOutcomes(c.ind, c.d, s.stop_atr, s.reward_risk, s.max_hold, cfg.optimizer.fee_pct);
  const y = Array.from(outcomes, (r) => (ok(r) ? (r > 0 ? 1 : 0) : NaN));
  const res = walkForward(X, y, { nSplits: mc.n_splits, minTrain: mc.min_train, purge: s.max_hold + 1 });
  if (!res) return fail('not enough labelled history to train a reliable model');
  const wins = [], losses = [];
  for (const r of outcomes) if (ok(r)) (r > 0 ? wins : losses).push(r > 0 ? r : -r);
  if (!wins.length || !losses.length) return fail('trade outcomes are one-sided - cannot estimate an edge');
  const avgWin = mean(wins), avgLoss = mean(losses), breakeven = avgLoss / (avgWin + avgLoss);
  const { prob: p, auc, base_rate: base } = res, ev = p * avgWin - (1 - p) * avgLoss;
  const ranked = res.oos.p.map((x, k) => [x, outcomes[res.oos.rows[k]]]).sort((a, b) => b[0] - a[0]);
  const topR = mean(ranked.slice(0, Math.max(1, Math.floor(ranked.length / 5))).map((x) => x[1])), allR = mean(ranked.map((x) => x[1]));
  const data = { prob: p, auc, base_rate: base, samples: res.samples, oos_samples: res.oos_samples, top_features: res.top_features, breakeven, expected_r: ev, avg_win_r: avgWin, avg_loss_r: avgLoss, oos_top_quintile_r: topR, oos_all_r: allR };
  if (!(auc >= mc.min_auc)) return fail(`model shows no out-of-sample skill (AUC ${auc.toFixed(2)} < ${mc.min_auc})`, data);
  if (p < breakeven + mc.min_edge) return fail(`P(profit) ${(p * 100).toFixed(0)}% too close to break-even ${(breakeven * 100).toFixed(0)}%`, data);
  if (p < base) return fail(`model rates today below an average bar (${(p * 100).toFixed(0)}% < base rate ${(base * 100).toFixed(0)}%)`, data);
  return pass(clip(0.6 * clip(0.5 + ev / 2) + 0.4 * clip((auc - 0.5) / 0.1)), [
    `P(profit) ${(p * 100).toFixed(0)}% vs break-even ${(breakeven * 100).toFixed(0)}%, base rate ${(base * 100).toFixed(0)}%`,
    `walk-forward AUC ${auc.toFixed(2)} on ${res.oos_samples} unseen bars; top-20% picks averaged ${topR >= 0 ? '+' : ''}${topR.toFixed(2)}R vs ${allR >= 0 ? '+' : ''}${allR.toFixed(2)}R`,
    `model expected value ${ev >= 0 ? '+' : ''}${ev.toFixed(2)}R (avg win ${avgWin.toFixed(2)}R, avg loss ${avgLoss.toFixed(2)}R)`,
    `top drivers: ${res.top_features.slice(0, 3).join(', ')}`,
  ], data);
}

// ---- L10 risk ----------------------------------------------------------------------------------
const roundPrice = (x) => (x >= 1 ? Math.round(x * 100) / 100 : Math.round(x * 1e6) / 1e6);
function positionRisk(p, equity, rc) {
  if (p.entry && p.stop && p.qty) return Math.max(0, p.side === 'short' ? (p.stop - p.entry) * p.qty : (p.entry - p.stop) * p.qty);
  return equity * rc.risk_per_trade;
}
function riskAgent(c, cfg, ctx) {
  const rc = cfg.risk, { equity, cash, positions, held } = ctx.account;
  if (!(equity > 0)) return fail('account equity is unknown or zero');
  if (positions.some((p) => p.symbol === c.symbol)) return fail('a position or order is already open');
  if (held.has(c.symbol)) return fail('already held in the account outside the desk');
  if (positions.length >= rc.max_open_positions) return fail(`${positions.length} positions open (max ${rc.max_open_positions})`);
  const s = c.cache.setup, d = c.d, n = c.ind.close.length, price = c.ind.close[n - 1], atr = c.ind.atr[n - 1];
  const dist = s.stop_atr * atr, rr = s.reward_risk;
  if (rr < rc.min_reward_risk) return fail(`reward:risk ${rr} below the ${rc.min_reward_risk} minimum`);
  const stop = price - d * dist, target = price + d * rr * dist;
  if (stop <= 0) return fail('stop would be at or below zero');
  const openRisk = positions.reduce((t, p) => t + positionRisk(p, equity, rc), 0);
  const heatLeft = equity * rc.max_portfolio_heat - openRisk;
  if (heatLeft <= 0) return fail('portfolio heat limit reached');
  const budget = Math.min(equity * rc.risk_per_trade * ctx.market.risk_multiplier, heatLeft);
  const caps = { 'risk budget': budget / dist, 'max position size': (equity * rc.max_position_pct) / price, 'max order value': cfg.paper.max_order_value / price };
  if (d > 0) caps['available cash'] = Math.max(cash, 0) / price;
  const limiter = Object.keys(caps).reduce((a, b) => (caps[b] < caps[a] ? b : a));
  let qty = caps[limiter];
  qty = isCrypto(c.symbol) ? Math.floor(qty * 1e6) / 1e6 : Math.floor(qty);
  if (qty <= 0) return fail(`position size rounds to zero (limited by ${limiter})`);
  const riskAmount = qty * dist;
  c.plan = { symbol: c.symbol, side: c.side, qty, entry_ref: roundPrice(price), stop: roundPrice(stop), target: roundPrice(target), atr, stop_atr: s.stop_atr,
    reward_risk: rr, max_hold: s.max_hold, setup: s.label, risk_amount: riskAmount, risk_pct: riskAmount / equity, notional: qty * price, limited_by: limiter,
    signal_date: new Date(last(c.bars.t)).toISOString().slice(0, 10), segment: cfg.style };
  const score = 0.5 * clip(rr / 3) + 0.5 * clip(1 - atr / price / 0.06);
  return pass(score, [`${c.side} ${qty} @ ~${price.toFixed(2)}, stop ${stop.toFixed(2)}, target ${target.toFixed(2)} (${rr}R)`,
    `risk ${money(riskAmount)} = ${(c.plan.risk_pct * 100).toFixed(2)}% of equity (limited by ${limiter}, regime risk x${ctx.market.risk_multiplier.toFixed(2)})`,
    `position value ${money(qty * price)} (${((qty * price * 100) / equity).toFixed(0)}% of equity)`], { ...c.plan });
}

// ---- L11 decision ------------------------------------------------------------------------------
function decisionAgent(c, cfg) {
  const dc = cfg.decision;
  let num = 0, den = 0;
  const contributions = {};
  for (const [agent, w] of Object.entries(dc.weights)) {
    const r = c.reports[agent];
    if (!r || r.score === null || r.score === undefined || w <= 0) continue;
    num += w * r.score; den += w; contributions[agent] = r.score;
  }
  const conviction = den ? num / den : 0;
  const oosEv = c.cache.setup.out_of_sample.expectancy, mlEv = c.reports.ml?.data?.expected_r;
  const ev = ok(mlEv) ? 0.5 * oosEv + 0.5 * mlEv : oosEv;
  Object.assign(c.cache, { conviction, expected_r: ev });
  const data = { conviction, expected_r: ev, rank_score: conviction * ev, contributions };
  if (conviction < dc.min_conviction) return fail(`conviction ${conviction.toFixed(2)} below ${dc.min_conviction}`, data, conviction);
  if (ev <= 0) return fail(`expected value ${ev.toFixed(2)}R is not positive`, data, conviction);
  return pass(conviction, [`conviction ${conviction.toFixed(2)} across ${Object.keys(contributions).length} agents`, `expected value ${ev >= 0 ? '+' : ''}${ev.toFixed(2)}R per trade`], data);
}
function correlation(a, b) {
  if (!a || !b) return null;
  const mapB = new Map();
  b.t.forEach((t, i) => mapB.set(Math.floor(t / 86400000), b.c[i]));
  const xs = [], ys = [];
  let prevA = null, prevB = null;
  for (let i = Math.max(0, a.t.length - 121); i < a.t.length; i++) {
    const vb = mapB.get(Math.floor(a.t[i] / 86400000));
    if (vb === undefined) continue;
    if (prevA !== null) { xs.push(a.c[i] / prevA - 1); ys.push(vb / prevB - 1); }
    prevA = a.c[i]; prevB = vb;
  }
  if (xs.length < 30) return null;
  const mx = mean(xs), my = mean(ys);
  let sxy = 0, sxx = 0, syy = 0;
  xs.forEach((x, i) => { sxy += (x - mx) * (ys[i] - my); sxx += (x - mx) ** 2; syy += (ys[i] - my) ** 2; });
  return sxx && syy ? sxy / Math.sqrt(sxx * syy) : null;
}
function finalizeDecision(alive, cfg, ctx, reject, frames) {
  const dc = cfg.decision, rc = cfg.risk, { equity, positions } = ctx.account;
  const ranked = [...alive].sort((a, b) => b.cache.conviction * b.cache.expected_r - a.cache.conviction * a.cache.expected_r);
  let heat = positions.reduce((t, p) => t + positionRisk(p, equity, rc), 0);
  const heatLimit = equity * rc.max_portfolio_heat;
  let cashLeft = ctx.account.cash - positions.filter((p) => p.status === 'pending' && p.side === 'long').reduce((t, p) => t + p.qty * (p.entry_ref || 0), 0);
  const slots = Math.min(dc.top_n, rc.max_open_positions - positions.length), chosen = [];
  for (const c of ranked) {
    if (chosen.length >= slots) { reject(c, 'decision', slots <= 0 ? 'no free position slots' : `ranked below the top ${slots}`); continue; }
    let clash = null;
    for (const other of [...chosen.map((x) => x.symbol), ...positions.map((p) => p.symbol)]) {
      const rho = correlation(c.bars, frames[other]);
      if (rho !== null && rho > rc.max_correlation) { clash = [other, rho]; break; }
    }
    if (clash) { reject(c, 'decision', `${clash[1].toFixed(2)} correlated with ${clash[0]} (max ${rc.max_correlation})`); continue; }
    if (heat + c.plan.risk_amount > heatLimit + 1e-9) { reject(c, 'decision', 'would push total open risk over the portfolio heat limit'); continue; }
    if (c.side === 'long' && c.plan.notional > cashLeft + 1e-9) { reject(c, 'decision', 'not enough free cash after the higher-ranked picks'); continue; }
    heat += c.plan.risk_amount;
    if (c.side === 'long') cashLeft -= c.plan.notional;
    chosen.push(c);
  }
  chosen.forEach((c, i) => { c.cache.rank = i + 1; c.reports.decision.data.rank = i + 1; });
  return chosen;
}

const AGENTS = { liquidity, regime: regimeAgent, technical, timeframe, price_action: priceAction, optimizer: optimizerAgent, ml: mlAgent,
  sentiment: () => skipped('news sentiment runs on the desk server'), fundamental: () => skipped('fundamentals run on the desk server'),
  risk: riskAgent, decision: decisionAgent, ai_review: () => skipped('Claude review runs on the desk server') };

/**
 * Run the whole funnel.
 * frames: {symbol: bars}; account: {equity, cash, positions, held:Set}; emit(event) reports progress.
 */
export async function runScan({ cfg, symbols, frames, benchmark, volIndex, account, emit = () => {}, tick = async () => {}, now = Date.now() }) {
  const benchBars = frames[benchmark] || null;
  const benchClose = benchBars ? benchBars.c : null;
  const ctx = { now, account, minTurnover: cfg.liquidity.min_turnover, market: null };
  const candidates = symbols.map((symbol) => ({ symbol, bars: frames[symbol] || null, reports: {}, cache: {}, side: null, d: 1, plan: null, rejected_by: null, rejection: '',
    get ind() { if (!this._ind) this._ind = computeAll(this.bars, benchClose ? alignTo(this.bars, benchBars) : null); return this._ind; } }));
  const alive = () => candidates.filter((c) => !c.rejected_by);
  const reject = (c, agent, reason) => { c.rejected_by = agent; c.rejection = reason; emit({ topic: 'reject', symbol: c.symbol, agent, reason }); };
  const funnel = [];
  emit({ topic: 'start', symbols: symbols.length });
  for (const L of LAYERS) {
    const entered = alive().length;
    if (entered && L.name === 'regime') {
      ctx.market = analyseMarket(benchBars, frames[volIndex] || null, alive().map((c) => c.bars), cfg.regime);
      emit({ topic: 'regime', ...ctx.market });
    }
    emit({ topic: 'layer_start', ...L, entered });
    for (const c of alive()) {
      let rep;
      try {
        rep = AGENTS[L.name](c, cfg, ctx);
      } catch (err) {
        rep = ['sentiment', 'fundamental', 'ai_review'].includes(L.name) ? skipped(`skipped after an error: ${err.message}`) : fail(`${L.title} error: ${err.message}`);
      }
      c.reports[L.name] = { agent: L.name, ...rep };
      if (!rep.passed) reject(c, L.name, rep.veto || 'did not pass');
      await tick();
    }
    if (L.name === 'decision' && alive().length) finalizeDecision(alive(), cfg, ctx, reject, frames);
    const rejected = candidates.filter((c) => c.rejected_by === L.name).map((c) => [c.symbol, c.rejection]);
    funnel.push({ layer: L.layer, agent: L.name, title: L.title, entered, passed: alive().length, rejected });
    emit({ topic: 'layer_done', layer: L.layer, title: L.title, entered, passed: alive().length });
  }
  const strip = (c) => ({ symbol: c.symbol, side: c.side, reports: c.reports, rejected_by: c.rejected_by, rejection: c.rejection, plan: c.plan,
    rank: c.cache.rank || null, conviction: c.cache.conviction ?? null, expected_r: c.cache.expected_r ?? null, setup: c.cache.setup || null,
    closes: c.bars ? Array.from(c.bars.c.slice(-120)) : [] });
  const trades = alive().sort((a, b) => a.cache.rank - b.cache.rank).map(strip);
  const layerOf = Object.fromEntries(LAYERS.map((L) => [L.name, L.layer]));
  const misses = candidates.filter((c) => c.rejected_by).sort((a, b) => layerOf[b.rejected_by] - layerOf[a.rejected_by]).slice(0, 8)
    .map((c) => ({ symbol: c.symbol, agent: c.rejected_by, layer: layerOf[c.rejected_by], reason: c.rejection }));
  const result = { generated: new Date(now).toISOString(), market: ctx.market, funnel, trades, misses, universe: symbols.length };
  emit({ topic: 'done', trades: trades.map((t) => t.symbol) });
  return result;
}
