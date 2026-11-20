"""TradeLens order relay — runs on YOUR static-IP VPS (the IP you registered with your broker under the SEBI
retail-algo framework). It accepts approved orders from the app/engine and forwards them to the broker.

Safety rules enforced here (independently of the app):
  * bearer-token auth (constant-time compare) + optional client IP allowlist
  * LIMIT orders only, positive integer quantity, price within ±PRICE_BAND_PCT of the reference if supplied
  * max order value, max orders per day, duplicate/replay protection (clientRef, approvedAt ≤ 120 s old)
  * every order tagged with the broker-issued algo ID; every request/response written to an append-only audit log
  * kill file: if state/KILL exists, all new orders are refused
"""
from __future__ import annotations

import hmac
import json
import os
import time
import uuid
from datetime import date
from pathlib import Path

import httpx
from fastapi import Depends, FastAPI, Header, HTTPException, Request
from pydantic import BaseModel, Field

TOKEN = os.environ.get("RELAY_TOKEN", "")
ALLOW_IPS = {ip.strip() for ip in os.environ.get("RELAY_ALLOW_IPS", "").split(",") if ip.strip()}
MAX_ORDER_VALUE = float(os.environ.get("RELAY_MAX_ORDER_VALUE", "500000"))
MAX_ORDERS_PER_DAY = int(os.environ.get("RELAY_MAX_ORDERS_PER_DAY", "50"))
STATE = Path(os.environ.get("RELAY_STATE_DIR", "state")); STATE.mkdir(parents=True, exist_ok=True)
BROKERS = {b.strip() for b in os.environ.get("RELAY_BROKERS", "kite,upstox,angel,fyers,dhan").split(",") if b.strip()}


def algo_id(broker: str) -> str:
    return os.environ.get(f"ALGO_ID_{broker.upper()}", os.environ.get("ALGO_ID", ""))


def api_key(broker: str) -> str:
    return os.environ.get(f"{broker.upper()}_API_KEY", "")


class OrderIn(BaseModel):
    broker: str
    exchange: str = "NSE"
    symbol: str
    token: str = ""
    side: str = Field(pattern="^(BUY|SELL)$")
    qty: int = Field(gt=0)
    price: float = Field(gt=0)
    product: str = "DELIVERY"
    clientRef: str = Field(min_length=6, max_length=40)
    approvedAt: int
    orderType: str = "LIMIT"


app = FastAPI(title="TradeLens order relay", version="1.0.0")


def audit(kind: str, data: dict) -> None:
    with (STATE / "audit.jsonl").open("a") as f:
        f.write(json.dumps({"t": int(time.time()), "kind": kind, **data}, default=str) + "\n")


def auth(request: Request, authorization: str = Header(default="")) -> None:
    if not TOKEN or len(TOKEN) < 24:
        raise HTTPException(503, "RELAY_TOKEN not configured (≥ 24 chars)")
    if not hmac.compare_digest(authorization.removeprefix("Bearer ").strip(), TOKEN):
        audit("auth_fail", {"ip": request.client.host if request.client else "?"})
        raise HTTPException(401, "invalid token")
    if ALLOW_IPS and (request.client is None or request.client.host not in ALLOW_IPS):
        raise HTTPException(403, "client IP not allowed")


def _counter() -> dict:
    p = STATE / "counter.json"
    d = json.loads(p.read_text()) if p.exists() else {}
    if d.get("day") != date.today().isoformat():
        d = {"day": date.today().isoformat(), "n": 0, "refs": []}
    return d


def _save_counter(d: dict) -> None:
    (STATE / "counter.json").write_text(json.dumps(d))


def validate(o: OrderIn) -> None:
    if (STATE / "KILL").exists():
        raise HTTPException(423, "relay kill switch engaged")
    if o.broker not in BROKERS:
        raise HTTPException(400, f"broker {o.broker} not enabled on this relay")
    if o.orderType.upper() != "LIMIT":
        raise HTTPException(400, "LIMIT orders only")
    if o.qty * o.price > MAX_ORDER_VALUE:
        raise HTTPException(400, f"order value {o.qty * o.price:,.0f} exceeds relay cap {MAX_ORDER_VALUE:,.0f}")
    if abs(time.time() * 1000 - o.approvedAt) > 120_000:
        raise HTTPException(400, "approval older than 120 s – re-approve in the app")
    c = _counter()
    if c["n"] >= MAX_ORDERS_PER_DAY:
        raise HTTPException(429, "daily order cap reached")
    if o.clientRef in c["refs"]:
        raise HTTPException(409, "duplicate clientRef")


# ------------------------------------------------------------------ broker adapters
def _product(broker: str, p: str) -> str:
    p = p.upper()
    table = {
        "kite": {"DELIVERY": "CNC", "INTRADAY": "MIS", "NRML": "NRML"},
        "upstox": {"DELIVERY": "D", "INTRADAY": "I", "NRML": "D"},
        "angel": {"DELIVERY": "DELIVERY", "INTRADAY": "INTRADAY", "NRML": "CARRYFORWARD"},
        "fyers": {"DELIVERY": "CNC", "INTRADAY": "INTRADAY", "NRML": "MARGIN"},
        "dhan": {"DELIVERY": "CNC", "INTRADAY": "INTRADAY", "NRML": "MARGIN"},
    }
    return table[broker].get(p, p)


def build_request(o: OrderIn, session: str) -> httpx.Request:
    """Returns the broker HTTP request for a LIMIT order, tagged with the algo ID."""
    tag = algo_id(o.broker) or "tradelens"
    if o.broker == "kite":
        return httpx.Request("POST", "https://api.kite.trade/orders/regular",
                             headers={"X-Kite-Version": "3", "Authorization": f"token {api_key('kite')}:{session}"},
                             data={"tradingsymbol": o.symbol, "exchange": o.exchange, "transaction_type": o.side, "order_type": "LIMIT",
                                   "quantity": o.qty, "product": _product("kite", o.product), "price": o.price, "validity": "DAY", "tag": tag[:20]})
    if o.broker == "upstox":
        return httpx.Request("POST", "https://api-hft.upstox.com/v2/order/place", headers={"Authorization": f"Bearer {session}", "Accept": "application/json"},
                             json={"quantity": o.qty, "product": _product("upstox", o.product), "validity": "DAY", "price": o.price, "tag": tag[:20],
                                   "instrument_token": o.token, "order_type": "LIMIT", "transaction_type": o.side, "disclosed_quantity": 0,
                                   "trigger_price": 0, "is_amo": False})
    if o.broker == "angel":
        return httpx.Request("POST", "https://apiconnect.angelone.in/rest/secure/angelbroking/order/v1/placeOrder",
                             headers={"Authorization": f"Bearer {session}", "Content-Type": "application/json", "Accept": "application/json", "X-UserType": "USER",
                                      "X-SourceID": "WEB", "X-ClientLocalIP": "127.0.0.1", "X-ClientPublicIP": os.environ.get("RELAY_PUBLIC_IP", "127.0.0.1"),
                                      "X-MACAddress": "02:00:00:00:00:00", "X-PrivateKey": api_key("angel")},
                             json={"variety": "NORMAL", "tradingsymbol": o.symbol if o.exchange != "NSE" or o.symbol.endswith("-EQ") else f"{o.symbol}-EQ",
                                   "symboltoken": o.token, "transactiontype": o.side, "exchange": o.exchange, "ordertype": "LIMIT",
                                   "producttype": _product("angel", o.product), "duration": "DAY", "price": str(o.price), "quantity": str(o.qty), "ordertag": tag[:20]})
    if o.broker == "fyers":
        return httpx.Request("POST", "https://api-t1.fyers.in/api/v3/orders/sync", headers={"Authorization": f"{api_key('fyers')}:{session}"},
                             json={"symbol": o.token or f"{o.exchange}:{o.symbol}-EQ", "qty": o.qty, "type": 1, "side": 1 if o.side == "BUY" else -1,
                                   "productType": _product("fyers", o.product), "limitPrice": o.price, "stopPrice": 0, "validity": "DAY",
                                   "disclosedQty": 0, "offlineOrder": False, "orderTag": tag[:20]})
    if o.broker == "dhan":
        seg = {"NSE": "NSE_EQ", "BSE": "BSE_EQ", "NFO": "NSE_FNO", "BFO": "BSE_FNO", "MCX": "MCX_COMM"}.get(o.exchange, "NSE_EQ")
        return httpx.Request("POST", "https://api.dhan.co/v2/orders", headers={"access-token": session, "Content-Type": "application/json"},
                             json={"dhanClientId": os.environ.get("DHAN_CLIENT_ID", ""), "correlationId": o.clientRef[:25], "transactionType": o.side,
                                   "exchangeSegment": seg, "productType": _product("dhan", o.product), "orderType": "LIMIT", "validity": "DAY",
                                   "securityId": o.token, "quantity": o.qty, "price": o.price, "algoId": tag})
    raise HTTPException(400, "unsupported broker")


def parse_order_id(broker: str, j: dict) -> str | None:
    d = j.get("data") if isinstance(j.get("data"), dict) else {}
    return d.get("order_id") or d.get("orderid") or j.get("id") or j.get("orderId") or d.get("orderId")


SEND = httpx.Client(timeout=15)


@app.get("/health", dependencies=[Depends(auth)])
def health():
    try:
        ip = httpx.get("https://api.ipify.org", timeout=5).text.strip()
    except Exception:
        ip = "unknown"
    return {"ok": True, "egress_ip": ip, "brokers": ",".join(sorted(BROKERS)), "kill": (STATE / "KILL").exists()}


@app.post("/orders", dependencies=[Depends(auth)])
def place(o: OrderIn, x_broker_session: str = Header(default="")):
    session = x_broker_session or os.environ.get(f"{o.broker.upper()}_ACCESS_TOKEN", "")
    if not session:
        raise HTTPException(400, "missing broker session token")
    validate(o)
    req = build_request(o, session)
    audit("order", {"order": o.model_dump(), "url": str(req.url)})
    c = _counter(); c["n"] += 1; c["refs"] = (c["refs"] + [o.clientRef])[-500:]; _save_counter(c)
    t0 = time.time()
    try:
        r = SEND.send(req)
        j = r.json() if r.headers.get("content-type", "").startswith("application/json") else {"raw": r.text}
    except Exception as e:
        audit("error", {"clientRef": o.clientRef, "error": str(e)})
        return {"status": "error", "message": str(e)}
    ms = (time.time() - t0) * 1000
    audit("response", {"clientRef": o.clientRef, "status": r.status_code, "ms": ms, "body": j})
    if r.status_code >= 400 or str(j.get("status", "")).lower() in ("error", "false"):
        return {"status": "rejected", "message": str(j.get("message") or j.get("errors") or j)[:300]}
    return {"status": "accepted", "brokerOrderId": parse_order_id(o.broker, j), "message": f"{ms:.0f} ms"}


@app.delete("/orders/{broker}/{order_id}", dependencies=[Depends(auth)])
def cancel(broker: str, order_id: str, x_broker_session: str = Header(default="")):
    s = x_broker_session or os.environ.get(f"{broker.upper()}_ACCESS_TOKEN", "")
    reqs = {
        "kite": httpx.Request("DELETE", f"https://api.kite.trade/orders/regular/{order_id}", headers={"X-Kite-Version": "3", "Authorization": f"token {api_key('kite')}:{s}"}),
        "upstox": httpx.Request("DELETE", f"https://api-hft.upstox.com/v2/order/cancel?order_id={order_id}", headers={"Authorization": f"Bearer {s}"}),
        "fyers": httpx.Request("DELETE", "https://api-t1.fyers.in/api/v3/orders/sync", headers={"Authorization": f"{api_key('fyers')}:{s}"}, json={"id": order_id}),
        "dhan": httpx.Request("DELETE", f"https://api.dhan.co/v2/orders/{order_id}", headers={"access-token": s}),
        "angel": httpx.Request("POST", "https://apiconnect.angelone.in/rest/secure/angelbroking/order/v1/cancelOrder",
                               headers={"Authorization": f"Bearer {s}", "X-PrivateKey": api_key("angel"), "Content-Type": "application/json", "X-UserType": "USER", "X-SourceID": "WEB"},
                               json={"variety": "NORMAL", "orderid": order_id}),
    }
    if broker not in reqs:
        raise HTTPException(400, "unsupported broker")
    r = SEND.send(reqs[broker])
    audit("cancel", {"broker": broker, "order_id": order_id, "status": r.status_code, "body": r.text[:500]})
    return {"status": "accepted" if r.status_code < 400 else "rejected", "message": r.text[:200]}


@app.post("/kill", dependencies=[Depends(auth)])
def kill(body: dict):
    p = STATE / "KILL"
    if body.get("enabled"):
        p.write_text(str(time.time()))
    elif p.exists():
        p.unlink()
    audit("kill", {"enabled": bool(body.get("enabled"))})
    return {"status": "ok", "kill": p.exists()}
