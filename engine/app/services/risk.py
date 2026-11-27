"""Server-side risk engine — the absolute veto. Phase-3 adds per-trade stop-based sizing,
sector and correlation caps; these limits are enforced today:
  * daily loss limit: breach => veto every new order and auto-engage the kill switch
  * max position size: order notional <= maxPositionPct of equity
  * max drawdown from equity peak: breach => kill switch
Exits that reduce a position bypass the size limits (see ExecutionGateway.place(exit_only=True)).
"""
from __future__ import annotations

import json
from decimal import Decimal
from pathlib import Path

from pydantic import BaseModel, Field

from app.core.models import OrderRequest
from app.execution.risk_hook import RiskDecision

D = Decimal


class RiskLimits(BaseModel):
    # !! PLACEHOLDER DEFAULTS — set your own from the app before LIVE.
    dailyLossLimit: float = Field(10_000, gt=0)
    maxDrawdownPct: float = Field(10, ge=1, le=30)
    maxRiskPerTradePct: float = Field(1, ge=0.1, le=2)
    maxPositionPct: float = Field(10, gt=0, le=100)
    maxSectorPct: float = Field(25, gt=0, le=100)
    kellyFraction: float = Field(0.25, ge=0, le=0.25)


class RiskEngine:
    def __init__(self, path: str | Path, portfolio=None):
        self.path = Path(path); self.path.parent.mkdir(parents=True, exist_ok=True)
        self.limits = RiskLimits.model_validate_json(self.path.read_text()) if self.path.exists() else RiskLimits()
        self.portfolio = portfolio
        self.peaks: dict[str, Decimal] = {}
        self.breaches: list[str] = []

    def update(self, limits: RiskLimits) -> None:
        self.limits = limits
        self.path.write_text(limits.model_dump_json())

    async def state(self, broker=None) -> tuple[Decimal, Decimal, Decimal]:
        s = await self.portfolio.snapshot(broker)
        eq, day = s["equityDec"], s["dayPnlDec"]
        key = getattr(broker, "name", "current") if broker is not None else "current"
        self.peaks[key] = max(self.peaks.get(key, eq), eq)
        peak = self.peaks[key]
        dd = (peak - eq) / peak * 100 if peak else D("0")
        return eq, day, dd

    async def evaluate(self, broker=None) -> list[str]:
        """Current breaches for the account `broker` (default: the engine's current one)."""
        eq, day, dd = await self.state(broker)
        b = []
        if -day >= D(str(self.limits.dailyLossLimit)):
            b.append(f"daily loss {-day:.0f} >= limit {self.limits.dailyLossLimit:.0f}")
        if dd >= D(str(self.limits.maxDrawdownPct)):
            b.append(f"drawdown {dd:.1f}% >= max {self.limits.maxDrawdownPct:.1f}%")
        self.breaches = b
        return b

    async def check(self, req: OrderRequest, ref_price: Decimal, broker=None) -> RiskDecision:
        breaches = await self.evaluate(broker)
        if breaches:
            return RiskDecision(False, "; ".join(breaches))
        eq, _, _ = await self.state(broker)
        notional = D(req.quantity) * D(ref_price) * req.contract.multiplier
        cap = eq * D(str(self.limits.maxPositionPct)) / 100
        if notional > cap:
            return RiskDecision(False, f"order value {notional:.0f} > {self.limits.maxPositionPct:.0f}% of equity ({cap:.0f})")
        return RiskDecision(True)

    async def view(self, kill_switch: bool) -> dict:
        eq, day, dd = await self.state()
        return {**self.limits.model_dump(), "dayLossUsed": float(max(-day, D("0"))),
                "currentDrawdownPct": float(dd), "killSwitch": kill_switch, "breaches": self.breaches}
