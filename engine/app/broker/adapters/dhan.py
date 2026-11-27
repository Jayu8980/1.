"""Dhan API v2 adapter. Docs: https://dhanhq.co/docs/v2/

Auth: access token generated in web.dhan.co (valid up to 30 days) is set on the server as
ENGINE_DHAN_ACCESS_TOKEN; "connect" verifies it via /profile. Instruments use Dhan
`securityId` from the Dhan scrip master -> Contract.ids["dhan"].
"""
from __future__ import annotations

from datetime import datetime, timedelta, timezone
from decimal import Decimal

from app.broker.tokens import IST, BrokerToken
from app.core.enums import Exchange, OrderStatus, OrderType, Product, Side
from app.core.errors import BrokerError
from app.core.models import Contract, Funds, Order, OrderRequest, Position

from .base import AuthKind, BrokerInfo, RestBroker

INFO = BrokerInfo("dhan", "Dhan", AuthKind.TOKEN, ("NSE", "BSE", "NFO", "BFO", "MCX"),
                  10, None, "https://dhanhq.co/docs/v2/", ("ENGINE_DHAN_CLIENT_ID", "ENGINE_DHAN_ACCESS_TOKEN"))

_SEG = {Exchange.NSE: "NSE_EQ", Exchange.BSE: "BSE_EQ", Exchange.NFO: "NSE_FNO", Exchange.BFO: "BSE_FNO"}
_SEG_R = {v: k for k, v in _SEG.items()}
_OT = {OrderType.MARKET: "MARKET", OrderType.LIMIT: "LIMIT", OrderType.SL_LIMIT: "STOP_LOSS",
       OrderType.SL_MARKET: "STOP_LOSS_MARKET"}
_OT_R = {v: k for k, v in _OT.items()}
_PR = {Product.DELIVERY: "CNC", Product.INTRADAY: "INTRADAY", Product.CARRYFORWARD: "MARGIN"}
_PR_R = {v: k for k, v in _PR.items()}
_ST = {"TRADED": OrderStatus.FILLED, "REJECTED": OrderStatus.REJECTED, "CANCELLED": OrderStatus.CANCELLED,
       "EXPIRED": OrderStatus.CANCELLED, "PENDING": OrderStatus.OPEN, "TRANSIT": OrderStatus.PENDING,
       "PART_TRADED": OrderStatus.OPEN}


def to_dhan_body(req: OrderRequest, client_id: str, security_id: str) -> dict:
    return {"dhanClientId": client_id, "correlationId": req.client_order_id[:25],
            "transactionType": req.side.value, "exchangeSegment": _SEG[req.contract.exchange],
            "productType": _PR[req.product], "orderType": _OT[req.order_type], "validity": "DAY",
            "securityId": security_id, "quantity": req.quantity, "disclosedQuantity": 0,
            "price": float(req.price or 0), "triggerPrice": float(req.trigger_price or 0), "afterMarketOrder": False}


class DhanBroker(RestBroker):
    info = INFO

    def __init__(self, tokens, client_id: str, access_token: str, orders_per_sec: int = 8, **kw):
        self.client_id, self._configured_token = client_id, access_token
        super().__init__(tokens, base_url="https://api.dhan.co/v2", orders_per_sec=orders_per_sec, **kw)

    def configured(self) -> bool:
        return bool(self.client_id and self._configured_token)

    async def complete_login(self, params: dict[str, str]) -> BrokerToken:
        """Verify the server-configured token and record its validity."""
        prof = await self.request("GET", "/profile", auth=False,
                                  headers={"access-token": self._configured_token})
        exp = datetime.now(IST) + timedelta(hours=24)
        if v := prof.get("tokenValidity"):
            try:
                exp = datetime.strptime(v, "%d/%m/%Y %H:%M").replace(tzinfo=IST)
            except ValueError:
                pass
        tok = BrokerToken(self._configured_token, exp.isoformat(), str(prof.get("dhanClientId", self.client_id)))
        self.tokens.put(INFO.id, tok)
        return tok

    def auth_headers(self) -> dict[str, str]:
        return {"access-token": self.token().access_token, "client-id": self.client_id,
                "Content-Type": "application/json"}

    def ok(self, body) -> bool:
        return not (isinstance(body, dict) and (body.get("status") == "failure" or "errorCode" in body))

    async def place_order(self, req: OrderRequest) -> Order:
        d = await self.request("POST", "/orders", order=True,
                               json=to_dhan_body(req, self.client_id, self.contract_id(req.contract)))
        oid = str(d["orderId"]); self._known[oid] = req
        now = datetime.now(timezone.utc)
        return Order(order_id=oid, request=req, status=_ST.get(d.get("orderStatus", ""), OrderStatus.PENDING),
                     message="sent", created_at=now, updated_at=now)

    async def cancel_order(self, order_id: str) -> Order:
        await self.request("DELETE", f"/orders/{order_id}", order=True)
        return await self._find(order_id)

    async def modify_order(self, order_id: str, *, price=None, trigger_price=None, quantity=None) -> Order:
        cur = await self._find(order_id)
        body = {"dhanClientId": self.client_id, "orderId": order_id, "orderType": _OT[cur.request.order_type],
                "legName": "", "quantity": quantity or cur.request.quantity, "validity": "DAY", "disclosedQuantity": 0,
                "price": float(price or cur.request.price or 0),
                "triggerPrice": float(trigger_price or cur.request.trigger_price or 0)}
        await self.request("PUT", f"/orders/{order_id}", order=True, json=body)
        return await self._find(order_id)

    async def _find(self, oid: str) -> Order:
        for o in await self.orders():
            if o.order_id == oid:
                return o
        raise BrokerError(f"order {oid} not found")

    async def orders(self) -> list[Order]:
        out = []
        for r in await self.request("GET", "/orders") or []:
            oid = str(r["orderId"])
            ex = _SEG_R.get(r.get("exchangeSegment", ""), Exchange.NSE)
            req = self._known.get(oid) or OrderRequest(
                contract=Contract(symbol=r.get("tradingSymbol", ""), token=str(r["securityId"]), exchange=ex,
                                  ids={"dhan": str(r["securityId"])}),
                side=Side(r["transactionType"]), quantity=int(r["quantity"]),
                order_type=_OT_R.get(r["orderType"], OrderType.MARKET), product=_PR_R.get(r["productType"], Product.INTRADAY),
                price=Decimal(str(r.get("price") or 0)) or Decimal("0.05"),
                trigger_price=Decimal(str(r.get("triggerPrice") or 0)) or Decimal("0.05"), strategy="external")
            now = datetime.now(timezone.utc)
            out.append(Order(order_id=oid, request=req, status=_ST.get(r.get("orderStatus", ""), OrderStatus.PENDING),
                             filled_qty=int(r.get("filledQty") or 0),
                             avg_fill_price=Decimal(str(r.get("averageTradedPrice") or 0)),
                             message=r.get("omsErrorDescription") or "", created_at=now, updated_at=now))
        return out

    async def positions(self) -> list[Position]:
        out = []
        for r in await self.request("GET", "/positions") or []:
            ex = _SEG_R.get(r.get("exchangeSegment", ""), Exchange.NSE)
            c = Contract(symbol=r.get("tradingSymbol", ""), token=str(r["securityId"]), exchange=ex,
                         ids={"dhan": str(r["securityId"])})
            net = int(r.get("netQty") or 0)
            avg = r.get("buyAvg") if net >= 0 else r.get("sellAvg")
            out.append(Position(contract=c, product=_PR_R.get(r.get("productType"), Product.INTRADAY), net_qty=net,
                                avg_price=Decimal(str(avg or 0)), realized_pnl=Decimal(str(r.get("realizedProfit") or 0))))
        return out

    async def funds(self) -> Funds:
        d = await self.request("GET", "/fundlimit") or {}
        # Dhan's field really is spelled "availabelBalance".
        avail = d.get("availabelBalance", d.get("availableBalance", 0))
        return Funds(available_cash=Decimal(str(avail or 0)), used_margin=Decimal(str(d.get("utilizedAmount") or 0)))

    async def ltp(self, contract: Contract) -> Decimal:
        sid = self.contract_id(contract); seg = _SEG[contract.exchange]
        d = await self.request("POST", "/marketfeed/ltp", json={seg: [int(sid)]})
        return Decimal(str(d["data"][seg][sid]["last_price"]))
