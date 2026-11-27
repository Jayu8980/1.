"""Every adapter against a mocked broker API (respx). Verifies login exchange, auth headers,
order-parameter mapping and response parsing. No network."""
import hashlib
import json
from datetime import datetime, timedelta
from decimal import Decimal as D
from urllib.parse import parse_qs, urlparse

import httpx
import pytest
import respx

from app.broker.adapters.dhan import DhanBroker
from app.broker.adapters.fyers import FyersBroker
from app.broker.adapters.upstox import UpstoxBroker
from app.broker.adapters.zerodha import ZerodhaBroker
from app.broker.tokens import IST, BrokerToken, TokenStore, next_expiry
from app.core.enums import Exchange, OrderStatus, OrderType, Product, Side
from app.core.errors import AuthError, BrokerError
from app.core.models import Contract, OrderRequest

SBIN = Contract(symbol="SBIN-EQ", token="3045", exchange=Exchange.NSE,
                ids={"upstox": "NSE_EQ|INE062A01020", "dhan": "3045", "fyers": "NSE:SBIN-EQ"})


def limit_buy(qty=5, price="800"):
    return OrderRequest(contract=SBIN, side=Side.BUY, quantity=qty, order_type=OrderType.LIMIT,
                        price=D(price), product=Product.INTRADAY, strategy="trend")


@pytest.fixture
def tokens(tmp_path):
    return TokenStore(tmp_path / "tok.json")


def body(call):
    return json.loads(call.request.content)


def form(call):
    return {k: v[0] for k, v in parse_qs(call.request.content.decode()).items()}


# ------------------------------------------------------------------ token store
def test_token_store_perms_and_expiry(tokens, tmp_path):
    tokens.put("x", BrokerToken("t", (datetime.now(IST) + timedelta(hours=1)).isoformat()))
    assert oct((tmp_path / "tok.json").stat().st_mode)[-3:] == "600"
    assert not tokens.get("x").expired()
    tokens.put("y", BrokerToken("t", (datetime.now(IST) - timedelta(seconds=1)).isoformat()))
    assert tokens.get("y").expired()
    tokens.delete("x")
    assert tokens.get("x") is None


def test_next_expiry_rolls_to_tomorrow():
    from datetime import time
    now = datetime(2026, 11, 27, 9, 0, tzinfo=IST)
    assert next_expiry(time(6, 0), now).day == 28
    assert next_expiry(time(10, 0), now).day == 27


# ------------------------------------------------------------------ zerodha
async def test_zerodha_login_and_order(tokens):
    z = ZerodhaBroker(tokens, "kkey", "ksecret")
    url = urlparse(z.login_url("st123"))
    q = parse_qs(url.query)
    assert url.netloc == "kite.zerodha.com" and q["api_key"] == ["kkey"] and "state=st123" in q["redirect_params"][0]
    with respx.mock(base_url="https://api.kite.trade") as m:
        sess = m.post("/session/token").mock(return_value=httpx.Response(200, json={
            "status": "success", "data": {"access_token": "AT", "user_id": "AB1234"}}))
        tok = await z.complete_login({"request_token": "RT", "status": "success", "state": "st123"})
        f = form(sess.calls.last)
        assert f["checksum"] == hashlib.sha256(b"kkeyRTksecret").hexdigest()
        assert tok.account == "AB1234" and tokens.get("zerodha").access_token == "AT"

        place = m.post("/orders/regular").mock(return_value=httpx.Response(200, json={
            "status": "success", "data": {"order_id": "Z1"}}))
        o = await z.place_order(limit_buy())
        req = place.calls.last.request
        assert req.headers["Authorization"] == "token kkey:AT" and req.headers["X-Kite-Version"] == "3"
        f = form(place.calls.last)
        assert f == {"tradingsymbol": "SBIN", "exchange": "NSE", "transaction_type": "BUY", "order_type": "LIMIT",
                     "quantity": "5", "product": "MIS", "validity": "DAY", "tag": "trend", "price": "800"}
        assert o.order_id == "Z1"

        m.get("/orders").mock(return_value=httpx.Response(200, json={"status": "success", "data": [{
            "order_id": "Z1", "tradingsymbol": "SBIN", "exchange": "NSE", "transaction_type": "BUY", "quantity": 5,
            "order_type": "LIMIT", "product": "MIS", "price": 800, "status": "COMPLETE", "filled_quantity": 5,
            "average_price": 799.5}]}))
        [o] = await z.orders()
        assert o.status is OrderStatus.FILLED and o.avg_fill_price == D("799.5")


async def test_zerodha_errors(tokens):
    z = ZerodhaBroker(tokens, "k", "s")
    with pytest.raises(AuthError):        # not connected
        z.auth_headers()
    tokens.put("zerodha", BrokerToken("AT", next_expiry(ZerodhaBroker.info.token_expiry_ist).isoformat()))
    with respx.mock(base_url="https://api.kite.trade") as m:
        m.post("/orders/regular").mock(return_value=httpx.Response(400, json={
            "status": "error", "message": "Insufficient funds", "error_type": "MarginException"}))
        with pytest.raises(BrokerError) as e:
            await z.place_order(limit_buy())
        assert "Insufficient" in str(e.value) and e.value.errorcode == "MarginException"
        m.get("/orders").mock(return_value=httpx.Response(403, json={"status": "error", "message": "TokenException"}))
        with pytest.raises(AuthError):
            await z.orders()


# ------------------------------------------------------------------ upstox
async def test_upstox_login_and_order(tokens):
    u = UpstoxBroker(tokens, "cid", "csec", "https://vm/api/v1/brokers/upstox/callback")
    q = parse_qs(urlparse(u.login_url("S")).query)
    assert q["client_id"] == ["cid"] and q["state"] == ["S"] and q["redirect_uri"][0].endswith("/upstox/callback")
    with respx.mock() as m:
        m.post("https://api.upstox.com/v2/login/authorization/token").mock(
            return_value=httpx.Response(200, json={"access_token": "UT", "user_id": "U1"}))
        await u.complete_login({"code": "C", "state": "S"})
        place = m.post("https://api-hft.upstox.com/v2/order/place").mock(
            return_value=httpx.Response(200, json={"status": "success", "data": {"order_id": "U9"}}))
        o = await u.place_order(limit_buy())
        b = body(place.calls.last)
        assert place.calls.last.request.headers["Authorization"] == "Bearer UT"
        assert b["instrument_token"] == "NSE_EQ|INE062A01020" and b["product"] == "I" and b["order_type"] == "LIMIT"
        assert b["price"] == 800.0 and o.order_id == "U9"


async def test_upstox_requires_instrument_key(tokens):
    u = UpstoxBroker(tokens, "c", "s", "https://vm/cb")
    tokens.put("upstox", BrokerToken("UT", next_expiry(u.info.token_expiry_ist).isoformat()))
    bare = Contract(symbol="INFY-EQ", token="1594", exchange=Exchange.NSE)
    with pytest.raises(BrokerError, match="instrument id"):
        await u.place_order(OrderRequest(contract=bare, side=Side.BUY, quantity=1, order_type=OrderType.MARKET))


# ------------------------------------------------------------------ dhan
async def test_dhan_connect_and_order(tokens):
    d = DhanBroker(tokens, "1000001", "DTOKEN")
    with respx.mock(base_url="https://api.dhan.co/v2") as m:
        prof = m.get("/profile").mock(return_value=httpx.Response(200, json={
            "dhanClientId": "1000001", "tokenValidity": "30/12/2099 15:37"}))
        tok = await d.complete_login({})
        assert prof.calls.last.request.headers["access-token"] == "DTOKEN"
        assert tok.expires_at.startswith("2099-12-30")
        place = m.post("/orders").mock(return_value=httpx.Response(200, json={"orderId": "112111182198", "orderStatus": "PENDING"}))
        req = OrderRequest(contract=SBIN, side=Side.SELL, quantity=2, order_type=OrderType.SL_MARKET,
                           trigger_price=D("790"), product=Product.DELIVERY)
        o = await d.place_order(req)
        b = body(place.calls.last)
        assert b["exchangeSegment"] == "NSE_EQ" and b["productType"] == "CNC" and b["orderType"] == "STOP_LOSS_MARKET"
        assert b["securityId"] == "3045" and b["triggerPrice"] == 790.0 and b["dhanClientId"] == "1000001"
        assert o.status is OrderStatus.OPEN
        m.get("/fundlimit").mock(return_value=httpx.Response(200, json={"availabelBalance": 98440.0, "utilizedAmount": 1560}))
        assert (await d.funds()).available_cash == D("98440.0")
        m.get("/orders").mock(return_value=httpx.Response(400, json={"errorCode": "DH-906", "errorMessage": "Order error"}))
        with pytest.raises(BrokerError, match="Order error"):
            await d.orders()


# ------------------------------------------------------------------ fyers
async def test_fyers_login_and_order(tokens):
    f = FyersBroker(tokens, "XA1234-100", "FSEC", "https://vm/api/v1/brokers/fyers/callback")
    assert "generate-authcode" in f.login_url("S")
    with respx.mock(base_url="https://api-t1.fyers.in/api/v3") as m:
        val = m.post("/validate-authcode").mock(return_value=httpx.Response(200, json={"s": "ok", "access_token": "FT"}))
        await f.complete_login({"auth_code": "AC", "state": "S"})
        assert body(val.calls.last)["appIdHash"] == hashlib.sha256(b"XA1234-100:FSEC").hexdigest()
        place = m.post("/orders/sync").mock(return_value=httpx.Response(200, json={"s": "ok", "id": "F77", "message": "ok"}))
        await f.place_order(limit_buy())
        b = body(place.calls.last)
        assert place.calls.last.request.headers["Authorization"] == "XA1234-100:FT"
        assert b["symbol"] == "NSE:SBIN-EQ" and b["type"] == 1 and b["side"] == 1 and b["productType"] == "INTRADAY"
        m.get("/positions").mock(return_value=httpx.Response(200, json={"s": "ok", "netPositions": [
            {"symbol": "NSE:NIFTY26DECFUT", "productType": "MARGIN", "netQty": 75, "netAvg": 25900, "ltp": 25950}]}))
        [p] = await f.positions()
        assert p.contract.exchange is Exchange.NFO and p.unrealized_pnl == D("3750")
        m.get("/orders").mock(return_value=httpx.Response(200, json={"s": "error", "code": -16, "message": "Invalid token"}))
        with pytest.raises(BrokerError) as e:
            await f.orders()
        assert e.value.errorcode == "-16"


async def test_order_calls_hit_rate_limiter(tokens):
    z = ZerodhaBroker(tokens, "k", "s")
    tokens.put("zerodha", BrokerToken("AT", next_expiry(z.info.token_expiry_ist).isoformat()))
    n = 0
    orig = z.limiter.acquire

    async def counting():
        nonlocal n; n += 1; await orig()
    z.limiter.acquire = counting
    with respx.mock(base_url="https://api.kite.trade") as m:
        m.post("/orders/regular").mock(return_value=httpx.Response(200, json={"status": "success", "data": {"order_id": "1"}}))
        m.get("/orders").mock(return_value=httpx.Response(200, json={"status": "success", "data": []}))
        await z.place_order(limit_buy()); await z.orders()
    assert n == 1
