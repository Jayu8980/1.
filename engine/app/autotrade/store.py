"""Persisted strategy config (enabled / allocation / stage), trade journal, stats and the
promotion gate: PAPER -> LIVE_10 -> LIVE.

Gate (from the project rules):
  PAPER -> LIVE_10 : >= 28 days in PAPER, >= 30 closed paper trades, positive expectancy
  LIVE_10 -> LIVE  : >= 28 days in LIVE_10, >= 15 live trades, live expectancy >= 50% of paper
Drift guard: a LIVE_10/LIVE strategy whose live drawdown exceeds 1.5x its paper max drawdown, or
whose rolling live Sharpe (last 20 trades) goes negative, is auto-disabled.
"""
from __future__ import annotations

import json
import math
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from pathlib import Path

STAGES = ("PAPER", "LIVE_10", "LIVE")
GATE_DAYS = 28
GATE_PAPER_TRADES = 30
GATE_LIVE_TRADES = 15


@dataclass
class StrategyConfig:
    enabled: bool = True
    allocationPct: float = 25.0
    stage: str = "PAPER"
    stageSince: str = field(default_factory=lambda: datetime.now(timezone.utc).isoformat())
    autoDisabledReason: str | None = None


def _now():
    return datetime.now(timezone.utc)


def trade_stats(trades: list[dict]) -> dict:
    pnls = [t["pnl"] for t in trades]
    if not pnls:
        return {"trades": 0, "winRate": 0.0, "pnl": 0.0, "expectancy": 0.0, "sharpe": 0.0, "maxDd": 0.0, "ddPct": 0.0}
    eq, peak, mdd = 0.0, 0.0, 0.0
    for p in pnls:
        eq += p; peak = max(peak, eq); mdd = max(mdd, peak - eq)
    rets = [t["pnl"] / t["notional"] for t in trades if t.get("notional")]
    mu = sum(rets) / len(rets) if rets else 0.0
    sd = math.sqrt(sum((r - mu) ** 2 for r in rets) / (len(rets) - 1)) if len(rets) > 1 else 0.0
    capital = max((t.get("capital") or 0) for t in trades) or 1.0
    return {"trades": len(pnls), "winRate": sum(p > 0 for p in pnls) / len(pnls), "pnl": sum(pnls),
            "expectancy": sum(pnls) / len(pnls), "sharpe": (mu / sd * math.sqrt(252)) if sd > 1e-12 else 0.0,
            "maxDd": mdd, "ddPct": mdd / capital * 100}


class StrategyStore:
    def __init__(self, config_path: str | Path, journal_path: str | Path, strategy_ids: list[str]):
        self.config_path, self.journal_path = Path(config_path), Path(journal_path)
        self.config_path.parent.mkdir(parents=True, exist_ok=True)
        raw = json.loads(self.config_path.read_text()) if self.config_path.exists() else {}
        self.cfg: dict[str, StrategyConfig] = {
            sid: StrategyConfig(**raw[sid]) if sid in raw else StrategyConfig() for sid in strategy_ids}
        self.autotrade_enabled: bool = raw.get("__autotrade__", {}).get("enabled", False)
        self._save()

    def _save(self) -> None:
        d = {k: asdict(v) for k, v in self.cfg.items()}
        d["__autotrade__"] = {"enabled": self.autotrade_enabled}
        self.config_path.write_text(json.dumps(d, indent=1))

    def set_autotrade(self, on: bool) -> None:
        self.autotrade_enabled = on; self._save()

    def update(self, sid: str, enabled: bool | None = None, allocation: float | None = None) -> str | None:
        c = self.cfg[sid]
        if enabled and c.autoDisabledReason:
            return f"auto-disabled: {c.autoDisabledReason} — demote to PAPER to re-enable"
        if allocation is not None:
            total = sum(v.allocationPct for k, v in self.cfg.items() if k != sid) + allocation
            if not 0 <= allocation <= 100 or total > 100:
                return f"total allocation would be {total:.0f}% (max 100%)"
            c.allocationPct = allocation
        if enabled is not None:
            c.enabled = enabled
        self._save()
        return None

    # ------------------------------------------------------------ journal
    def record_trade(self, t: dict) -> None:
        with self.journal_path.open("a") as f:
            f.write(json.dumps(t, default=str) + "\n")

    def trades(self, sid: str | None = None, venue: str | None = None) -> list[dict]:
        if not self.journal_path.exists():
            return []
        out = [json.loads(x) for x in self.journal_path.read_text().splitlines() if x.strip()]
        return [t for t in out if (sid is None or t["strategy"] == sid) and (venue is None or t["venue"] == venue)]

    def trades_today(self, sid: str, today_iso: str) -> int:
        return sum(1 for t in self.trades(sid) if t.get("opened", "").startswith(today_iso))

    # ------------------------------------------------------------ gate
    def days_in_stage(self, sid: str) -> float:
        return (_now() - datetime.fromisoformat(self.cfg[sid].stageSince)).total_seconds() / 86400

    def gate(self, sid: str) -> tuple[bool, str, str | None]:
        """(eligible, explanation, next_stage)"""
        c = self.cfg[sid]
        if c.stage == "LIVE":
            return False, "already at full LIVE", None
        days = self.days_in_stage(sid)
        paper, live = trade_stats(self.trades(sid, "paper")), trade_stats(self.trades(sid, "live"))
        if c.stage == "PAPER":
            missing = []
            if days < GATE_DAYS: missing.append(f"{GATE_DAYS - days:.0f} more days in PAPER")
            if paper["trades"] < GATE_PAPER_TRADES: missing.append(f"{GATE_PAPER_TRADES - paper['trades']} more paper trades")
            if paper["expectancy"] <= 0: missing.append("positive paper expectancy")
            return (not missing, "needs " + ", ".join(missing) if missing else "eligible for LIVE_10 (10% of allocation)",
                    "LIVE_10")
        missing = []
        if days < GATE_DAYS: missing.append(f"{GATE_DAYS - days:.0f} more days in LIVE_10")
        if live["trades"] < GATE_LIVE_TRADES: missing.append(f"{GATE_LIVE_TRADES - live['trades']} more live trades")
        if paper["expectancy"] > 0 and live["expectancy"] < 0.5 * paper["expectancy"]:
            missing.append("live expectancy within 50% of paper")
        return (not missing, "needs " + ", ".join(missing) if missing else "eligible for full LIVE", "LIVE")

    def promote(self, sid: str) -> str:
        ok, why, nxt = self.gate(sid)
        if not ok:
            raise ValueError(why)
        c = self.cfg[sid]
        c.stage, c.stageSince = nxt, _now().isoformat()
        self._save()
        return nxt

    def demote(self, sid: str) -> None:
        c = self.cfg[sid]
        c.stage, c.stageSince, c.autoDisabledReason = "PAPER", _now().isoformat(), None
        self._save()

    def drift_check(self, sid: str) -> str | None:
        c = self.cfg[sid]
        if c.stage == "PAPER" or c.autoDisabledReason:
            return None
        paper, live_trades = trade_stats(self.trades(sid, "paper")), self.trades(sid, "live")
        live = trade_stats(live_trades)
        reason = None
        if paper["maxDd"] > 0 and live["maxDd"] > 1.5 * paper["maxDd"]:
            reason = f"live drawdown {live['maxDd']:.0f} > 1.5x paper max {paper['maxDd']:.0f}"
        elif len(live_trades) >= 20 and trade_stats(live_trades[-20:])["sharpe"] < 0:
            reason = "rolling 20-trade live Sharpe below 0"
        if reason:
            c.enabled, c.autoDisabledReason = False, reason
            self._save()
        return reason
