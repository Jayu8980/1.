"""SmartAPI implementation of the Broker interface (LIVE)."""
from __future__ import annotations

from datetime import datetime, timezone
from decimal import Decimal

from app.broker.base import Broker
from app.core.enums import Exchange, OrderStatus, OrderType, Product, Side
from app.core.errors import BrokerError
from app.core.models import Contract, Funds, Order, OrderRequest, Position

from .session import SessionManager

_STATUS = {
    "complete": OrderStatus.FILLED, "rejected": OrderStatus.REJECTED, "cancelled": OrderStatus.CANCELLED,
    "open": OrderStatus.OPEN, "open pending": OrderStatus.OPEN, "trigger pending": OrderStatus.PENDING,
    "validation pending": OrderStatus.PENDING, "put order req received": OrderStatus.PENDING,
    "modify pending": OrderStatus.OPEN, "cancel pending": OrderStatus.OPEN,
}


def _variety(req: OrderRequest) -> str:
    return "STOPLOSS" if req.order_type in (OrderType.SL_LIMIT, OrderType.SL_MARKET) else "NORMAL"


def to_smartapi_params(req: OrderRequest) -> dict:
    return {
        "variety": _variety(req),
        "tradingsymbol": req.contract.symbol,
        "symboltoken": req.contract.token,
        "transactiontype": req.side.value,
        "exchange": req.contract.exchange.value,
        "ordertype": req.order_type.value,
        "producttype": req.product.value,
        "duration": "DAY",
        "price": str(req.price or 0),
        "triggerprice": str(req.trigger_price or 0),
        "squareoff": "0",
        "stoploss": "0",
        "quantity": str(req.quantity),
        "ordertag": (req.tag or req.strategy)[:20],
    }


class SmartAPIBroker(Broker):
    name = "smartapi"

    def __init__(self, session: SessionManager):
        self.s = session
        self.c = session.client
        self._known: dict[str, OrderRequest] = {}

    async def place_order(self, req: OrderRequest) -> Order:
        data = await self.s.call_with_reauth(self.c.place_order, to_smartapi_params(req))
        oid = data.get("orderid") or data.get("uniqueorderid")
        if not oid:
            raise BrokerError(f"placeOrder returned no order id: {data}")
        self._known[oid] = req
        now = datetime.now(timezone.utc)
        return Order(order_id=oid, request=req, status=OrderStatus.PENDING, message="sent",
                     created_at=now, updated_at=now)

    async def cancel_order(self, order_id: str) -> Order:
        req = self._known.get(order_id)
        variety = _variety(req) if req else "NORMAL"
        await self.s.call_with_reauth(self.c.cancel_order, variety, order_id)
        return await self._find(order_id)

    async def modify_order(self, order_id: str, *, price=None, trigger_price=None, quantity=None) -> Order:
        cur = await self._find(order_id)
        req = cur.request.model_copy(update={k: v for k, v in dict(price=price, trigger_price=trigger_price,
                                                                     quantity=quantity).items() if v is not None})
        params = to_smartapi_params(req) | {"orderid": order_id}
        await self.s.call_with_reauth(self.c.modify_order, params)
        self._known[order_id] = req
        return await self._find(order_id)

    async def _find(self, order_id: str) -> Order:
        for o in await self.orders():
            if o.order_id == order_id:
                return o
        raise BrokerError(f"order {order_id} not in order book")

    async def orders(self) -> list[Order]:
        rows = await self.s.call_with_reauth(self.c.order_book)
        return [self._parse_order(r) for r in rows]

    def _parse_order(self, r: dict) -> Order:
        oid = r["orderid"]
        req = self._known.get(oid) or OrderRequest(
            contract=Contract(symbol=r["tradingsymbol"], token=str(r["symboltoken"]),
                              exchange=Exchange(r["exchange"]), lot_size=int(r.get("lotsize") or 1)),
            side=Side(r["transactiontype"]), quantity=int(r["quantity"]),
            order_type=OrderType(r["ordertype"]), product=Product(r["producttype"]),
            price=Decimal(str(r.get("price") or 0)) or Decimal("0.05"),
            trigger_price=Decimal(str(r.get("triggerprice") or 0)) or Decimal("0.05"),
            strategy=r.get("ordertag") or "external")
        now = datetime.now(timezone.utc)
        return Order(order_id=oid, request=req,
                     status=_STATUS.get(str(r.get("status", "")).lower(), OrderStatus.PENDING),
                     filled_qty=int(r.get("filledshares") or 0),
                     avg_fill_price=Decimal(str(r.get("averageprice") or 0)),
                     message=r.get("text", "") or "", created_at=now, updated_at=now)

    async def positions(self) -> list[Position]:
        rows = await self.s.call_with_reauth(self.c.positions)
        out = []
        for r in rows:
            net = int(r.get("netqty") or 0)
            c = Contract(symbol=r["tradingsymbol"], token=str(r["symboltoken"]), exchange=Exchange(r["exchange"]),
                         lot_size=int(r.get("lotsize") or 1))
            avg_key = "netprice" if net else "avgnetprice"
            out.append(Position(contract=c, product=Product(r["producttype"]), net_qty=net,
                                avg_price=Decimal(str(r.get(avg_key) or 0)),
                                realized_pnl=Decimal(str(r.get("realised") or 0)),
                                ltp=Decimal(str(r.get("ltp") or 0))))
        return out

    async def funds(self) -> Funds:
        d = await self.s.call_with_reauth(self.c.rms)
        return Funds(available_cash=Decimal(str(d.get("availablecash") or 0)),
                     used_margin=Decimal(str(d.get("utiliseddebits") or 0)),
                     realized_pnl=Decimal(str(d.get("m2mrealized") or 0)),
                     unrealized_pnl=Decimal(str(d.get("m2munrealized") or 0)))

    async def ltp(self, contract: Contract) -> Decimal:
        d = await self.s.call_with_reauth(self.c.ltp, contract.exchange.value, contract.symbol, contract.token)
        return Decimal(str(d["ltp"]))

    async def ltps(self, contracts: list[Contract]) -> dict[str, Decimal]:
        by_ex: dict[str, list[str]] = {}
        for c in contracts:
            by_ex.setdefault(c.exchange.value, []).append(c.token)
        keys = {(c.exchange.value, c.token): c.key for c in contracts}
        out: dict[str, Decimal] = {}
        rows = await self.s.call_with_reauth(self.c.quote_ltp, by_ex)
        for r in rows:
            k = keys.get((r.get("exchange"), str(r.get("symbolToken"))))
            if k:
                out[k] = Decimal(str(r["ltp"]))
        return out
