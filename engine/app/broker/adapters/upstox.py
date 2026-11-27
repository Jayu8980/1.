"""Upstox API v2 adapter. Docs: https://upstox.com/developer/api-documentation/

Login: browser -> /login/authorization/dialog -> redirect with ?code -> POST /login/authorization/token.
Token expires ~03:30 IST next day. Orders go to the HFT host. Instruments use `instrument_key`
(e.g. "NSE_EQ|INE062A01020") from the Upstox instrument master -> Contract.ids["upstox"].
"""
from __future__ import annotations

from datetime import datetime, time, timezone
from decimal import Decimal
from urllib.parse import urlencode

from app.broker.tokens import BrokerToken, next_expiry
from app.core.enums import Exchange, OrderStatus, OrderType, Product, Side
from app.core.errors import BrokerError
from app.core.models import Contract, Funds, Order, OrderRequest, Position

from .base import AuthKind, BrokerInfo, RestBroker

INFO = BrokerInfo("upstox", "Upstox", AuthKind.OAUTH, ("NSE", "BSE", "NFO", "BFO", "MCX"),
                  10, time(3, 30), "https://upstox.com/developer/api-documentation/",
                  ("ENGINE_UPSTOX_CLIENT_ID", "ENGINE_UPSTOX_CLIENT_SECRET"))
HFT = "https://api-hft.upstox.com/v2"

_OT = {OrderType.MARKET: "MARKET", OrderType.LIMIT: "LIMIT", OrderType.SL_LIMIT: "SL", OrderType.SL_MARKET: "SL-M"}
_OT_R = {v: k for k, v in _OT.items()}
# Upstox: D = delivery (cash) / carry-forward (F&O), I = intraday.
_PR = {Product.DELIVERY: "D", Product.INTRADAY: "I", Product.CARRYFORWARD: "D"}
_ST = {"complete": OrderStatus.FILLED, "rejected": OrderStatus.REJECTED, "cancelled": OrderStatus.CANCELLED,
       "open": OrderStatus.OPEN, "trigger pending": OrderStatus.PENDING}
_EX = {"NSE_EQ": Exchange.NSE, "BSE_EQ": Exchange.BSE, "NSE_FO": Exchange.NFO, "BSE_FO": Exchange.BFO}


def to_upstox_body(req: OrderRequest, instrument_key: str) -> dict:
    return {"quantity": req.quantity, "product": _PR[req.product], "validity": "DAY",
            "price": float(req.price or 0), "tag": (req.tag or req.strategy)[:20], "instrument_token": instrument_key,
            "order_type": _OT[req.order_type], "transaction_type": req.side.value, "disclosed_quantity": 0,
            "trigger_price": float(req.trigger_price or 0), "is_amo": False}


class UpstoxBroker(RestBroker):
    info = INFO

    def __init__(self, tokens, client_id: str, client_secret: str, redirect_uri: str, orders_per_sec: int = 8, **kw):
        self.client_id, self._secret, self.redirect_uri = client_id, client_secret, redirect_uri
        super().__init__(tokens, base_url="https://api.upstox.com/v2", orders_per_sec=orders_per_sec, **kw)

    def configured(self) -> bool:
        return bool(self.client_id and self._secret and self.redirect_uri)

    def login_url(self, state: str) -> str:
        return "https://api.upstox.com/v2/login/authorization/dialog?" + urlencode(
            {"response_type": "code", "client_id": self.client_id, "redirect_uri": self.redirect_uri, "state": state})

    async def complete_login(self, params: dict[str, str]) -> BrokerToken:
        code = params.get("code")
        if not code:
            raise BrokerError("Upstox login was not completed")
        d = await self.request("POST", "/login/authorization/token", auth=False,
                               headers={"Content-Type": "application/x-www-form-urlencoded"},
                               data={"code": code, "client_id": self.client_id, "client_secret": self._secret,
                                     "redirect_uri": self.redirect_uri, "grant_type": "authorization_code"})
        tok = BrokerToken(d["access_token"], next_expiry(INFO.token_expiry_ist).isoformat(), d.get("user_id", ""))
        self.tokens.put(INFO.id, tok)
        return tok

    def auth_headers(self) -> dict[str, str]:
        return {"Authorization": f"Bearer {self.token().access_token}"}

    def ok(self, body) -> bool:
        # token endpoint returns a bare object; everything else wraps in {"status": "success", "data": ...}
        return isinstance(body, dict) and (body.get("status") == "success" or "access_token" in body)

    def unwrap(self, body):
        return body["data"] if "data" in body else body

    def error_message(self, body) -> str:
        errs = body.get("errors") if isinstance(body, dict) else None
        return errs[0].get("message", "") if errs else super().error_message(body)

    async def place_order(self, req: OrderRequest) -> Order:
        d = await self.request("POST", "/order/place", order=True, base_url=HFT,
                               json=to_upstox_body(req, self.contract_id(req.contract)))
        oid = d["order_id"]; self._known[oid] = req
        now = datetime.now(timezone.utc)
        return Order(order_id=oid, request=req, status=OrderStatus.PENDING, message="sent", created_at=now, updated_at=now)

    async def cancel_order(self, order_id: str) -> Order:
        await self.request("DELETE", "/order/cancel", order=True, base_url=HFT, params={"order_id": order_id})
        return await self._find(order_id)

    async def modify_order(self, order_id: str, *, price=None, trigger_price=None, quantity=None) -> Order:
        cur = await self._find(order_id)
        body = {"order_id": order_id, "validity": "DAY", "order_type": _OT[cur.request.order_type],
                "quantity": quantity or cur.request.quantity, "price": float(price or cur.request.price or 0),
                "trigger_price": float(trigger_price or cur.request.trigger_price or 0), "disclosed_quantity": 0}
        await self.request("PUT", "/order/modify", order=True, base_url=HFT, json=body)
        return await self._find(order_id)

    async def _find(self, oid: str) -> Order:
        for o in await self.orders():
            if o.order_id == oid:
                return o
        raise BrokerError(f"order {oid} not found")

    async def orders(self) -> list[Order]:
        out = []
        for r in await self.request("GET", "/order/retrieve-all") or []:
            oid = r["order_id"]
            req = self._known.get(oid) or OrderRequest(
                contract=Contract(symbol=r["trading_symbol"], token=r["instrument_token"],
                                  exchange=_EX.get(r.get("exchange", ""), Exchange.NSE),
                                  ids={"upstox": r["instrument_token"]}),
                side=Side(r["transaction_type"]), quantity=int(r["quantity"]),
                order_type=_OT_R.get(r["order_type"], OrderType.MARKET),
                product=Product.INTRADAY if r.get("product") == "I" else Product.DELIVERY,
                price=Decimal(str(r.get("price") or 0)) or Decimal("0.05"),
                trigger_price=Decimal(str(r.get("trigger_price") or 0)) or Decimal("0.05"), strategy=r.get("tag") or "external")
            now = datetime.now(timezone.utc)
            out.append(Order(order_id=oid, request=req, status=_ST.get(str(r.get("status", "")).lower(), OrderStatus.PENDING),
                             filled_qty=int(r.get("filled_quantity") or 0),
                             avg_fill_price=Decimal(str(r.get("average_price") or 0)),
                             message=r.get("status_message") or "", created_at=now, updated_at=now))
        return out

    async def positions(self) -> list[Position]:
        out = []
        for r in await self.request("GET", "/portfolio/short-term-positions") or []:
            c = Contract(symbol=r["trading_symbol"], token=r["instrument_token"],
                         exchange=_EX.get(r.get("exchange", ""), Exchange.NSE), ids={"upstox": r["instrument_token"]})
            out.append(Position(contract=c, product=Product.INTRADAY if r.get("product") == "I" else Product.DELIVERY,
                                net_qty=int(r["quantity"]), avg_price=Decimal(str(r.get("average_price") or 0)),
                                realized_pnl=Decimal(str(r.get("realised") or 0)), ltp=Decimal(str(r.get("last_price") or 0))))
        return out

    async def funds(self) -> Funds:
        eq = ((await self.request("GET", "/user/get-funds-and-margin", params={"segment": "SEC"})) or {}).get("equity", {})
        return Funds(available_cash=Decimal(str(eq.get("available_margin") or 0)),
                     used_margin=Decimal(str(eq.get("used_margin") or 0)))

    async def ltp(self, contract: Contract) -> Decimal:
        key = self.contract_id(contract)
        d = await self.request("GET", "/market-quote/ltp", params={"instrument_key": key})
        # response is keyed "NSE_EQ:SBIN"; take the single entry
        return Decimal(str(next(iter(d.values()))["last_price"]))
