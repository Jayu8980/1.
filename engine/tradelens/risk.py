"""Live risk manager: daily-loss auto-flatten, drawdown circuit breaker, order caps, kill switches."""
from __future__ import annotations

import json
from dataclasses import asdict, dataclass, field
from datetime import date
from pathlib import Path

from .config import RiskLimits


@dataclass
class RiskState:
    day: str = ""
    start_of_day_equity: float = 0.0
    peak_equity: float = 0.0
    orders_today: int = 0
    halted_for_day: bool = False
    circuit_broken: bool = False
    global_kill: bool = False
    killed: set = field(default_factory=set)       # per-strategy kill switches
    api_errors: int = 0


class RiskManager:
    def __init__(self, limits: RiskLimits, state_file: Path | None = None):
        self.limits = limits
        self.state_file = state_file
        self.state = RiskState()
        if state_file and state_file.exists():
            d = json.loads(state_file.read_text())
            d["killed"] = set(d.get("killed", []))
            self.state = RiskState(**d)

    def save(self) -> None:
        if self.state_file:
            d = asdict(self.state); d["killed"] = sorted(self.state.killed)
            self.state_file.write_text(json.dumps(d))

    def new_day(self, today: date, equity: float) -> None:
        if self.state.day != today.isoformat():
            self.state.day = today.isoformat()
            self.state.start_of_day_equity = equity
            self.state.orders_today = 0
            self.state.halted_for_day = False
            self.state.api_errors = 0
            self.state.peak_equity = max(self.state.peak_equity, equity)
            self.save()

    def on_equity(self, equity: float) -> str | None:
        """Returns 'flatten_halt_day' / 'circuit_break' when a limit is breached (caller flattens)."""
        s = self.state
        if s.start_of_day_equity <= 0:
            s.start_of_day_equity = equity
        s.peak_equity = max(s.peak_equity, equity)
        dd = (s.peak_equity - equity) / s.peak_equity * 100 if s.peak_equity else 0
        day_loss = (s.start_of_day_equity - equity) / s.start_of_day_equity * 100
        if dd >= self.limits.maxDrawdownPct and not s.circuit_broken:
            s.circuit_broken = True; s.halted_for_day = True; self.save()
            return "circuit_break"
        if day_loss >= self.limits.dailyLossPct and not s.halted_for_day:
            s.halted_for_day = True; self.save()
            return "flatten_halt_day"
        return None

    def can_trade(self, strategy: str, risk_reducing: bool = False) -> tuple[bool, str]:
        s = self.state
        if s.global_kill:
            return False, "global kill switch"
        if s.circuit_broken:
            return False, "drawdown circuit breaker – manual restart required"
        if risk_reducing:
            return True, ""
        if s.halted_for_day:
            return False, "daily loss limit – halted for the day"
        if strategy in s.killed:
            return False, f"kill switch on {strategy}"
        if s.orders_today >= self.limits.maxOrdersPerDay:
            return False, "daily order cap"
        return True, ""

    def record_order(self) -> None:
        self.state.orders_today += 1

    def restart(self, equity: float) -> None:
        self.state.circuit_broken = False; self.state.halted_for_day = False
        self.state.peak_equity = equity; self.state.start_of_day_equity = equity
        self.save()

    def drawdown_pct(self, equity: float) -> float:
        return (self.state.peak_equity - equity) / self.state.peak_equity * 100 if self.state.peak_equity else 0.0
