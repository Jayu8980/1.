"""Layer 4 - Multi-timeframe analyst."""
from __future__ import annotations

import numpy as np

from ..indicators import ema, macd, resample, rsi
from .base import Agent


class TimeframeAgent(Agent):
    name = "timeframe"
    title = "Multi-Timeframe Analyst"
    layer = 4
    role = "Checks that the weekly and monthly trends point the same way as the daily trade."

    def evaluate(self, cand):
        d = cand.direction
        weekly = resample(cand.df, "W-FRI")["Close"]
        monthly = resample(cand.df, "ME")["Close"]
        votes = {}
        if len(weekly) >= 35:
            e10, e30 = ema(weekly, 10), ema(weekly, 30)
            votes["weekly close vs 10-week EMA"] = np.sign(weekly.iloc[-1] - e10.iloc[-1])
            votes["weekly 10/30 EMA"] = np.sign(e10.iloc[-1] - e30.iloc[-1])
            votes["weekly MACD"] = np.sign(macd(weekly)[2].iloc[-1])
            votes["weekly RSI vs 50"] = np.sign(rsi(weekly, 14).iloc[-1] - 50)
        if len(monthly) >= 13:
            votes["monthly close vs 10-month SMA"] = np.sign(monthly.iloc[-1] - monthly.rolling(10).mean().iloc[-1])
            votes["12-month momentum"] = np.sign(monthly.iloc[-1] / monthly.iloc[-13] - 1)
        valid = {k: float(v) for k, v in votes.items() if not np.isnan(v)}
        if not valid:
            return self.fail("not enough weekly and monthly history")
        agreeing = [k for k, v in valid.items() if v * d > 0]
        against = [k for k, v in valid.items() if v * d <= 0]
        share = len(agreeing) / len(valid)
        data = {"agreement": share, "votes": valid}
        if share < self.cfg.timeframe.min_agreement:
            return self.fail(f"higher timeframes disagree ({len(agreeing)}/{len(valid)} agree; "
                             f"against: {', '.join(against[:3])})", **data)
        reasons = [f"{len(agreeing)}/{len(valid)} weekly and monthly signals agree"]
        if against:
            reasons.append(f"against: {', '.join(against)}")
        return self.ok(share, reasons, **data)
