"""Layer 3 - Technical indicator analyst."""
from __future__ import annotations

import numpy as np
import pandas as pd

from .base import Agent, clip

# group -> (weight, indicator votes in the group)
GROUPS = {
    "trend": (0.35, ["ma_stack", "ema_cross", "adx_dmi", "supertrend", "lr_slope"]),
    "momentum": (0.30, ["rsi", "macd", "stochastic", "roc", "williams_r", "cci"]),
    "volume": (0.15, ["obv", "mfi", "cmf", "volume_thrust"]),
    "position": (0.10, ["bollinger", "donchian"]),
    "relative": (0.10, ["rel_strength"]),
}


def indicator_votes(ind: pd.DataFrame) -> pd.DataFrame:
    """One vote per indicator per bar, from -1 (bearish) to +1 (bullish)."""
    a = ind["atr"].replace(0, np.nan)
    c = ind["close"]
    v = pd.DataFrame(index=ind.index)
    v["ma_stack"] = (np.sign(c - ind["sma20"]) + np.sign(ind["sma20"] - ind["sma50"])
                     + np.sign(ind["sma50"] - ind["sma200"])) / 3
    v["ema_cross"] = np.tanh((ind["ema9"] - ind["ema21"]) / a)
    v["adx_dmi"] = np.sign(ind["plus_di"] - ind["minus_di"]) * ((ind["adx"] - 15) / 20).clip(0, 1)
    v["supertrend"] = ind["st_dir"]
    v["lr_slope"] = np.tanh(ind["lr_slope50"] * 252 / 0.4)
    v["rsi"] = ((ind["rsi14"] - 50) / 20).clip(-1, 1)
    v["macd"] = np.tanh(ind["macd_hist"] / (0.2 * a))
    v["stochastic"] = (0.5 * ((ind["stoch_k"] - 50) / 50).clip(-1, 1)
                       + 0.5 * np.sign(ind["stoch_k"] - ind["stoch_d"]))
    v["roc"] = np.tanh(ind["ret10"] / (ind["vol21"] * np.sqrt(10)).replace(0, np.nan))
    v["williams_r"] = ((ind["willr"] + 50) / 50).clip(-1, 1)
    v["cci"] = np.tanh(ind["cci"] / 100)
    v["obv"] = np.sign(ind["obv"] - ind["obv_ema20"])
    v["mfi"] = ((ind["mfi"] - 50) / 30).clip(-1, 1)
    v["cmf"] = (ind["cmf"] / 0.2).clip(-1, 1)
    thrust = np.sign(ind["ret1"]) * (ind["vol_ratio"] - 1).clip(0, 1)
    v["volume_thrust"] = thrust.where(ind["vol_ratio"] > 1.2, 0.0)
    v["bollinger"] = ((ind["bb_pctb"] - 0.5) * 2).clip(-1, 1)
    width = (ind["dc_high20"] - ind["dc_low20"]).replace(0, np.nan)
    v["donchian"] = (2 * (c - ind["dc_low20"]) / width - 1).clip(-1, 1)
    v["rel_strength"] = np.tanh(ind["rs63"] / 0.1)
    return v


def composite(votes: pd.DataFrame) -> pd.Series:
    """Weighted confluence score per bar, -1..1."""
    total = pd.Series(0.0, index=votes.index)
    for weight, cols in GROUPS.values():
        total = total + weight * votes[cols].mean(axis=1, skipna=True).fillna(0.0)
    return total


class TechnicalAgent(Agent):
    name = "technical"
    title = "Technical Indicator Analyst"
    layer = 3
    role = ("Scores 19 indicators across trend, momentum, volume, volatility bands and relative "
            "strength, and keeps symbols whose indicators agree with the trade direction.")

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.boost = {"long": 0.0, "short": 0.0}
        self.bus.subscribe("market.regime", self._on_regime)  # stricter in hostile regimes

    def _on_regime(self, msg):
        self.boost = msg.payload.get("threshold_boost", self.boost)

    def evaluate(self, cand):
        votes = indicator_votes(cand.ind)
        score_series = composite(votes)
        cand.cache["tech_score"] = score_series  # the optimizer's momentum setup reuses it
        d = cand.direction
        s = float(score_series.iloc[-1]) * d
        need = self.cfg.technical.min_score + self.boost.get(cand.side, 0.0)
        last = (votes.iloc[-1] * d).dropna()
        agree, disagree = int((last > 0.1).sum()), int((last < -0.1).sum())
        groups = {g: float(votes[cols].iloc[-1].mean() * d) for g, (_, cols) in GROUPS.items()}
        data = {"confluence": s, "threshold": need, "agree": agree, "disagree": disagree, "groups": groups}
        if s < need:
            return self.fail(f"indicator confluence {s:+.2f} below {need:+.2f} "
                             f"({agree} agree, {disagree} disagree)", **data)
        strongest = ", ".join(f"{k} {v:+.2f}" for k, v in last.sort_values(ascending=False).head(3).items())
        return self.ok(clip((s + 1) / 2), [f"confluence {s:+.2f} (needs {need:+.2f})",
                                          f"{agree}/{len(last)} indicators agree, {disagree} disagree",
                                          f"strongest: {strongest}"], **data)
