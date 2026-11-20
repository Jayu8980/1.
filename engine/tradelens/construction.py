"""Signal combination (meta-model) and portfolio construction.

1. Meta-model: each strategy's signals are weighted by its recent out-of-sample Sharpe (floored at 0) and a
   regime multiplier; conflicting signals on one symbol net out.
2. Sizing: risk ≤ riskPerTrade% of capital to the stop, capped fractional Kelly (≤ 0.25 Kelly), then the whole
   book is scaled to the volatility target (~12–15% annualised).
3. Limits: ≤ 10% per stock, ≤ 25% per sector, pairwise correlation cap, F&O margin utilisation ≤ 50%.
4. Hedge: in high-volatility / crash regimes add a short index-future hedge sized to cut net beta.
"""
from __future__ import annotations

import math
from collections import defaultdict
from dataclasses import dataclass

import numpy as np
import pandas as pd

from .core.types import Signal
from .config import RiskLimits

FO_MARGIN = {"FUTURES": 0.15, "OPTIONS": 0.20, "COMMODITY_FUT": 0.10, "CRYPTO_FUT": 0.10, "CRYPTO_OPT": 0.10}


@dataclass
class StrategyStats:
    oos_sharpe: float = 0.5
    win_rate: float = 0.5
    payoff: float = 1.5
    enabled: bool = True


def meta_weights(stats: dict[str, StrategyStats], regime: str, regime_tilts: dict[str, dict[str, float]] | None = None) -> dict[str, float]:
    raw = {}
    for name, s in stats.items():
        if not s.enabled:
            continue
        tilt = (regime_tilts or {}).get(name, {}).get(regime, 1.0)
        raw[name] = max(s.oos_sharpe, 0.0) * tilt
    tot = sum(raw.values())
    if tot <= 0:
        return {k: 1 / len(raw) for k in raw} if raw else {}
    return {k: v / tot for k, v in raw.items()}


def kelly_cap(win_rate: float, payoff: float, fraction: float = 0.25) -> float:
    if payoff <= 0:
        return 0.0
    return max(0.0, (win_rate - (1 - win_rate) / payoff) * min(fraction, 0.25))


def target_positions(
    signals: list[Signal],
    prices: dict[str, float],
    vols: dict[str, float],                 # annualised vol per symbol
    equity: float,
    weights: dict[str, float],
    stats: dict[str, StrategyStats],
    limits: RiskLimits,
    target_vol: float = 0.13,
    sectors: dict[str, str] | None = None,
    returns: pd.DataFrame | None = None,    # daily returns for correlation cap
    corr_cap: float = 0.85,
    multipliers: dict[str, float] | None = None,
) -> dict[tuple[str, str], float]:
    """Returns target quantity per (strategy, symbol)."""
    sectors = sectors or {}
    mult = multipliers or {}
    tgt_val: dict[tuple[str, str], float] = {}
    for s in signals:
        p = prices.get(s.symbol)
        if not p or p <= 0 or math.isnan(p):
            continue
        if s.direction == 0:
            tgt_val[(s.strategy, s.symbol)] = 0.0
            continue
        w = weights.get(s.strategy, 0.0)
        st = stats.get(s.strategy, StrategyStats())
        # risk-based size (to the stop) …
        risk_budget = equity * limits.riskPerTradePct / 100 * w * len(weights or {s.strategy: 1})
        stop_dist = abs(p - s.stop) / p if s.stop else max(vols.get(s.symbol, 0.3) / math.sqrt(252) * 2, 0.01)
        val_risk = risk_budget / stop_dist
        # … capped by fractional Kelly and the per-stock limit
        val_kelly = equity * max(kelly_cap(st.win_rate, st.payoff), 0.02) * 4 * w
        val = min(val_risk, val_kelly, equity * limits.maxPositionPct / 100) * s.strength
        tgt_val[(s.strategy, s.symbol)] = val * int(s.direction)

    # volatility targeting of the whole book
    by_symbol: dict[str, float] = defaultdict(float)
    for (_, sym), v in tgt_val.items():
        by_symbol[sym] += v
    if by_symbol:
        w_vec = np.array([v / equity for v in by_symbol.values()])
        syms = list(by_symbol)
        if returns is not None and set(syms) <= set(returns.columns) and len(returns.dropna(how="all")) >= 60:
            cov = returns[syms].tail(126).cov().values * 252
        else:
            cov = np.diag([vols.get(s, 0.3) ** 2 for s in syms])
        port_vol = float(np.sqrt(max(w_vec @ cov @ w_vec, 1e-12)))
        scale = min(target_vol / port_vol, 1.5) if port_vol > 0 else 1.0
        tgt_val = {k: v * scale for k, v in tgt_val.items()}
        by_symbol = {k: v * scale for k, v in by_symbol.items()}

    # per-stock cap (after scaling)
    cap = equity * limits.maxPositionPct / 100
    for sym, v in list(by_symbol.items()):
        if abs(v) > cap:
            f = cap / abs(v)
            for k in tgt_val:
                if k[1] == sym:
                    tgt_val[k] *= f
            by_symbol[sym] = math.copysign(cap, v)

    # sector cap
    sec_tot: dict[str, float] = defaultdict(float)
    for sym, v in by_symbol.items():
        sec_tot[sectors.get(sym, "Other")] += abs(v)
    scap = equity * limits.maxSectorPct / 100
    for sec, tot in sec_tot.items():
        if tot > scap and sec != "Other":
            f = scap / tot
            for k in tgt_val:
                if sectors.get(k[1], "Other") == sec:
                    tgt_val[k] *= f

    # correlation cap: of any pair with corr > cap, halve the smaller position
    if returns is not None and len(by_symbol) > 1:
        syms = [s for s in by_symbol if s in returns.columns]
        hist = returns[syms].tail(126).dropna(how="all") if syms else None
        if len(syms) > 1 and hist is not None and len(hist) >= 60:   # need a meaningful sample
            c = hist.corr()
            halved: set[str] = set()
            for i, a in enumerate(syms):
                for b in syms[i + 1:]:
                    if c.loc[a, b] > corr_cap and np.sign(by_symbol[a]) == np.sign(by_symbol[b]):
                        small = a if abs(by_symbol[a]) < abs(by_symbol[b]) else b
                        if small in halved:
                            continue
                        halved.add(small)
                        for k in tgt_val:
                            if k[1] == small:
                                tgt_val[k] *= 0.5

    # F&O margin utilisation
    seg_of = {s.symbol: s.segment for s in signals}
    margin = sum(abs(v) * FO_MARGIN.get(seg_of.get(k[1], ""), 0.0) for k, v in tgt_val.items())
    mcap = equity * limits.maxMarginUtilPct / 100
    if margin > mcap > 0:
        f = mcap / margin
        tgt_val = {k: v * f if FO_MARGIN.get(seg_of.get(k[1], ""), 0) else v for k, v in tgt_val.items()}

    return {k: v / (prices[k[1]] * mult.get(k[1], 1.0)) for k, v in tgt_val.items() if prices.get(k[1])}


def hedge_qty(regime: str, net_beta_exposure: float, index_price: float, lot: int = 75) -> int:
    """Short NIFTY futures lots to neutralise 50% (high-vol) or 100% (crash) of net long beta exposure."""
    frac = {"high_volatility": 0.5, "crash": 1.0}.get(regime, 0.0)
    if frac == 0 or net_beta_exposure <= 0 or index_price <= 0:
        return 0
    return -int(net_beta_exposure * frac / (index_price * lot))
