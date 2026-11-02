"""Layer 5 - Price action & levels analyst."""
from __future__ import annotations

import math

import numpy as np
import pandas as pd

from .base import Agent, clip


def swing_levels(high: np.ndarray, low: np.ndarray, k: int = 8) -> tuple[list[float], list[float]]:
    """Confirmed swing highs and lows: bars that are the extreme of the k bars on either side.

    k=8 keeps levels that held for about three weeks around the pivot, so minor wiggles inside a
    trend are not mistaken for resistance or support."""
    highs, lows = [], []
    for i in range(k, len(high) - k):
        if high[i] == high[i - k:i + k + 1].max():
            highs.append(float(high[i]))
        if low[i] == low[i - k:i + k + 1].min():
            lows.append(float(low[i]))
    return highs, lows


def candle_patterns(df: pd.DataFrame) -> dict[str, float]:
    """Patterns on the last bar, with a bullish (+) or bearish (-) weight."""
    (o1, h1, l1, c1), (o, h, l, c) = df[["Open", "High", "Low", "Close"]].iloc[-2:].to_numpy()
    rng = h - l
    if rng <= 0:
        return {}
    body = abs(c - o)
    upper, lower = h - max(o, c), min(o, c) - l
    pats = {}
    if c1 < o1 and c > o and c >= o1 and o <= c1:
        pats["bullish engulfing"] = 1.0
    if c1 > o1 and c < o and c <= o1 and o >= c1:
        pats["bearish engulfing"] = -1.0
    if lower >= 2 * body and upper <= body:
        pats["hammer"] = 0.7
    if upper >= 2 * body and lower <= body:
        pats["shooting star"] = -0.7
    if h < h1 and l > l1:
        pats["inside bar"] = 0.0
    if body / rng < 0.1:
        pats["doji"] = 0.0
    if c > o and (c - l) / rng > 0.8:
        pats["strong close"] = 0.5
    if c < o and (h - c) / rng > 0.8:
        pats["weak close"] = -0.5
    return pats


class PriceActionAgent(Agent):
    name = "price_action"
    title = "Price Action & Levels Analyst"
    layer = 5
    role = ("Maps support and resistance from swing points, requires room to the next level, "
            "rejects over-extended (chasing) entries and reads candlestick patterns.")

    def evaluate(self, cand):
        pc = self.cfg.price_action
        ind, df, d = cand.ind, cand.df, cand.direction
        close, atr = float(ind["close"].iloc[-1]), float(ind["atr"].iloc[-1])
        if not atr > 0:
            return self.fail("ATR unavailable")
        recent = df.tail(260)
        highs, lows = swing_levels(recent["High"].to_numpy(), recent["Low"].to_numpy())
        above = [x for x in highs if x > close * 1.001]
        below = [x for x in lows if x < close * 0.999]
        resistance = min(above) if above else None
        support = max(below) if below else None
        obstacle = resistance if d > 0 else support
        room = abs(obstacle - close) / atr if obstacle is not None else math.inf
        extension = d * (close - float(ind["ema20"].iloc[-1])) / atr
        hi, lo = float(recent["High"].max()), float(recent["Low"].min())
        range_pos = (close - lo) / (hi - lo) if hi > lo else 0.5
        if d < 0:
            range_pos = 1 - range_pos
        pats = candle_patterns(df)
        pattern_score = clip(d * sum(pats.values()), -1, 1)
        gap = abs(float(df["Open"].iloc[-1]) - float(df["Close"].iloc[-2])) / atr
        data = {"support": support, "resistance": resistance,
                "room_atr": None if math.isinf(room) else room, "extension_atr": extension,
                "range_position": range_pos, "patterns": list(pats), "gap_atr": gap}

        if extension > pc.max_extension_atr:
            return self.fail(f"over-extended: {extension:.1f} ATR from the 20-day EMA "
                             f"(max {pc.max_extension_atr})", **data)
        level = "resistance" if d > 0 else "support"
        if room < pc.min_room_atr:
            return self.fail(f"only {room:.1f} ATR to {level} at {obstacle:.2f}", **data)

        reasons = ["no overhead resistance in the last year" if math.isinf(room) and d > 0
                   else "no support below in the last year" if math.isinf(room)
                   else f"{room:.1f} ATR of room to {level} at {obstacle:.2f}",
                   f"{extension:+.1f} ATR from the 20-day EMA",
                   f"at {range_pos:.0%} of the 52-week range (in trade direction)"]
        if pats:
            reasons.append("candles: " + ", ".join(pats))
        if gap > 2:
            reasons.append(f"large opening gap ({gap:.1f} ATR)")
        score = (0.35 * clip(room / 4) + 0.25 * (1 - clip(max(extension, 0) / pc.max_extension_atr))
                 + 0.2 * (0.5 + 0.5 * pattern_score) + 0.2 * range_pos - (0.1 if gap > 2 else 0.0))
        return self.ok(clip(score), reasons, **data)
