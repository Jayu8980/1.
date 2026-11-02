"""Layer 1 - Liquidity & data-quality screener."""
from __future__ import annotations

import math

import pandas as pd

from ..markets import is_index
from .base import Agent, clip


def money(x: float) -> str:
    for unit, div in (("B", 1e9), ("M", 1e6), ("K", 1e3)):
        if abs(x) >= div:
            return f"{x / div:.1f}{unit}"
    return f"{x:.0f}"


class LiquidityAgent(Agent):
    name = "liquidity"
    title = "Liquidity & Data-Quality Screener"
    layer = 1
    role = ("Drops symbols with missing, stale or corrupt data, too little history, a very low "
            "price or thin trading.")

    def evaluate(self, cand):
        lc = self.cfg.liquidity
        df = cand.df
        if df is None or df.empty:
            return self.fail("no market data returned")
        if len(df) < lc.min_bars:
            return self.fail(f"only {len(df)} bars of history (needs {lc.min_bars})")
        last = df.index[-1]
        age = (pd.Timestamp.today().normalize() - last.normalize()).days
        if age > lc.max_stale_days:
            return self.fail(f"stale data: last bar {last.date()} is {age} days old")
        price = float(df["Close"].iloc[-1])
        if price < lc.min_price:
            return self.fail(f"price {price:.2f} below the {lc.min_price} minimum")

        no_volume = cand.symbol.endswith("=X") or is_index(cand.symbol)  # currencies and indices
        turnover = float((df["Close"] * df["Volume"]).tail(20).mean())
        if not no_volume:
            if (df["Volume"].tail(60) <= 0).mean() > 0.2:
                return self.fail("volume missing on more than 20% of recent bars")
            if lc.min_turnover and turnover < lc.min_turnover:
                return self.fail(f"average turnover {money(turnover)} below {money(lc.min_turnover)}")
        worst = float(df["Close"].pct_change().tail(252).abs().max())
        if worst > lc.max_daily_move:
            return self.fail(f"suspicious {worst:.0%} one-bar move in the last year "
                             "(bad data or a corporate action)")

        depth = 0.5
        if lc.min_turnover and not no_volume:
            depth = clip(math.log10(max(turnover, 1.0) / lc.min_turnover) / 2)
        return self.ok(0.5 + 0.5 * depth,
                       [f"{len(df)} bars, price {price:.2f}, avg turnover {money(turnover)}"],
                       bars=len(df), price=price, turnover=turnover)
