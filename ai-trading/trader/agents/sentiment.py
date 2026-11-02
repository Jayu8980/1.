"""Layer 8 - News & sentiment analyst."""
from __future__ import annotations

import json
import re

import numpy as np
import pandas as pd

from ..bus import Report
from .base import Agent, clip

POSITIVE = set("""beat beats surge surges soar soars jump jumps rally rallies record upgrade upgraded
outperform bullish growth profit profits strong raises raised buyback approval approved wins win
expands partnership breakthrough tops exceeds gain gains boost boosts optimistic upbeat rebound
rebounds""".split())
NEGATIVE = set("""miss misses plunge plunges slump slumps fall falls drop drops downgrade downgraded
underperform bearish loss losses weak cuts cut lawsuit probe investigation recall fraud bankruptcy
layoffs warning warns decline declines selloff sell-off crash fined halt delay delays concern
concerns slowdown tumble tumbles sinks""".split())

SYSTEM = """You score recent news for a trading system. For each symbol you get recent headlines,
with summaries when available. Return:
- sentiment: from -1 (clearly bad for the share price) to +1 (clearly good); 0 when mixed or neutral
- confidence: from 0 to 1, how informative and price-relevant the news is. Routine, stale or generic
  market coverage deserves low confidence.
- summary: one sentence on what matters for the price.
Judge only from the text provided."""

SCHEMA = {
    "type": "object",
    "properties": {"results": {"type": "array", "items": {
        "type": "object",
        "properties": {"symbol": {"type": "string"}, "sentiment": {"type": "number"},
                       "confidence": {"type": "number"}, "summary": {"type": "string"}},
        "required": ["symbol", "sentiment", "confidence", "summary"],
        "additionalProperties": False}}},
    "required": ["results"],
    "additionalProperties": False,
}


def fetch_headlines(symbol: str, max_items: int, max_age_days: int) -> list[dict]:
    import yfinance as yf

    now = pd.Timestamp.now(tz="UTC")
    out = []
    for item in yf.Ticker(symbol).news or []:
        content = item.get("content") if isinstance(item.get("content"), dict) else item
        title = content.get("title") or ""
        summary = content.get("summary") or content.get("description") or ""
        when = content.get("pubDate") or content.get("displayTime")
        ts = None
        try:
            if when:
                ts = pd.Timestamp(when)
            elif item.get("providerPublishTime"):
                ts = pd.Timestamp(item["providerPublishTime"], unit="s")
            if ts is not None and ts.tzinfo is None:
                ts = ts.tz_localize("UTC")
        except (ValueError, TypeError):
            ts = None
        if ts is not None and (now - ts).days > max_age_days:
            continue
        if title:
            out.append({"title": title, "summary": summary[:300],
                        "date": ts.date().isoformat() if ts is not None else ""})
        if len(out) >= max_items:
            break
    return out


def lexicon_score(headlines: list[dict]) -> tuple[float, float]:
    """Fallback scorer: share of positive vs negative finance words. Weak, so low confidence."""
    scores = []
    for h in headlines:
        words = re.findall(r"[a-z\-]+", f"{h['title']} {h['summary']}".lower())
        pos = sum(w in POSITIVE for w in words)
        neg = sum(w in NEGATIVE for w in words)
        if pos or neg:
            scores.append((pos - neg) / (pos + neg))
    if not scores:
        return 0.0, 0.0
    return float(np.mean(scores)), min(1.0, len(scores) / 8) * 0.6


class SentimentAgent(Agent):
    name = "sentiment"
    title = "News & Sentiment Analyst"
    layer = 8
    fail_open = True
    role = ("Reads recent headlines (scored by Claude when available, otherwise by a finance word "
            "list) and vetoes trades the news flow is clearly against.")

    def prepare(self):
        self.results: dict[str, dict] = {}
        nc = self.cfg.news
        self.enabled = nc.enabled and self.cfg.data.source != "synthetic"
        if not self.enabled:
            return
        news = {}
        for cand in self.board.alive():
            try:
                news[cand.symbol] = fetch_headlines(cand.symbol, nc.max_headlines, nc.max_age_days)
            except Exception as exc:
                self.log.debug("news for %s failed: %s", cand.symbol, exc)
                news[cand.symbol] = []
        scored = self._score_with_claude({s: h for s, h in news.items() if h})
        for symbol, headlines in news.items():
            if symbol in scored:
                self.results[symbol] = {**scored[symbol], "source": "claude", "headlines": len(headlines)}
            else:
                sentiment, confidence = lexicon_score(headlines)
                self.results[symbol] = {"sentiment": sentiment, "confidence": confidence,
                                        "summary": headlines[0]["title"] if headlines else "",
                                        "source": "word list", "headlines": len(headlines)}

    def _score_with_claude(self, news: dict[str, list[dict]]) -> dict[str, dict]:
        llm = self.services.get("llm")
        if llm is None or not news:
            return {}
        answer = llm.json_call(SYSTEM, json.dumps(news, ensure_ascii=False), SCHEMA,
                               effort=self.cfg.llm.sentiment_effort)
        out = {}
        for row in (answer or {}).get("results", []):
            if row.get("symbol") in news:
                out[row["symbol"]] = {"sentiment": clip(row["sentiment"], -1, 1),
                                      "confidence": clip(row["confidence"]), "summary": row["summary"]}
        return out

    def evaluate(self, cand):
        if not self.enabled:
            return Report(self.name, True, score=None, reasons=["news check off"])
        r = self.results.get(cand.symbol)
        if not r or not r["headlines"]:
            return self.ok(0.5, ["no recent news"], headlines=0)
        aligned = cand.direction * r["sentiment"]
        data = {**r, "aligned_sentiment": aligned}
        text = (f"{r['headlines']} headlines, sentiment {r['sentiment']:+.2f} "
                f"(confidence {r['confidence']:.2f}, {r['source']})")
        if aligned <= self.cfg.news.veto_below and r["confidence"] >= 0.5:
            return self.fail(f"news flow is against the trade: {text}", score=0.1, **data)
        reasons = [text] + ([r["summary"]] if r["summary"] else [])
        return self.ok(clip(0.5 + 0.5 * aligned * r["confidence"]), reasons, **data)
