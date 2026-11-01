// Default settings (mirrors trader/config.py). The Settings screen edits a copy saved on the phone.
export const DEFAULTS = {
  universe: { preset: 'nifty50', symbols: [], history_years: 6 },
  data: { source: 'yahoo' }, // 'yahoo' or 'synthetic' (offline demo data, never traded)
  liquidity: { min_bars: 500, min_price: 2, min_turnover: 0, max_stale_days: 7, max_daily_move: 0.6 },
  regime: { allow_short: false, halt_on_crisis: true, high_vol_index: 28, crisis_vol_index: 40, bear_longs_need_rs: true },
  technical: { min_score: 0.1 },
  timeframe: { min_agreement: 0.6 },
  price_action: { min_room_atr: 1.0, max_extension_atr: 3.0 },
  optimizer: {
    in_sample_frac: 0.7, stop_atr: [1.0, 1.5, 2.0, 2.5], reward_risk: [1.5, 2.0, 3.0], max_hold: [5, 10, 20],
    fee_pct: 0.0005, min_trades_is: 15, min_trades_oos: 5, min_expectancy_oos: 0.05, min_profit_factor_oos: 1.15,
    max_drawdown_r: 12, min_robustness: 0.5, signal_freshness: 1,
  },
  ml: { n_splits: 4, min_train: 400, min_auc: 0.52, min_edge: 0.02 },
  risk: {
    account_equity: 0, risk_per_trade: 0.01, max_position_pct: 0.25, max_open_positions: 5, max_portfolio_heat: 0.06,
    min_reward_risk: 1.5, max_correlation: 0.8,
  },
  decision: {
    top_n: 3, min_conviction: 0.55,
    weights: { regime: 0.05, technical: 0.15, timeframe: 0.1, price_action: 0.1, optimizer: 0.25, ml: 0.2, sentiment: 0.075, fundamental: 0.075 },
  },
  paper: { equity: 1000000, fee_pct: 0.0005, slippage_pct: 0.0005, max_order_value: 250000, trail_to_breakeven: true },
  style: 'delivery', // on the phone: 'delivery' (long only, held for days) or 'intraday' (long/short, same day)
};

export function withStyle(cfg) {
  const c = structuredClone(cfg);
  if (c.style === 'intraday') {
    c.regime.allow_short = true;
    c.optimizer.max_hold = [1];
  }
  return c;
}

export function merge(base, over) {
  const out = structuredClone(base);
  for (const [k, v] of Object.entries(over || {})) {
    out[k] = v && typeof v === 'object' && !Array.isArray(v) && typeof out[k] === 'object' ? merge(out[k], v) : v;
  }
  return out;
}
