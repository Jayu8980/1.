from __future__ import annotations

from abc import ABC, abstractmethod
from decimal import Decimal

from app.core.models import Contract, Funds, Order, OrderRequest, Position


class Broker(ABC):
    """Uniform interface for PaperBroker and SmartAPIBroker."""

    name: str = "base"

    @abstractmethod
    async def place_order(self, req: OrderRequest) -> Order: ...

    @abstractmethod
    async def cancel_order(self, order_id: str) -> Order: ...

    @abstractmethod
    async def modify_order(self, order_id: str, *, price: Decimal | None = None,
                           trigger_price: Decimal | None = None, quantity: int | None = None) -> Order: ...

    @abstractmethod
    async def orders(self) -> list[Order]: ...

    @abstractmethod
    async def positions(self) -> list[Position]: ...

    @abstractmethod
    async def funds(self) -> Funds: ...

    @abstractmethod
    async def ltp(self, contract: Contract) -> Decimal: ...

    async def ltps(self, contracts: list[Contract]) -> dict[str, Decimal]:
        """Batch quotes keyed by Contract.key. Adapters with a batch endpoint override this."""
        out = {}
        for c in contracts:
            try:
                out[c.key] = await self.ltp(c)
            except Exception:
                pass
        return out
