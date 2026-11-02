"""Layer 2 - Market regime analyst."""
from __future__ import annotations

import numpy as np

from ..data import bars_per_year
from .base import Agent, clip

THRESHOLD_BOOST = {  # extra indicator confluence the technical analyst demands, per regime and side
    "bull": {"long": 0.0, "short": 0.15},
    "bear": {"long": 0.15, "short": 0.0},
    "neutral": {"long": 0.05, "short": 0.05},
    "crisis": {"long": 0.2, "short": 0.1},
}
RISK_BY_VOLATILITY = {"low": 1.0, "normal": 1.0, "high": 0.6, "crisis": 0.3}


def analyse_market(bench, vol_index, frames, rc) -> dict:
    """Classify the market from the benchmark's trend, a volatility gauge and universe breadth."""
    ctx = {"regime": "neutral", "trend_score": 0.0, "volatility": "normal", "vol_measure": None,
           "breadth": None, "benchmark_return_63d": None, "benchmark_drawdown": None, "notes": []}
    trend, vol_pct = 0.0, None
    if bench is not None and len(bench) >= 260:
        c = bench["Close"]
        sma50, sma200 = c.rolling(50).mean(), c.rolling(200).mean()
        r63 = c.iloc[-1] / c.iloc[-64] - 1
        votes = [np.sign(c.iloc[-1] - sma200.iloc[-1]), np.sign(sma50.iloc[-1] - sma200.iloc[-1]), np.sign(r63)]
        trend = float(np.mean(votes))
        ctx.update(trend_score=trend, benchmark_return_63d=float(r63),
                   benchmark_drawdown=float(c.iloc[-1] / c.tail(252).max() - 1))
        realized = (c.pct_change().rolling(21).std() * np.sqrt(bars_per_year(c.index))).dropna().tail(756)
        if len(realized) > 100:
            vol_pct = float((realized < realized.iloc[-1]).mean())
            ctx["realized_vol"] = float(realized.iloc[-1])
    else:
        ctx["notes"].append("benchmark data unavailable - assuming a neutral market")

    if vol_index is not None and len(vol_index):
        level = float(vol_index["Close"].iloc[-1])
        ctx["vol_measure"] = f"volatility index {level:.1f}"
        vol = ("crisis" if level >= rc.crisis_vol_index else "high" if level >= rc.high_vol_index
               else "low" if level < 15 else "normal")
    elif vol_pct is not None:
        ctx["vol_measure"] = f"realized vol {ctx['realized_vol']:.0%} ({vol_pct:.0%} percentile of 3y)"
        vol = "crisis" if vol_pct >= 0.97 else "high" if vol_pct >= 0.85 else "low" if vol_pct < 0.25 else "normal"
    else:
        vol = "normal"
    ctx["volatility"] = vol

    above = [float(df["Close"].iloc[-1] > df["Close"].tail(50).mean())
             for df in frames if df is not None and len(df) >= 50]
    breadth = float(np.mean(above)) if above else None
    ctx["breadth"] = breadth

    if vol == "crisis" and trend < 0:
        regime = "crisis"
    elif trend >= 0.33 and (breadth is None or breadth >= 0.4):
        regime = "bull"
    elif trend <= -0.33:
        regime = "bear"
    else:
        regime = "neutral"
    ctx["regime"] = regime
    ctx["risk_multiplier"] = RISK_BY_VOLATILITY[vol] * (0.75 if regime == "bear" else 1.0)
    ctx["threshold_boost"] = THRESHOLD_BOOST[regime]
    return ctx


class RegimeAgent(Agent):
    name = "regime"
    title = "Market Regime Analyst"
    layer = 2
    role = ("Reads the benchmark's trend, volatility and market breadth, broadcasts the regime to "
            "every other agent, and sets each symbol's trade direction from its primary trend.")

    def prepare(self):
        ctx = analyse_market(self.board.context.get("benchmark"), self.board.context.get("vol_index"),
                             [c.df for c in self.board.alive()], self.cfg.regime)
        self.board.context["regime"] = ctx
        self.bus.publish("market.regime", self.name, **ctx)

    def evaluate(self, cand):
        rc = self.cfg.regime
        regime = self.board.context["regime"]["regime"]
        if regime == "crisis" and rc.halt_on_crisis:
            return self.fail("market is in a crisis regime - new trades halted")
        ind = cand.ind
        close, sma50, sma200 = ind["close"].iloc[-1], ind["sma50"].iloc[-1], ind["sma200"].iloc[-1]
        slope = ind["sma200"].iloc[-1] / ind["sma200"].iloc[-21] - 1
        total = int(np.nansum([np.sign(close - sma200), np.sign(sma50 - sma200), np.sign(slope)]))
        rs63 = float(ind["rs63"].iloc[-1])
        rs63 = 0.0 if np.isnan(rs63) else rs63
        data = {"trend_votes": total, "rs63": rs63, "market": regime}
        if total == 0:
            return self.fail("no clear primary trend", **data)
        side = "long" if total > 0 else "short"
        if side == "short" and not rc.allow_short:
            return self.fail(f"primary trend is down ({-total}/3 bearish) and short selling is disabled", **data)
        if side == "long" and regime == "bear" and rc.bear_longs_need_rs and rs63 <= 0:
            return self.fail(f"bear market and lagging the benchmark (63-day relative strength {rs63:+.1%})", **data)
        if side == "short" and regime == "bull" and rs63 >= 0:
            return self.fail(f"bull market and beating the benchmark ({rs63:+.1%}) - no short", **data)
        cand.side = side
        alignment = {"bull": 1, "bear": -1}.get(regime, 0) * cand.direction
        score = clip(0.5 + 0.25 * abs(total) / 3 + 0.25 * alignment)
        return self.ok(score, [f"primary trend {side} ({abs(total)}/3 signals)", f"market {regime}",
                               f"63-day relative strength {rs63:+.1%}"], **data)
