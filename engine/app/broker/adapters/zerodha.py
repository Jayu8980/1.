"""Zerodha Kite Connect v3 adapter. Docs: https://kite.trade/docs/connect/v3/

Login: browser -> kite.zerodha.com/connect/login -> redirect to engine with request_token
-> POST /session/token with checksum = sha256(api_key + request_token + api_secret).
Access token is valid until ~06:00 IST next day.
"""
from __future__ import annotations

import hashlib
from datetime import datetime, time, timezone
from decimal import Decimal
from urllib.parse import urlencode

from app.broker.tokens import BrokerToken, next_expiry
from app.core.enums import Exchange, Instrument, OrderStatus, OrderType, Product, Side
from app.core.errors import BrokerError
from app.core.models import Contract, Funds, Order, OrderRequest, Position

from .base import AuthKind, BrokerInfo, RestBroker

INFO = BrokerInfo("zerodha", "Zerodha (Kite Connect)", AuthKind.OAUTH, ("NSE", "BSE", "NFO", "BFO", "MCX"),
                  10, time(6, 0), "https://kite.trade/docs/connect/v3/",
                  ("ENGINE_ZERODHA_API_KEY", "ENGINE_ZERODHA_API_SECRET"))

_OT = {OrderType.MARKET: "MARKET", OrderType.LIMIT: "LIMIT", OrderType.SL_LIMIT: "SL", OrderType.SL_MARKET: "SL-M"}
_OT_R = {v: k for k, v in _OT.items()}
_PR = {Product.DELIVERY: "CNC", Product.INTRADAY: "MIS", Product.CARRYFORWARD: "NRML"}
_PR_R = {v: k for k, v in _PR.items()}
_ST = {"COMPLETE": OrderStatus.FILLED, "REJECTED": OrderStatus.REJECTED, "CANCELLED": OrderStatus.CANCELLED,
       "OPEN": OrderStatus.OPEN, "TRIGGER PENDING": OrderStatus.PENDING}


def kite_symbol(c: Contract) -> str:
    return c.ids.get("zerodha") or c.symbol.removesuffix("-EQ")


def to_kite_params(req: OrderRequest) -> dict:
    p = {"tradingsymbol": kite_symbol(req.contract), "exchange": req.contract.exchange.value,
         "transaction_type": req.side.value, "order_type": _OT[req.order_type], "quantity": req.quantity,
         "product": _PR[req.product], "validity": "DAY", "tag": (req.tag or req.strategy)[:20]}
    if req.price is not None:
        p["price"] = str(req.price)
    if req.trigger_price is not None:
        p["trigger_price"] = str(req.trigger_price)
    return p


class ZerodhaBroker(RestBroker):
    info = INFO

    def __init__(self, tokens, api_key: str, api_secret: str, orders_per_sec: int = 8, **kw):
        self.api_key, self._secret = api_key, api_secret
        super().__init__(tokens, base_url="https://api.kite.trade", orders_per_sec=orders_per_sec, **kw)

    def configured(self) -> bool:
        return bool(self.api_key and self._secret)

    def login_url(self, state: str) -> str:
        # Kite passes `redirect_params` back verbatim; we use it to carry our CSRF state.
        return "https://kite.zerodha.com/connect/login?" + urlencode(
            {"v": "3", "api_key": self.api_key, "redirect_params": urlencode({"state": state})})

    async def complete_login(self, params: dict[str, str]) -> BrokerToken:
        rt = params.get("request_token")
        if not rt or params.get("status", "success") != "success":
            raise BrokerError("Zerodha login was not completed")
        checksum = hashlib.sha256((self.api_key + rt + self._secret).encode()).hexdigest()
        d = await self.request("POST", "/session/token", auth=False, headers={"X-Kite-Version": "3"},
                               data={"api_key": self.api_key, "request_token": rt, "checksum": checksum})
        tok = BrokerToken(d["access_token"], next_expiry(INFO.token_expiry_ist).isoformat(), d.get("user_id", ""))
        self.tokens.put(INFO.id, tok)
        return tok

    def auth_headers(self) -> dict[str, str]:
        return {"X-Kite-Version": "3", "Authorization": f"token {self.api_key}:{self.token().access_token}"}

    def ok(self, body) -> bool:
        return isinstance(body, dict) and body.get("status") == "success"

    def unwrap(self, body):
        return body.get("data")

    async def place_order(self, req: OrderRequest) -> Order:
        d = await self.request("POST", "/orders/regular", order=True, data=to_kite_params(req))
        oid = d["order_id"]; self._known[oid] = req
        now = datetime.now(timezone.utc)
        return Order(order_id=oid, request=req, status=OrderStatus.PENDING, message="sent", created_at=now, updated_at=now)

    async def cancel_order(self, order_id: str) -> Order:
        await self.request("DELETE", f"/orders/regular/{order_id}", order=True)
        return await self._find(order_id)

    async def modify_order(self, order_id: str, *, price=None, trigger_price=None, quantity=None) -> Order:
        upd = {k: str(v) for k, v in dict(price=price, trigger_price=trigger_price, quantity=quantity).items() if v is not None}
        await self.request("PUT", f"/orders/regular/{order_id}", order=True, data=upd)
        return await self._find(order_id)

    async def _find(self, oid: str) -> Order:
        for o in await self.orders():
            if o.order_id == oid:
                return o
        raise BrokerError(f"order {oid} not found")

    async def orders(self) -> list[Order]:
        out = []
        for r in await self.request("GET", "/orders") or []:
            oid = r["order_id"]
            req = self._known.get(oid) or OrderRequest(
                contract=Contract(symbol=r["tradingsymbol"], token=str(r.get("instrument_token", "")),
                                  exchange=Exchange(r["exchange"])),
                side=Side(r["transaction_type"]), quantity=int(r["quantity"]),
                order_type=_OT_R.get(r["order_type"], OrderType.MARKET), product=_PR_R.get(r["product"], Product.INTRADAY),
                price=Decimal(str(r.get("price") or 0)) or Decimal("0.05"),
                trigger_price=Decimal(str(r.get("trigger_price") or 0)) or Decimal("0.05"), strategy=r.get("tag") or "external")
            now = datetime.now(timezone.utc)
            out.append(Order(order_id=oid, request=req, status=_ST.get(r.get("status", ""), OrderStatus.PENDING),
                             filled_qty=int(r.get("filled_quantity") or 0),
                             avg_fill_price=Decimal(str(r.get("average_price") or 0)),
                             message=r.get("status_message") or "", created_at=now, updated_at=now))
        return out

    async def positions(self) -> list[Position]:
        d = await self.request("GET", "/portfolio/positions")
        out = []
        for r in d.get("net", []):
            c = Contract(symbol=r["tradingsymbol"], token=str(r.get("instrument_token", "")), exchange=Exchange(r["exchange"]),
                         instrument=Instrument.EQUITY if r["exchange"] in ("NSE", "BSE") else Instrument.FUTURE)
            out.append(Position(contract=c, product=_PR_R.get(r["product"], Product.INTRADAY), net_qty=int(r["quantity"]),
                                avg_price=Decimal(str(r.get("average_price") or 0)),
                                realized_pnl=Decimal(str(r.get("realised") or 0)), ltp=Decimal(str(r.get("last_price") or 0))))
        return out

    async def funds(self) -> Funds:
        eq = (await self.request("GET", "/user/margins/equity")) or {}
        return Funds(available_cash=Decimal(str(eq.get("net") or 0)),
                     used_margin=Decimal(str((eq.get("utilised") or {}).get("debits") or 0)))

    async def ltp(self, contract: Contract) -> Decimal:
        key = f"{contract.exchange.value}:{kite_symbol(contract)}"
        d = await self.request("GET", "/quote/ltp", params={"i": key})
        return Decimal(str(d[key]["last_price"]))
