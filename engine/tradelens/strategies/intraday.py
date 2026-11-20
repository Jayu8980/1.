from __future__ import annotations

import pandas as pd

from ..core.strategy import Context, Strategy
from ..core.types import Direction, Signal


class OpeningRangeBreakout(Strategy):
    """Intraday NIFTY/BANKNIFTY: break of the first-15-minute range in the direction of VWAP, stop at the
    opposite side of the range, flat by 15:15 IST. Requires 5-minute bars; skips the first/last 5 minutes."""
    name = "orb_vwap"
    description = "Opening-range breakout with VWAP filter on index futures (intraday)"
    segment = "FUTURES"
    active_regimes = {"trending_up", "trending_down", "range", "high_volatility"}
    regime_scale = {"high_volatility": 0.5}
    params = {"range_minutes": 15, "target_r": 2.0}
    param_grid = {"range_minutes": [15, 30], "target_r": [1.5, 2.0, 3.0]}

    def on_bar(self, ctx: Context) -> list[Signal]:
        out = []
        now = pd.Timestamp(ctx.now).tz_convert("Asia/Kolkata")
        t = now.time()
        for s in ctx.symbols():
            h = ctx.history(s, 100)
            if len(h) < 10:
                continue
            ist = h.index.tz_convert("Asia/Kolkata")
            today = h[ist.date == now.date()]
            if len(today) < 2:
                continue
            if t >= pd.Timestamp("15:15").time():
                if ctx.positions.get(s, 0):
                    out.append(Signal(s, Direction.FLAT, 0, 1, horizon="intraday", reason="EOD square-off"))
                continue
            first = today[today.index.tz_convert("Asia/Kolkata").time < (pd.Timestamp("09:15") + pd.Timedelta(minutes=self.params["range_minutes"])).time()]
            if len(first) == 0 or len(today) <= len(first) or t < pd.Timestamp("09:35").time() or ctx.positions.get(s, 0):
                continue
            hi, lo = first["high"].max(), first["low"].min()
            tp = (today["high"] + today["low"] + today["close"]) / 3
            vwap = (tp * today["volume"]).cumsum().iloc[-1] / max(today["volume"].cumsum().iloc[-1], 1)
            c = today["close"].iloc[-1]
            rng = hi - lo
            if c > hi and c > vwap:
                out.append(Signal(s, Direction.LONG, 0.7, 0.55, stop=lo, target=c + self.params["target_r"] * (c - lo), horizon="intraday", reason="ORB long above VWAP"))
            elif c < lo and c < vwap:
                out.append(Signal(s, Direction.SHORT, 0.7, 0.55, stop=hi, target=c - self.params["target_r"] * (hi - c), horizon="intraday", reason="ORB short below VWAP"))
        return out
