from __future__ import annotations

from datetime import datetime
from decimal import Decimal
from uuid import uuid4

from pydantic import BaseModel, Field, model_validator

from .enums import Exchange, Instrument, OrderStatus, OrderType, Product, Side


class Contract(BaseModel):
    symbol: str                    # SmartAPI tradingsymbol, e.g. "SBIN-EQ"
    token: str                     # SmartAPI symboltoken
    exchange: Exchange
    instrument: Instrument = Instrument.EQUITY
    lot_size: int = 1
    tick_size: Decimal = Decimal("0.05")

    @property
    def key(self) -> str:
        return f"{self.exchange}:{self.symbol}"


class OrderRequest(BaseModel):
    contract: Contract
    side: Side
    quantity: int = Field(gt=0)
    order_type: OrderType = OrderType.LIMIT
    product: Product = Product.INTRADAY
    price: Decimal | None = None
    trigger_price: Decimal | None = None
    strategy: str = "manual"
    tag: str | None = None
    client_order_id: str = Field(default_factory=lambda: uuid4().hex[:16])

    @model_validator(mode="after")
    def _check(self) -> "OrderRequest":
        if self.order_type in (OrderType.LIMIT, OrderType.SL_LIMIT) and self.price is None:
            raise ValueError("price required for LIMIT / SL-LIMIT orders")
        if self.order_type in (OrderType.SL_LIMIT, OrderType.SL_MARKET) and self.trigger_price is None:
            raise ValueError("trigger_price required for stop-loss orders")
        if self.quantity % self.contract.lot_size != 0:
            raise ValueError(f"quantity must be a multiple of lot size {self.contract.lot_size}")
        return self


class Order(BaseModel):
    order_id: str
    request: OrderRequest
    status: OrderStatus
    filled_qty: int = 0
    avg_fill_price: Decimal = Decimal("0")
    charges: Decimal = Decimal("0")
    message: str = ""
    created_at: datetime
    updated_at: datetime


class Fill(BaseModel):
    order_id: str
    contract: Contract
    side: Side
    quantity: int
    price: Decimal
    charges: Decimal
    ts: datetime


class Position(BaseModel):
    contract: Contract
    product: Product
    net_qty: int = 0
    avg_price: Decimal = Decimal("0")
    realized_pnl: Decimal = Decimal("0")
    charges: Decimal = Decimal("0")
    ltp: Decimal = Decimal("0")

    @property
    def unrealized_pnl(self) -> Decimal:
        return (self.ltp - self.avg_price) * self.net_qty if self.net_qty else Decimal("0")


class Funds(BaseModel):
    available_cash: Decimal
    used_margin: Decimal = Decimal("0")
    realized_pnl: Decimal = Decimal("0")
    unrealized_pnl: Decimal = Decimal("0")
