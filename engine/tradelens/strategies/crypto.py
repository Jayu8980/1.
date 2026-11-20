"""Delta Exchange India strategies: funding-rate carry, futures basis and BTC/ETH trend following."""
from __future__ import annotations

import numpy as np

from ..core.strategy import Context, Strategy, atr, ema
from ..core.types import Direction, Signal


class FundingCarry(Strategy):
    """Collect perpetual funding: short the perp when annualised funding is very positive (longs pay), long
    when very negative, only when price trend does not fight the position. Funding series are supplied as
    `FUNDING:<SYMBOL>` columns (fraction per 8h)."""
    name = "funding_carry"
    description = "Perpetual funding-rate carry on BTC/ETH with trend guard"
    segment = "CRYPTO_FUT"
    params = {"ann_threshold": 0.15, "symbols": ("BTCUSD", "ETHUSD")}
    param_grid = {"ann_threshold": [0.10, 0.15, 0.25]}

    def on_bar(self, ctx: Context) -> list[Signal]:
        out = []
        for s in self.params["symbols"]:
            f = ctx.history(f"FUNDING:{s}", 9)
            h = ctx.history(s, 60)
            if len(f) < 3 or len(h) < 50:
                continue
            ann = float(f["close"].tail(9).mean()) * 3 * 365
            trend = ema(h["close"], 20).iloc[-1] / ema(h["close"], 50).iloc[-1] - 1
            pos = ctx.positions.get(s, 0)
            a = atr(h).iloc[-1]; c = h["close"].iloc[-1]
            if ann > self.params["ann_threshold"] and trend < 0.03:
                out.append(Signal(s, Direction.SHORT, min(ann / 0.5, 1), 0.6, stop=c + 3 * a, reason=f"funding {ann:.0%}/yr"))
            elif ann < -self.params["ann_threshold"] and trend > -0.03:
                out.append(Signal(s, Direction.LONG, min(-ann / 0.5, 1), 0.6, stop=c - 3 * a, reason=f"funding {ann:.0%}/yr"))
            elif pos and abs(ann) < self.params["ann_threshold"] / 3:
                out.append(Signal(s, Direction.FLAT, 0, 1, reason="funding normalised"))
        return out


class BasisTrade(Strategy):
    """Cash-and-carry: when a dated future trades at an annualised premium above threshold to its perp/index,
    short the future and long the perp (market-neutral) until convergence. Expects `<FUT>` and `<PERP>` bars and
    meta['basis_pairs'] = [(future_symbol, perp_symbol, expiry_ts)]."""
    name = "basis_trade"
    description = "Futures-vs-perp basis convergence (market neutral)"
    segment = "CRYPTO_FUT"
    params = {"ann_threshold": 0.12}

    def on_bar(self, ctx: Context) -> list[Signal]:
        out = []
        for fut, perp, expiry in ctx.meta.get("basis_pairs", []):
            if fut not in ctx.symbols() or perp not in ctx.symbols():
                continue
            days = max((expiry - ctx.now.timestamp()) / 86400, 1)
            basis = ctx.close(fut) / ctx.close(perp) - 1
            ann = basis * 365 / days
            if ann > self.params["ann_threshold"] and not ctx.positions.get(fut):
                out += [Signal(fut, Direction.SHORT, 0.6, 0.7, reason=f"basis {ann:.0%}/yr"), Signal(perp, Direction.LONG, 0.6, 0.7, reason="basis hedge")]
            elif ctx.positions.get(fut) and (ann < self.params["ann_threshold"] / 4 or days < 1):
                out += [Signal(fut, Direction.FLAT, 0, 1, reason="basis converged"), Signal(perp, Direction.FLAT, 0, 1, reason="basis converged")]
        return out


class CryptoTrend(Strategy):
    """BTC/ETH trend following: EMA(20/100) regime with Donchian-20 entries and a 3×ATR trailing stop."""
    name = "crypto_trend"
    description = "BTC/ETH EMA/Donchian trend following on Delta perps"
    segment = "CRYPTO_FUT"
    params = {"fast": 20, "slow": 100, "atr_mult": 3.0, "symbols": ("BTCUSD", "ETHUSD")}
    param_grid = {"fast": [10, 20, 30], "slow": [60, 100, 150], "atr_mult": [2.5, 3.0, 4.0]}

    def __init__(self, **p):
        super().__init__(**p)
        self._stop: dict[str, float] = {}

    def on_bar(self, ctx: Context) -> list[Signal]:
        out = []
        for s in self.params["symbols"]:
            h = ctx.history(s, self.params["slow"] + 30)
            if len(h) < self.params["slow"] + 5:
                continue
            c = h["close"].iloc[-1]; a = atr(h).iloc[-1]
            up = ema(h["close"], self.params["fast"]).iloc[-1] > ema(h["close"], self.params["slow"]).iloc[-1]
            pos = ctx.positions.get(s, 0)
            if pos > 0:
                self._stop[s] = max(self._stop.get(s, -np.inf), c - self.params["atr_mult"] * a)
                if c < self._stop[s] or not up:
                    out.append(Signal(s, Direction.FLAT, 0, 1, reason="trend exit"))
                else:
                    out.append(Signal(s, Direction.LONG, 0.8, 0.6, stop=self._stop[s]))
            elif pos < 0:
                self._stop[s] = min(self._stop.get(s, np.inf), c + self.params["atr_mult"] * a)
                if c > self._stop[s] or up:
                    out.append(Signal(s, Direction.FLAT, 0, 1, reason="trend exit"))
                else:
                    out.append(Signal(s, Direction.SHORT, 0.8, 0.6, stop=self._stop[s]))
            elif up and c >= h["high"].iloc[-21:-1].max():
                self._stop[s] = c - self.params["atr_mult"] * a
                out.append(Signal(s, Direction.LONG, 0.8, 0.6, stop=self._stop[s], reason="uptrend breakout"))
            elif not up and c <= h["low"].iloc[-21:-1].min():
                self._stop[s] = c + self.params["atr_mult"] * a
                out.append(Signal(s, Direction.SHORT, 0.8, 0.6, stop=self._stop[s], reason="downtrend breakdown"))
        return out
