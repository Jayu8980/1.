"""Promotion pipeline: RESEARCH → WALK_FORWARD → PAPER (≥ 4 weeks live data) → LIVE_SMALL → LIVE.
Automatic demotion when live results drift beyond 2σ of the backtest expectation."""
from __future__ import annotations

import json
import math
import time
from dataclasses import asdict, dataclass, field
from pathlib import Path

import numpy as np

STAGES = ["RESEARCH", "WALK_FORWARD", "PAPER", "LIVE_SMALL", "LIVE", "DEMOTED", "REJECTED"]
CAPITAL_FRACTION = {"PAPER": 0.0, "LIVE_SMALL": 0.1, "LIVE": 1.0}


@dataclass
class StrategyRecord:
    name: str
    stage: str = "RESEARCH"
    enabled: bool = False
    auto: bool = False                     # auto-execute without per-signal approval (off by default)
    killed: bool = False
    bt_mean: float = 0.0                   # backtest mean daily return
    bt_std: float = 0.01
    oos_sharpe: float = 0.0
    dsr: float | None = None
    pbo: float | None = None
    stage_since: float = field(default_factory=time.time)
    live_returns: list[float] = field(default_factory=list)
    notes: str = ""
    history: list[tuple] = field(default_factory=list)

    def drift_sigma(self) -> float | None:
        n = len(self.live_returns)
        if n < 10 or self.bt_std <= 0:
            return None
        return float((np.mean(self.live_returns) - self.bt_mean) / (self.bt_std / math.sqrt(n)))


class Pipeline:
    def __init__(self, path: Path):
        self.path = path
        self.records: dict[str, StrategyRecord] = {}
        if path.exists():
            for d in json.loads(path.read_text()):
                d["history"] = [tuple(h) for h in d.get("history", [])]
                self.records[d["name"]] = StrategyRecord(**d)

    def save(self) -> None:
        self.path.write_text(json.dumps([asdict(r) for r in self.records.values()]))

    def get(self, name: str) -> StrategyRecord:
        return self.records.setdefault(name, StrategyRecord(name))

    def _move(self, r: StrategyRecord, stage: str, why: str) -> None:
        r.history.append((time.time(), r.stage, stage, why))
        r.stage, r.stage_since, r.notes = stage, time.time(), why
        if stage in ("DEMOTED", "REJECTED"):
            r.enabled = False; r.auto = False
        self.save()

    def record_backtest(self, name: str, verdict, daily_returns, oos_sharpe: float) -> StrategyRecord:
        r = self.get(name)
        r.bt_mean, r.bt_std = float(np.nanmean(daily_returns)), float(np.nanstd(daily_returns) or 0.01)
        r.oos_sharpe, r.dsr, r.pbo = oos_sharpe, verdict.stats.get("dsr"), verdict.stats.get("pbo")
        if verdict.passed:
            self._move(r, "PAPER", "walk-forward + anti-overfitting checks passed → paper trading")
            r.enabled = True
        else:
            self._move(r, "REJECTED", "; ".join(verdict.reasons))
        return r

    def record_live_return(self, name: str, ret: float) -> str | None:
        r = self.get(name)
        r.live_returns.append(float(ret))
        z = r.drift_sigma()
        if r.stage in ("LIVE_SMALL", "LIVE") and z is not None and abs(z) > 2:
            self._move(r, "DEMOTED", f"live drift {z:.1f}σ from backtest expectation")
            return "demoted"
        self.save()
        return None

    def try_promote(self, name: str, min_paper_days: int = 28) -> str | None:
        """Paper → LIVE_SMALL after ≥ 4 weeks within 2σ; LIVE_SMALL → LIVE after another 8 weeks within 1σ."""
        r = self.get(name)
        days = (time.time() - r.stage_since) / 86400
        z = r.drift_sigma()
        if r.stage == "PAPER" and days >= min_paper_days and z is not None and abs(z) <= 2:
            r.live_returns = []
            self._move(r, "LIVE_SMALL", f"{days:.0f} days of paper within {abs(z):.1f}σ")
            return "LIVE_SMALL"
        if r.stage == "LIVE_SMALL" and days >= 56 and z is not None and abs(z) <= 1:
            self._move(r, "LIVE", f"{days:.0f} days small-live within {abs(z):.1f}σ")
            return "LIVE"
        return None
