"""Risk veto interface. The full risk engine lands in Phase 3; this is the contract plus a
minimal hard pre-trade check so nothing reaches a broker without passing a veto."""
from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal
from typing import Protocol

from app.core.models import OrderRequest


@dataclass(frozen=True)
class RiskDecision:
    approved: bool
    reason: str = ""


class RiskVeto(Protocol):
    async def check(self, req: OrderRequest, ref_price: Decimal, broker=None) -> RiskDecision: ...


class HardLimits:
    """Absolute, static limits. Deliberately simple and conservative."""

    def __init__(self, max_order_value: Decimal, max_qty: int = 10_000):
        self.max_order_value = Decimal(max_order_value)
        self.max_qty = max_qty

    async def check(self, req: OrderRequest, ref_price: Decimal, broker=None) -> RiskDecision:
        value = Decimal(req.quantity) * Decimal(ref_price)
        if req.quantity > self.max_qty:
            return RiskDecision(False, f"qty {req.quantity} > max {self.max_qty}")
        if value > self.max_order_value:
            return RiskDecision(False, f"order value {value:.0f} > max {self.max_order_value:.0f}")
        return RiskDecision(True)
