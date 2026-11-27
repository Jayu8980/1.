"""Delta Exchange (crypto derivatives) adapter. Docs: https://docs.delta.exchange

Auth: API key + secret on the server; every private request carries
  signature = HMAC_SHA256(secret, METHOD + timestamp + path + ?query + body)  (hex)
with headers api-key / timestamp / signature. Requests older than ~5 s are rejected by Delta.
Delta API keys support IP whitelisting: whitelist the engine VM's static IP.

Instruments: product_symbol (e.g. "BTCUSD") from Contract.ids["delta"] or Contract.symbol.
Quantity = number of contracts; Contract.multiplier = contract_value (BTCUSD: 0.001 BTC).
Markets are 24x7, so the daily login/logout cycle does not apply.
"""
from __future__ import annotations

import hashlib
import hmac
import json as _json
import time as _time
from datetime import datetime, timezone
from decimal import Decimal
from typing import Any
from urllib.parse import urlencode

import httpx

from app.broker.tokens import BrokerToken
from app.core.enums import Exchange, Instrument, OrderStatus, OrderType, Product, Side
from app.core.errors import AuthError, BrokerError
from app.core.models import Contract, Funds, Order, OrderRequest, Position

from .base import AuthKind, BrokerInfo, RestBroker

INFO = BrokerInfo("delta", "Delta Exchange", AuthKind.API_KEY, ("CRYPTO_FUT", "CRYPTO_OPT"),
                  10, None, "https://docs.delta.exchange", ("ENGINE_DELTA_API_KEY", "ENGINE_DELTA_API_SECRET"))

REGIONS = {
    "india": "https://api.india.delta.exchange",
    "global": "https://api.delta.exchange",
    "india-testnet": "https://cdn-ind.testnet.deltaex.org",
    "global-testnet": "https://testnet-api.delta.exchange",
}
_AUTH_CODES = {"invalid_api_key", "expired_signature", "signature_mismatch", "unauthorized",
               "ip_not_whitelisted_for_api_key"}


def sign(secret: str, method: str, ts: str, path: str, query: str, body: str) -> str:
    msg = method + ts + path + (("?" + query) if query else "") + body
    return hmac.new(secret.encode(), msg.encode(), hashlib.sha256).hexdigest()


def delta_symbol(c: Contract) -> str:
    return c.ids.get("delta") or c.symbol


def to_delta_body(req: OrderRequest) -> dict:
    b: dict[str, Any] = {"product_symbol": delta_symbol(req.contract), "size": req.quantity,
                         "side": req.side.value.lower(), "time_in_force": "gtc",
                         "client_order_id": req.client_order_id[:32]}
    ot = req.order_type
    b["order_type"] = "limit_order" if ot in (OrderType.LIMIT, OrderType.SL_LIMIT) else "market_order"
    if req.price is not None and b["order_type"] == "limit_order":
        b["limit_price"] = str(req.price)
    if ot in (OrderType.SL_LIMIT, OrderType.SL_MARKET):
        b["stop_order_type"] = "stop_loss_order"
        b["stop_price"] = str(req.trigger_price)
    return b


def _status(r: dict) -> OrderStatus:
    st = r.get("state")
    if st == "closed":
        return OrderStatus.FILLED if int(r.get("unfilled_size") or 0) == 0 else OrderStatus.CANCELLED
    return {"cancelled": OrderStatus.CANCELLED, "open": OrderStatus.OPEN, "pending": OrderStatus.PENDING,
            "rejected": OrderStatus.REJECTED}.get(st, OrderStatus.PENDING)


class DeltaBroker(RestBroker):
    info = INFO

    def __init__(self, tokens, api_key: str, api_secret: str, region: str = "india", settle_asset: str = "USD",
                 orders_per_sec: int = 8, clock=_time.time, **kw):
        if region not in REGIONS:
            raise ValueError(f"ENGINE_DELTA_REGION must be one of {', '.join(REGIONS)}")
        self.api_key, self._secret, self.region, self.settle_asset = api_key, api_secret, region, settle_asset
        self._clock = clock
        self._product_ids: dict[str, int] = {}   # order_id -> product_id (needed for cancel/modify)
        super().__init__(tokens, base_url=REGIONS[region], orders_per_sec=orders_per_sec, **kw)

    def configured(self) -> bool:
        return bool(self.api_key and self._secret)

    def auth_headers(self) -> dict[str, str]:   # unused: Delta signs per request (see request())
        return {}

    async def complete_login(self, params: dict[str, str]) -> BrokerToken:
        """'Connect' = verify the keys (and IP whitelist) with a signed /v2/profile call."""
        prof = await self.request("GET", "/v2/profile", require_connected=False)
        tok = BrokerToken("hmac", "9999-12-31T00:00:00+05:30", str(prof.get("id") or prof.get("email") or ""),
                          {"region": self.region})
        self.tokens.put(INFO.id, tok)
        return tok

    async def request(self, method: str, path: str, *, order: bool = False, auth: bool = True,
                      json: Any = None, params: dict | None = None, require_connected: bool = True, **_) -> Any:
        if order:
            await self.limiter.acquire()
        if auth and require_connected:
            self.token()  # raises AuthError if never connected / disconnected
        query = urlencode(params or {})
        body = _json.dumps(json, separators=(",", ":")) if json is not None else ""
        h = {"Accept": "application/json", "Content-Type": "application/json", "User-Agent": "ai-trader-engine"}
        if auth:
            ts = str(int(self._clock()))
            h |= {"api-key": self.api_key, "timestamp": ts,
                  "signature": sign(self._secret, method, ts, path, query, body)}
        url = path + (("?" + query) if query else "")
        try:
            r = await self.http.request(method, url, content=body or None, headers=h)
        except httpx.HTTPError as e:
            raise BrokerError(f"Delta: network error on {path}: {e}") from e
        try:
            payload = r.json()
        except ValueError:
            raise BrokerError(f"Delta: non-JSON response ({r.status_code}) on {path}") from None
        if not payload.get("success", False):
            err = payload.get("error") or {}
            code = err.get("code") if isinstance(err, dict) else str(err)
            msg = f"Delta: {code}" + (f" {err.get('context')}" if isinstance(err, dict) and err.get("context") else "")
            if code == "ip_not_whitelisted_for_api_key":
                msg = "Delta: engine IP is not whitelisted for this API key — add the VM's static IP in Delta"
            if r.status_code == 401 or code in _AUTH_CODES:
                raise AuthError(msg, code)
            raise BrokerError(msg, code)
        return payload.get("result")

    # ------------------------------------------------------------------ trading
    async def place_order(self, req: OrderRequest) -> Order:
        if req.contract.exchange is not Exchange.DELTA:
            raise BrokerError(f"{req.contract.key} is not a Delta Exchange contract")
        d = await self.request("POST", "/v2/orders", order=True, json=to_delta_body(req))
        oid = str(d["id"]); self._known[oid] = req
        if d.get("product_id") is not None:
            self._product_ids[oid] = int(d["product_id"])
        return self._parse(d)

    async def _pid(self, order_id: str) -> int:
        if order_id not in self._product_ids:
            await self.orders()
        try:
            return self._product_ids[order_id]
        except KeyError:
            raise BrokerError(f"Delta order {order_id} not found") from None

    async def cancel_order(self, order_id: str) -> Order:
        d = await self.request("DELETE", "/v2/orders", order=True,
                               json={"id": int(order_id), "product_id": await self._pid(order_id)})
        return self._parse(d)

    async def modify_order(self, order_id: str, *, price=None, trigger_price=None, quantity=None) -> Order:
        body: dict[str, Any] = {"id": int(order_id), "product_id": await self._pid(order_id)}
        if price is not None:
            body["limit_price"] = str(price)
        if trigger_price is not None:
            body["stop_price"] = str(trigger_price)
        if quantity is not None:
            body["size"] = int(quantity)
        return self._parse(await self.request("PUT", "/v2/orders", order=True, json=body))

    def _parse(self, r: dict) -> Order:
        oid = str(r["id"])
        if r.get("product_id") is not None:
            self._product_ids[oid] = int(r["product_id"])
        req = self._known.get(oid) or OrderRequest(
            contract=Contract(symbol=r.get("product_symbol", ""), token=str(r.get("product_id", "")),
                              exchange=Exchange.DELTA, instrument=Instrument.FUTURE, tick_size=Decimal("0.5"),
                              ids={"delta": r.get("product_symbol", "")}),
            side=Side(r["side"].upper()), quantity=int(r["size"]),
            order_type=OrderType.LIMIT if r.get("order_type") == "limit_order" else OrderType.MARKET,
            product=Product.CARRYFORWARD, price=Decimal(str(r.get("limit_price") or 0)) or None,
            strategy="external")
        size, unfilled = int(r.get("size") or 0), int(r.get("unfilled_size") or 0)
        now = datetime.now(timezone.utc)
        return Order(order_id=oid, request=req, status=_status(r), filled_qty=size - unfilled,
                     avg_fill_price=Decimal(str(r.get("average_fill_price") or 0)),
                     message=r.get("state", ""), created_at=now, updated_at=now)

    async def orders(self) -> list[Order]:
        rows = await self.request("GET", "/v2/orders", params={"states": "open,pending"}) or []
        return [self._parse(r) for r in rows]

    async def positions(self) -> list[Position]:
        out = []
        for r in await self.request("GET", "/v2/positions/margined") or []:
            prod = r.get("product") or {}
            sym = r.get("product_symbol") or prod.get("symbol", "")
            c = Contract(symbol=sym, token=str(r.get("product_id", "")), exchange=Exchange.DELTA,
                         instrument=Instrument.OPTION if prod.get("contract_type", "").endswith("options") else Instrument.FUTURE,
                         multiplier=Decimal(str(prod.get("contract_value") or 1)), ids={"delta": sym})
            out.append(Position(contract=c, product=Product.CARRYFORWARD, net_qty=int(r.get("size") or 0),
                                avg_price=Decimal(str(r.get("entry_price") or 0)),
                                realized_pnl=Decimal(str(r.get("realized_pnl") or 0)),
                                ltp=Decimal(str(r.get("mark_price") or r.get("entry_price") or 0))))
        return out

    async def funds(self) -> Funds:
        rows = await self.request("GET", "/v2/wallet/balances") or []
        w = next((x for x in rows if x.get("asset_symbol") == self.settle_asset), None)
        if w is None:
            raise BrokerError(f"Delta: no {self.settle_asset} wallet (set ENGINE_DELTA_SETTLE_ASSET)")
        used = Decimal(str(w.get("order_margin") or 0)) + Decimal(str(w.get("position_margin") or 0))
        return Funds(available_cash=Decimal(str(w.get("available_balance") or 0)), used_margin=used)

    async def ltp(self, contract: Contract) -> Decimal:
        d = await self.request("GET", f"/v2/tickers/{delta_symbol(contract)}", auth=False)
        return Decimal(str(d.get("mark_price") or d["close"]))
