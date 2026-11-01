"""Layer 9 - Fundamental & event-risk analyst."""
from __future__ import annotations

import numpy as np
import pandas as pd

from ..bus import Report
from ..config import is_equity_like
from .base import Agent


def _num(info: dict, key: str) -> float | None:
    value = info.get(key)
    return float(value) if isinstance(value, (int, float)) and not isinstance(value, bool) else None


def quality_score(info: dict) -> tuple[float | None, dict[str, float]]:
    """0..1 business quality from profitability, growth, balance sheet, valuation and analysts."""
    parts = {}
    if (x := _num(info, "profitMargins")) is not None:
        parts["profit margin"] = 1.0 if x > 0.15 else 0.7 if x > 0.05 else 0.4 if x > 0 else 0.0
    if (x := _num(info, "revenueGrowth")) is not None:
        parts["revenue growth"] = 1.0 if x > 0.15 else 0.7 if x > 0.05 else 0.5 if x > 0 else 0.2
    if (x := _num(info, "returnOnEquity")) is not None:
        parts["return on equity"] = 1.0 if x > 0.2 else 0.7 if x > 0.1 else 0.4 if x > 0 else 0.0
    if (x := _num(info, "debtToEquity")) is not None:  # Yahoo reports this in percent
        parts["debt/equity"] = 1.0 if x < 50 else 0.7 if x < 100 else 0.4 if x < 200 else 0.1
    if (x := _num(info, "forwardPE")) is not None:
        parts["forward P/E"] = 0.1 if x <= 0 else 1.0 if x < 15 else 0.7 if x < 25 else 0.4 if x < 40 else 0.2
    if (x := _num(info, "recommendationMean")) is not None:  # 1 = strong buy ... 5 = sell
        parts["analyst rating"] = 1.0 if x <= 2 else 0.7 if x <= 2.5 else 0.5 if x <= 3 else 0.2
    if not parts:
        return None, {}
    return float(np.mean(list(parts.values()))), parts


def days_to_earnings(ticker) -> int | None:
    try:
        cal = ticker.calendar
    except Exception:
        return None
    dates = None
    if isinstance(cal, dict):
        dates = cal.get("Earnings Date")
    elif isinstance(cal, pd.DataFrame) and "Earnings Date" in cal.index:
        dates = list(cal.loc["Earnings Date"].values)
    if dates is None:
        return None
    if not isinstance(dates, (list, tuple)):
        dates = [dates]
    today = pd.Timestamp.today().normalize()
    ahead = []
    for value in dates:
        try:
            ts = pd.Timestamp(value)
        except (ValueError, TypeError):
            continue
        if ts.tzinfo is not None:
            ts = ts.tz_localize(None)
        if ts.normalize() >= today:
            ahead.append((ts.normalize() - today).days)
    return min(ahead) if ahead else None


class FundamentalAgent(Agent):
    name = "fundamental"
    title = "Fundamental & Event-Risk Analyst"
    layer = 9
    fail_open = True
    role = ("For company shares: scores profitability, growth, balance sheet, valuation and "
            "analyst views, and blocks trades with an earnings report due inside the blackout window.")

    def evaluate(self, cand):
        fc = self.cfg.fundamental
        if not fc.enabled or self.cfg.data.source == "synthetic":
            return Report(self.name, True, score=None, reasons=["fundamental check off"])
        if not is_equity_like(cand.symbol):
            return Report(self.name, True, score=None, reasons=["not a company share - skipped"])
        import yfinance as yf

        ticker = yf.Ticker(cand.symbol)
        info = ticker.info or {}
        if info.get("quoteType") not in (None, "EQUITY"):
            return Report(self.name, True, score=None, reasons=[f"{info.get('quoteType')} - no company fundamentals"])
        days = days_to_earnings(ticker)
        if days is not None and days <= fc.earnings_blackout_days:
            return self.fail(f"earnings due in {days} day(s) - overnight gap risk", score=0.2,
                             days_to_earnings=days)
        quality, parts = quality_score(info)
        if quality is None:
            return self.ok(0.5, ["no fundamental data"], days_to_earnings=days)
        data = {"quality": quality, "components": parts, "days_to_earnings": days}
        weakest = ", ".join(k for k, v in sorted(parts.items(), key=lambda kv: kv[1])[:2])
        if cand.direction > 0 and quality < fc.min_quality:
            return self.fail(f"weak fundamentals (quality {quality:.2f}; weakest: {weakest})", **data)
        favour = quality if cand.direction > 0 else 1 - quality
        reasons = [f"business quality {quality:.2f} from {len(parts)} metrics"]
        strongest = [k for k, v in parts.items() if v >= 0.7]
        if strongest:
            reasons.append("strong: " + ", ".join(strongest))
        if days is not None:
            reasons.append(f"next earnings in {days} days")
        return self.ok(favour, reasons, **data)
