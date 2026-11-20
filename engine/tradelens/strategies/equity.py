from __future__ import annotations

import numpy as np
import pandas as pd

from ..core.strategy import Context, Strategy, adx, atr, rsi, supertrend
from ..core.types import Direction, Signal

INDEX = "NIFTY"


class CrossSectionalMomentum(Strategy):
    """Rank the universe by risk-adjusted 3/6/12-month returns, hold the top decile, rebalance monthly,
    stay flat when NIFTY is below its 200-DMA."""
    name = "xs_momentum"
    description = "Cross-sectional momentum, top decile of NIFTY 200, monthly, NIFTY trend filter"
    active_regimes = {"trending_up", "range", "high_volatility"}
    regime_scale = {"high_volatility": 0.5}
    params = {"top_frac": 0.1, "skip_days": 21}
    param_grid = {"top_frac": [0.05, 0.1, 0.2], "skip_days": [0, 21]}
    min_history = 260

    def __init__(self, **p):
        super().__init__(**p)
        self._month = None
        self._holdings: set[str] = set()

    def on_bar(self, ctx: Context) -> list[Signal]:
        month = (ctx.now.year, ctx.now.month)
        if month == self._month:
            return []
        self._month = month
        idx = ctx.history(INDEX, 210)
        trend_ok = len(idx) < 200 or idx["close"].iloc[-1] > idx["close"].rolling(200).mean().iloc[-1]
        scores = {}
        for s in ctx.symbols():
            if s == INDEX:
                continue
            h = ctx.history(s, 260)["close"]
            if len(h) < 253:
                continue
            h = h.iloc[: len(h) - self.params["skip_days"]] if self.params["skip_days"] else h
            vol = h.pct_change().std() * np.sqrt(252)
            if vol <= 0 or np.isnan(vol):
                continue
            r = [h.iloc[-1] / h.iloc[-k] - 1 for k in (63, 126, min(252, len(h) - 1))]
            scores[s] = float(np.mean(r) / vol)
        if not scores:
            return []
        n = max(1, int(len(scores) * self.params["top_frac"]))
        top = set(sorted(scores, key=scores.get, reverse=True)[:n]) if trend_ok else set()
        sigs = [Signal(s, Direction.LONG, 1.0, min(0.5 + scores[s] / 10, 0.9), stop=ctx.close(s) * 0.8, horizon="position", reason=f"momentum score {scores[s]:.2f}") for s in top]
        sigs += [Signal(s, Direction.FLAT, 0.0, 1.0, reason="dropped from top decile" if trend_ok else "NIFTY below 200-DMA") for s in self._holdings - top]
        self._holdings = top
        return sigs


class TrendFollowing(Strategy):
    """Donchian-55 breakouts confirmed by Supertrend and ADX, with a 3×ATR trailing stop. Long/short on
    index futures; long-only for cash equities."""
    name = "trend_following"
    description = "Donchian/Supertrend breakouts on index futures & liquid stocks, ATR trailing stop"
    active_regimes = {"trending_up", "trending_down", "range", "high_volatility"}
    regime_scale = {"range": 0.5, "high_volatility": 0.6}
    params = {"entry": 55, "exit": 20, "atr_mult": 3.0, "adx_min": 20, "allow_short": False}
    param_grid = {"entry": [40, 55, 80], "exit": [10, 20, 30], "atr_mult": [2.5, 3.0, 4.0]}
    min_history = 100

    def __init__(self, **p):
        super().__init__(**p)
        self._stop: dict[str, float] = {}

    def on_bar(self, ctx: Context) -> list[Signal]:
        out = []
        P = self.params
        for s in ctx.symbols():
            h = ctx.history(s, 150)
            if len(h) < P["entry"] + 15:
                continue
            c = h["close"].iloc[-1]
            a = atr(h).iloc[-1]
            hi = h["high"].iloc[-P["entry"] - 1:-1].max(); lo = h["low"].iloc[-P["entry"] - 1:-1].min()
            xlo = h["low"].iloc[-P["exit"] - 1:-1].min(); xhi = h["high"].iloc[-P["exit"] - 1:-1].max()
            st = supertrend(h).iloc[-1]; ad = adx(h).iloc[-1]
            pos = ctx.positions.get(s, 0)
            if pos > 0:
                self._stop[s] = max(self._stop.get(s, -np.inf), c - P["atr_mult"] * a)
                if c < xlo or c < self._stop[s]:
                    out.append(Signal(s, Direction.FLAT, 0, 1, reason="trend exit")); self._stop.pop(s, None)
                else:
                    out.append(Signal(s, Direction.LONG, 0.8, 0.6, stop=self._stop[s], reason="trailing"))
            elif pos < 0:
                self._stop[s] = min(self._stop.get(s, np.inf), c + P["atr_mult"] * a)
                if c > xhi or c > self._stop[s]:
                    out.append(Signal(s, Direction.FLAT, 0, 1, reason="trend exit")); self._stop.pop(s, None)
                else:
                    out.append(Signal(s, Direction.SHORT, 0.8, 0.6, stop=self._stop[s], reason="trailing"))
            elif c > hi and st > 0 and ad > P["adx_min"]:
                self._stop[s] = c - P["atr_mult"] * a
                out.append(Signal(s, Direction.LONG, 0.8, 0.6, stop=self._stop[s], reason=f"{P['entry']}-bar breakout, ADX {ad:.0f}"))
            elif P["allow_short"] and c < lo and st < 0 and ad > P["adx_min"]:
                self._stop[s] = c + P["atr_mult"] * a
                out.append(Signal(s, Direction.SHORT, 0.8, 0.6, stop=self._stop[s], reason=f"{P['entry']}-bar breakdown", segment="FUTURES"))
        return out


class MeanReversionRSI2(Strategy):
    """Buy short-term pullbacks (RSI(2) < 10 or close below lower Bollinger) in large caps above their
    200-DMA; exit on RSI(2) > 70 / close above the 5-DMA / 7 bars."""
    name = "mean_reversion"
    description = "RSI(2)/Bollinger pullbacks in uptrending large caps, short holding period"
    active_regimes = {"trending_up", "range"}
    params = {"rsi_entry": 10, "rsi_exit": 70, "max_hold": 7}
    param_grid = {"rsi_entry": [5, 10, 15], "rsi_exit": [60, 70, 80], "max_hold": [5, 7, 10]}
    min_history = 210

    def __init__(self, **p):
        super().__init__(**p)
        self._held: dict[str, int] = {}

    def on_bar(self, ctx: Context) -> list[Signal]:
        out = []
        for s in ctx.symbols():
            if s == INDEX:
                continue
            h = ctx.history(s, 220)
            if len(h) < 205:
                continue
            c = h["close"]; r2 = rsi(c, 2).iloc[-1]
            sma200 = c.rolling(200).mean().iloc[-1]; sma5 = c.rolling(5).mean().iloc[-1]
            bb_lo = (c.rolling(20).mean() - 2 * c.rolling(20).std()).iloc[-1]
            a = atr(h).iloc[-1]
            if ctx.positions.get(s, 0) > 0:
                self._held[s] = self._held.get(s, 0) + 1
                if r2 > self.params["rsi_exit"] or c.iloc[-1] > sma5 or self._held[s] >= self.params["max_hold"]:
                    out.append(Signal(s, Direction.FLAT, 0, 1, reason="mean reverted")); self._held.pop(s, None)
            elif c.iloc[-1] > sma200 and (r2 < self.params["rsi_entry"] or c.iloc[-1] < bb_lo):
                self._held[s] = 0
                out.append(Signal(s, Direction.LONG, 0.7, 0.62, stop=c.iloc[-1] - 2.5 * a, horizon="swing", reason=f"RSI2 {r2:.0f} above 200-DMA"))
        return out


class EarningsDrift(Strategy):
    """Post-results gap continuation. With an earnings calendar (meta['earnings'][symbol] = dates) it trades
    only results days; otherwise it uses the proxy of a ≥4% gap on ≥2.5× volume that closes in the top quartile."""
    name = "earnings_drift"
    description = "Post-results gap continuation (PEAD), 10-day hold"
    active_regimes = {"trending_up", "range"}
    params = {"gap": 0.04, "vol_mult": 2.5, "hold": 10}
    param_grid = {"gap": [0.03, 0.04, 0.06], "hold": [5, 10, 20]}

    def __init__(self, **p):
        super().__init__(**p)
        self._age: dict[str, int] = {}

    def on_bar(self, ctx: Context) -> list[Signal]:
        out = []
        cal = ctx.meta.get("earnings", {})
        for s in ctx.symbols():
            if s == INDEX:
                continue
            h = ctx.history(s, 30)
            if len(h) < 25:
                continue
            if ctx.positions.get(s, 0) > 0:
                self._age[s] = self._age.get(s, 0) + 1
                if self._age[s] >= self.params["hold"]:
                    out.append(Signal(s, Direction.FLAT, 0, 1, reason="drift window over")); self._age.pop(s)
                continue
            b = h.iloc[-1]; prev = h["close"].iloc[-2]
            gap = b["open"] / prev - 1
            vol_ok = b["volume"] > self.params["vol_mult"] * h["volume"].iloc[-21:-1].mean()
            strong_close = (b["close"] - b["low"]) / max(b["high"] - b["low"], 1e-9) > 0.75
            is_event = (str(ctx.now.date()) in cal.get(s, [])) if cal else True
            if is_event and gap > self.params["gap"] and vol_ok and strong_close:
                self._age[s] = 0
                out.append(Signal(s, Direction.LONG, 0.6, 0.58, stop=b["low"], reason=f"results gap {gap:.1%} on {b['volume'] / h['volume'].iloc[-21:-1].mean():.1f}× volume"))
        return out
