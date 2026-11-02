"""Layer 12 - AI chief analyst (Claude)."""
from __future__ import annotations

import json

from ..bus import Report
from ..llm import to_jsonable
from .base import Agent, clip

SYSTEM = """You are the final reviewer on a multi-agent trading desk. Specialist agents - market
regime, technical indicators, multi-timeframe trend, price action, a strategy optimizer with
out-of-sample backtests, a machine-learning forecaster, news sentiment, fundamentals and a risk
manager - have each analysed one proposed trade. Their reports and the trade plan are in the user
message as JSON.

Decide whether to approve the trade. Approve only when the evidence is coherent and the plan is
sound. Reject when you see a material problem, for example: agents contradicting each other in a
way the scores hide, an edge resting on too few trades, a stop or target that ignores the reported
support and resistance, a position size inconsistent with the stated risk, event risk such as
imminent earnings, or data that looks wrong. Avoiding a bad trade is worth more than adding a
marginal one.

Use only the data provided and don't assume facts that aren't in the reports. Keep each text field
to one to three sentences and list at most five key risks. Set confidence to your probability,
between 0 and 1, that approving is the right call."""

SCHEMA = {
    "type": "object",
    "properties": {
        "approve": {"type": "boolean"},
        "confidence": {"type": "number"},
        "bull_case": {"type": "string"},
        "bear_case": {"type": "string"},
        "key_risks": {"type": "array", "items": {"type": "string"}},
        "suggested_changes": {"type": "string"},
        "verdict": {"type": "string"},
    },
    "required": ["approve", "confidence", "bull_case", "bear_case", "key_risks", "suggested_changes", "verdict"],
    "additionalProperties": False,
}

_REGIME_KEYS = ("regime", "trend_score", "volatility", "vol_measure", "breadth",
                "benchmark_return_63d", "benchmark_drawdown", "risk_multiplier", "notes")


def dossier(cand, context: dict) -> dict:
    """Everything the agents concluded about one candidate, ready to send as JSON."""
    regime = context.get("regime") or {}
    return to_jsonable({
        "symbol": cand.symbol,
        "side": cand.side,
        "last_bar": cand.df.index[-1].date().isoformat(),
        "trade_plan": cand.plan,
        "market_regime": {k: regime.get(k) for k in _REGIME_KEYS},
        "agent_reports": {name: {"passed": r.passed, "score": r.score, "reasons": r.reasons, "data": r.data}
                          for name, r in cand.reports.items()},
    })


class AIReviewAgent(Agent):
    name = "ai_review"
    title = "AI Chief Analyst (Claude)"
    layer = 12
    fail_open = True
    role = ("Claude reads every agent's report for each finalist, weighs the bull and bear case, "
            "and approves or rejects the trade with its reasons.")

    def evaluate(self, cand):
        llm = self.services.get("llm")
        required = self.cfg.llm.required
        if llm is None:
            if required:
                return self.fail("AI review is required but Claude is not configured")
            return Report(self.name, True, score=None, reasons=["AI review off (no Claude credentials)"])
        verdict = llm.json_call(SYSTEM, json.dumps(dossier(cand, self.board.context)), SCHEMA,
                                effort=self.cfg.llm.review_effort)
        if verdict is None:
            if required:
                return self.fail("AI review unavailable and llm.required is on")
            return Report(self.name, True, score=None,
                          reasons=["AI review unavailable - keeping the quantitative decision"])
        confidence = clip(verdict.get("confidence", 0.5))
        data = {**verdict, "confidence": confidence}
        if not verdict.get("approve"):
            return self.fail(f"AI reviewer rejected: {verdict.get('verdict', '')}", score=1 - confidence, **data)
        return self.ok(confidence, [f"approved ({confidence:.0%}): {verdict.get('verdict', '')}",
                                    f"bear case: {verdict.get('bear_case', '')}"], **data)
