"""The base class every agent extends."""
from __future__ import annotations

import logging
import math

from ..bus import Blackboard, Candidate, MessageBus, Report


def clip(x: float, lo: float = 0.0, hi: float = 1.0) -> float:
    if x is None or (isinstance(x, float) and math.isnan(x)):
        return lo
    return max(lo, min(hi, float(x)))


class Agent:
    name = "agent"
    title = "Agent"
    layer = 0
    role = ""
    fail_open = False  # on an internal error: True lets the candidate through, False rejects it

    def __init__(self, cfg, bus: MessageBus, board: Blackboard, services: dict | None = None) -> None:
        self.cfg = cfg
        self.bus = bus
        self.board = board
        self.services = services or {}
        self.log = logging.getLogger(f"trader.agents.{self.name}")

    # -- lifecycle ---------------------------------------------------------------------------
    def prepare(self) -> None:
        """Runs once before candidates are evaluated (e.g. to read the market as a whole)."""

    def evaluate(self, cand: Candidate) -> Report:
        raise NotImplementedError

    def finalize(self, alive: list[Candidate]) -> None:
        """Runs once after every candidate was evaluated (cross-sectional decisions)."""

    def run(self) -> None:
        if not self._safely(self.prepare, "preparation"):
            return
        for cand in self.board.alive():
            try:
                report = self.evaluate(cand)
            except Exception as exc:  # one bad symbol must not stop the desk
                self.log.warning("%s: %s failed: %s", cand.symbol, self.title, exc,
                                 exc_info=self.log.isEnabledFor(logging.DEBUG))
                if self.fail_open:
                    report = Report(self.name, True, score=None, reasons=[f"skipped after an error: {exc}"])
                else:
                    report = Report(self.name, False, score=0.0, veto=f"{self.title} error: {exc}")
            self.record(cand, report)
        self._safely(lambda: self.finalize(self.board.alive()), "final step")

    def _safely(self, step, label: str) -> bool:
        """Run a whole-market step. On error, fail closed (reject everyone) unless fail_open."""
        try:
            step()
            return True
        except Exception as exc:
            self.log.error("%s %s failed: %s", self.title, label, exc,
                           exc_info=self.log.isEnabledFor(logging.DEBUG))
            if not self.fail_open:
                for cand in self.board.alive():
                    self.reject(cand, f"{self.title} {label} failed: {exc}")
            return self.fail_open

    # -- helpers -------------------------------------------------------------------------------
    def record(self, cand: Candidate, report: Report) -> None:
        cand.reports[self.name] = report
        self.bus.publish("agent.report", self.name, symbol=cand.symbol, passed=report.passed,
                         score=report.score, summary=report.summary())
        if not report.passed:
            self.reject(cand, report.veto or "did not pass")

    def reject(self, cand: Candidate, reason: str) -> None:
        cand.rejected_by = self.name
        cand.rejection = reason
        self.bus.publish("candidate.rejected", self.name, symbol=cand.symbol, reason=reason)

    def ok(self, score: float | None, reasons: list[str], **data) -> Report:
        return Report(self.name, True, score=score, reasons=reasons, data=data)

    def fail(self, veto: str, score: float = 0.0, **data) -> Report:
        return Report(self.name, False, score=score, veto=veto, data=data)
