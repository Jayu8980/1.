"""Fyers API v3 adapter. Docs: https://myapi.fyers.in/docsv3

Login: browser -> /generate-authcode -> redirect with ?auth_code -> POST /validate-authcode with
appIdHash = sha256("app_id:secret"). Auth header is "app_id:access_token". Token valid for the day.
Symbols look like "NSE:SBIN-EQ" / "NSE:NIFTY26DECFUT" -> Contract.ids["fyers"] (falls back to exchange:symbol).
"""
from __future__ import annotations

import hashlib
from datetime import datetime, time, timezone
from decimal import Decimal
from urllib.parse import urlencode

from app.broker.tokens import BrokerToken, next_expiry
from app.core.enums import Exchange, OrderStatus, OrderType, Product, Side
from app.core.errors import BrokerError
from app.core.models import Contract, Funds, Order, OrderRequest, Position

from .base import AuthKind, BrokerInfo, RestBroker

INFO = BrokerInfo("fyers", "Fyers", AuthKind.OAUTH, ("NSE", "BSE", "NFO", "BFO", "MCX"),
                  10, time(6, 0), "https://myapi.fyers.in/docsv3", ("ENGINE_FYERS_APP_ID", "ENGINE_FYERS_SECRET"))
BASE = "https://api-t1.fyers.in/api/v3"

_OT = {OrderType.LIMIT: 1, OrderType.MARKET: 2, OrderType.SL_MARKET: 3, OrderType.SL_LIMIT: 4}
_OT_R = {v: k for k, v in _OT.items()}
_PR = {Product.DELIVERY: "CNC", Product.INTRADAY: "INTRADAY", Product.CARRYFORWARD: "MARGIN"}
_PR_R = {v: k for k, v in _PR.items()}
_ST = {1: OrderStatus.CANCELLED, 2: OrderStatus.FILLED, 4: OrderStatus.PENDING, 5: OrderStatus.REJECTED,
       6: OrderStatus.OPEN}
# Fyers uses "NSE"/"BSE" prefixes for both cash and F&O symbols.
_FX = {Exchange.NSE: "NSE", Exchange.NFO: "NSE", Exchange.BSE: "BSE", Exchange.BFO: "BSE"}


def fyers_symbol(c: Contract) -> str:
    return c.ids.get("fyers") or f"{_FX[c.exchange]}:{c.symbol}"


def _exchange_of(sym: str) -> Exchange:
    pre, _, rest = sym.partition(":")
    fno = rest.endswith(("FUT", "CE", "PE")) and not rest.endswith("-EQ")
    return {"NSE": Exchange.NFO if fno else Exchange.NSE, "BSE": Exchange.BFO if fno else Exchange.BSE}.get(pre, Exchange.NSE)


def to_fyers_body(req: OrderRequest) -> dict:
    return {"symbol": fyers_symbol(req.contract), "qty": req.quantity, "type": _OT[req.order_type],
            "side": 1 if req.side is Side.BUY else -1, "productType": _PR[req.product],
            "limitPrice": float(req.price or 0), "stopPrice": float(req.trigger_price or 0), "validity": "DAY",
            "disclosedQty": 0, "offlineOrder": False, "stopLoss": 0, "takeProfit": 0,
            "orderTag": "".join(ch for ch in (req.tag or req.strategy) if ch.isalnum())[:20]}


class FyersBroker(RestBroker):
    info = INFO

    def __init__(self, tokens, app_id: str, secret: str, redirect_uri: str, orders_per_sec: int = 8, **kw):
        self.app_id, self._secret, self.redirect_uri = app_id, secret, redirect_uri
        super().__init__(tokens, base_url=BASE, orders_per_sec=orders_per_sec, **kw)

    def configured(self) -> bool:
        return bool(self.app_id and self._secret and self.redirect_uri)

    def login_url(self, state: str) -> str:
        return f"{BASE}/generate-authcode?" + urlencode(
            {"client_id": self.app_id, "redirect_uri": self.redirect_uri, "response_type": "code", "state": state})

    async def complete_login(self, params: dict[str, str]) -> BrokerToken:
        code = params.get("auth_code") or params.get("code")
        if not code:
            raise BrokerError("Fyers login was not completed")
        app_hash = hashlib.sha256(f"{self.app_id}:{self._secret}".encode()).hexdigest()
        d = await self.request("POST", "/validate-authcode", auth=False,
                               json={"grant_type": "authorization_code", "appIdHash": app_hash, "code": code})
        tok = BrokerToken(d["access_token"], next_expiry(INFO.token_expiry_ist).isoformat())
        self.tokens.put(INFO.id, tok)
        return tok

    def auth_headers(self) -> dict[str, str]:
        return {"Authorization": f"{self.app_id}:{self.token().access_token}"}

    def ok(self, body) -> bool:
        return isinstance(body, dict) and body.get("s") == "ok"

    def error_code(self, body):
        return str(body.get("code")) if isinstance(body, dict) and "code" in body else None

    async def place_order(self, req: OrderRequest) -> Order:
        d = await self.request("POST", "/orders/sync", order=True, json=to_fyers_body(req))
        oid = str(d["id"]); self._known[oid] = req
        now = datetime.now(timezone.utc)
        return Order(order_id=oid, request=req, status=OrderStatus.PENDING, message=d.get("message", "sent"),
                     created_at=now, updated_at=now)

    async def cancel_order(self, order_id: str) -> Order:
        await self.request("DELETE", "/orders/sync", order=True, json={"id": order_id})
        return await self._find(order_id)

    async def modify_order(self, order_id: str, *, price=None, trigger_price=None, quantity=None) -> Order:
        cur = await self._find(order_id)
        body = {"id": order_id, "type": _OT[cur.request.order_type], "qty": quantity or cur.request.quantity,
                "limitPrice": float(price or cur.request.price or 0),
                "stopPrice": float(trigger_price or cur.request.trigger_price or 0)}
        await self.request("PATCH", "/orders/sync", order=True, json=body)
        return await self._find(order_id)

    async def _find(self, oid: str) -> Order:
        for o in await self.orders():
            if o.order_id == oid:
                return o
        raise BrokerError(f"order {oid} not found")

    async def orders(self) -> list[Order]:
        out = []
        for r in (await self.request("GET", "/orders")).get("orderBook", []):
            oid = str(r["id"])
            req = self._known.get(oid) or OrderRequest(
                contract=Contract(symbol=r["symbol"].partition(":")[2], token=str(r.get("fyToken", "")),
                                  exchange=_exchange_of(r["symbol"]), ids={"fyers": r["symbol"]}),
                side=Side.BUY if r["side"] == 1 else Side.SELL, quantity=int(r["qty"]),
                order_type=_OT_R.get(r["type"], OrderType.MARKET), product=_PR_R.get(r["productType"], Product.INTRADAY),
                price=Decimal(str(r.get("limitPrice") or 0)) or Decimal("0.05"),
                trigger_price=Decimal(str(r.get("stopPrice") or 0)) or Decimal("0.05"), strategy=r.get("orderTag") or "external")
            now = datetime.now(timezone.utc)
            out.append(Order(order_id=oid, request=req, status=_ST.get(int(r.get("status", 4)), OrderStatus.PENDING),
                             filled_qty=int(r.get("filledQty") or 0), avg_fill_price=Decimal(str(r.get("tradedPrice") or 0)),
                             message=r.get("message") or "", created_at=now, updated_at=now))
        return out

    async def positions(self) -> list[Position]:
        out = []
        for r in (await self.request("GET", "/positions")).get("netPositions", []):
            c = Contract(symbol=r["symbol"].partition(":")[2], token=str(r.get("fyToken", "")),
                         exchange=_exchange_of(r["symbol"]), ids={"fyers": r["symbol"]})
            out.append(Position(contract=c, product=_PR_R.get(r.get("productType"), Product.INTRADAY),
                                net_qty=int(r.get("netQty") or 0), avg_price=Decimal(str(r.get("netAvg") or 0)),
                                realized_pnl=Decimal(str(r.get("realized_profit") or 0)), ltp=Decimal(str(r.get("ltp") or 0))))
        return out

    async def funds(self) -> Funds:
        rows = {f.get("title"): f for f in (await self.request("GET", "/funds")).get("fund_limit", [])}
        return Funds(available_cash=Decimal(str(rows.get("Available Balance", {}).get("equityAmount") or 0)),
                     used_margin=Decimal(str(rows.get("Utilized Amount", {}).get("equityAmount") or 0)))

    async def ltp(self, contract: Contract) -> Decimal:
        sym = fyers_symbol(contract)
        d = await self.request("GET", "/data/quotes", params={"symbols": sym}, base_url="https://api-t1.fyers.in")
        return Decimal(str(d["d"][0]["v"]["lp"]))
