"""Defined-risk options income: iron condors on NIFTY when IV rank is high and the regime is range-bound.
Never sells naked options — every short strike has a long wing.

For research, `synthetic_condor_series` prices a rolling monthly iron condor with Black-Scholes using India
VIX as the implied volatility and returns its mark-to-market value per lot (including 8 option legs of
charges per cycle). The strategy then trades that synthetic instrument like any other symbol; live execution
maps it to the four actual strikes.
"""
from __future__ import annotations

import math

import numpy as np
import pandas as pd
from scipy.stats import norm

from .. import charges
from ..core.strategy import Context, Strategy
from ..core.types import Direction, Signal

SYMBOL = "NIFTY_IC"


def bs(cp: str, s: float, k: float, t: float, v: float, r: float = 0.065) -> float:
    if t <= 0 or v <= 0:
        return max(0.0, s - k) if cp == "C" else max(0.0, k - s)
    d1 = (math.log(s / k) + (r + v * v / 2) * t) / (v * math.sqrt(t)); d2 = d1 - v * math.sqrt(t)
    return s * norm.cdf(d1) - k * math.exp(-r * t) * norm.cdf(d2) if cp == "C" else k * math.exp(-r * t) * norm.cdf(-d2) - s * norm.cdf(-d1)


def synthetic_condor_series(index_close: pd.Series, vix: pd.Series, lot: int = 75, short_sd: float = 1.0, wing: int = 300,
                            dte: int = 30, take_profit: float = 0.5, step: int = 50) -> pd.Series:
    """Daily value (₹ per lot, starting at margin ≈ wing×lot) of a rolling short iron condor."""
    vix = vix.reindex(index_close.index).ffill().bfill() / 100.0
    capital = wing * lot
    value = capital
    out = []
    pos = None
    for i, (t, s) in enumerate(index_close.items()):
        v = float(vix.loc[t])
        if pos is None:
            sd = s * v * math.sqrt(dte / 365) * short_sd
            kc = math.ceil((s + sd) / step) * step; kp = math.floor((s - sd) / step) * step
            legs = [("C", kc, -1), ("C", kc + wing, 1), ("P", kp, -1), ("P", kp - wing, 1)]
            credit = -sum(q * bs(cp, s, k, dte / 365, v) for cp, k, q in legs)
            ch = sum(charges.leg("OPTIONS", "SELL" if q < 0 else "BUY", bs(cp, s, k, dte / 365, v), lot).total for cp, k, q in legs)
            pos = {"legs": legs, "credit": credit, "opened": i, "base": value - ch}
            value -= ch
        days_left = dte - (i - pos["opened"])
        mtm = -sum(q * bs(cp, s, k, max(days_left, 0) / 365, v) for cp, k, q in pos["legs"])  # cost to close (negative)
        pnl = (pos["credit"] - mtm) * lot
        cur = pos["base"] + pnl
        if days_left <= 0 or pnl >= take_profit * pos["credit"] * lot:
            ch = sum(charges.leg("OPTIONS", "BUY" if q < 0 else "SELL", max(bs(cp, s, k, max(days_left, 0) / 365, v), 0.05), lot).total for cp, k, q in pos["legs"])
            value = cur - ch
            pos = None
            out.append((t, value))
        else:
            out.append((t, cur))
    return pd.Series(dict(out)).astype(float) / capital * 100_000  # normalised "price"


class IronCondorIncome(Strategy):
    name = "options_income"
    description = "Iron condors (defined risk) on NIFTY when IV rank is high and the regime is range-bound"
    segment = "OPTIONS"
    active_regimes = {"range"}
    params = {"ivr_min": 50}
    param_grid = {"ivr_min": [30, 50, 70]}

    def on_bar(self, ctx: Context) -> list[Signal]:
        vix = ctx.history("INDIAVIX", 260)
        if SYMBOL not in ctx.symbols() or len(vix) < 100:
            return []
        lo, hi = vix["close"].min(), vix["close"].max()
        ivr = (vix["close"].iloc[-1] - lo) / (hi - lo) * 100 if hi > lo else 50
        if ivr >= self.params["ivr_min"]:
            return [Signal(SYMBOL, Direction.LONG, 0.5, 0.6, horizon="position", reason=f"IV rank {ivr:.0f}, range regime")]
        return [Signal(SYMBOL, Direction.FLAT, 0, 1, reason=f"IV rank {ivr:.0f} too low")] if ctx.positions.get(SYMBOL) else []
