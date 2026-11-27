"""SmartAPI broker against a fully mocked SmartAPI (respx). No network."""
from datetime import datetime
from decimal import Decimal as D

import httpx
import pyotp
import pytest
import respx

from app.broker.smartapi import client as C
from app.broker.smartapi.broker import SmartAPIBroker, to_smartapi_params
from app.broker.smartapi.client import SmartAPIClient
from app.broker.smartapi.session import IST, SessionManager
from app.core.enums import OrderStatus, OrderType, Side
from app.core.errors import AuthError, BrokerError
from app.core.models import OrderRequest

BASE = "https://smartapi.test"
SECRET = pyotp.random_base32()


def ok(data):
    return httpx.Response(200, json={"status": True, "message": "SUCCESS", "errorcode": "", "data": data})


def err(code, msg="err"):
    return httpx.Response(200, json={"status": False, "message": msg, "errorcode": code, "data": None})


LOGIN_OK = ok({"jwtToken": "jwt-1", "refreshToken": "r-1", "feedToken": "f-1"})


@pytest.fixture
def mock():
    with respx.mock(base_url=BASE, assert_all_called=False) as m:
        yield m


@pytest.fixture
def session(audit):
    now = {"t": datetime(2026, 11, 27, 9, 0, tzinfo=IST)}
    cl = SmartAPIClient(BASE, "api-key", "10.0.0.1", "1.2.3.4", "aa:bb")
    s = SessionManager(cl, "A123", "1111", SECRET, audit, now=lambda: now["t"])
    s._clock = now
    return s


async def test_login_sends_totp_and_headers(mock, session):
    route = mock.post(C.LOGIN).mock(return_value=LOGIN_OK)
    await session.login()
    sent = route.calls.last.request
    body = __import__("json").loads(sent.content)
    assert body["clientcode"] == "A123" and body["password"] == "1111"
    assert pyotp.TOTP(SECRET).verify(body["totp"], valid_window=1)
    assert sent.headers["X-PrivateKey"] == "api-key"
    assert sent.headers["X-ClientPublicIP"] == "1.2.3.4"
    assert session.client.jwt == "jwt-1"
    assert session.audit.tail()[-1]["event"] == "auth.login"


async def test_place_order_maps_params_and_bearer(mock, session, sbin):
    mock.post(C.LOGIN).mock(return_value=LOGIN_OK)
    place = mock.post(C.PLACE).mock(return_value=ok({"orderid": "2611270001", "uniqueorderid": "u1"}))
    b = SmartAPIBroker(session)
    r = OrderRequest(contract=sbin, side=Side.BUY, quantity=5, order_type=OrderType.LIMIT, price=D("800.5"))
    o = await b.place_order(r)
    assert o.order_id == "2611270001"
    req = place.calls.last.request
    assert req.headers["Authorization"] == "Bearer jwt-1"
    body = __import__("json").loads(req.content)
    assert body == to_smartapi_params(r)
    assert body["variety"] == "NORMAL" and body["price"] == "800.5" and body["symboltoken"] == "3045"


async def test_stoploss_variety(sbin):
    r = OrderRequest(contract=sbin, side=Side.SELL, quantity=1, order_type=OrderType.SL_MARKET,
                     trigger_price=D("790"))
    assert to_smartapi_params(r)["variety"] == "STOPLOSS"


async def test_reauth_on_expired_token(mock, session):
    login = mock.post(C.LOGIN).mock(return_value=LOGIN_OK)
    mock.get(C.ORDER_BOOK).mock(side_effect=[err("AG8001", "Invalid Token"), ok([])])
    b = SmartAPIBroker(session)
    assert await b.orders() == []
    assert login.call_count == 2
    assert any(r["event"] == "auth.reauth" for r in session.audit.tail())


async def test_broker_error_surfaces(mock, session, sbin):
    mock.post(C.LOGIN).mock(return_value=LOGIN_OK)
    mock.post(C.PLACE).mock(return_value=err("AB1013", "Order rejected"))
    with pytest.raises(BrokerError) as e:
        await SmartAPIBroker(session).place_order(
            OrderRequest(contract=sbin, side=Side.BUY, quantity=1, order_type=OrderType.MARKET))
    assert e.value.errorcode == "AB1013"


async def test_http_401_is_auth_error(mock, audit):
    mock.get(C.PROFILE).mock(return_value=httpx.Response(401))
    cl = SmartAPIClient(BASE, "k", "l", "p", "m")
    cl.jwt = "x"
    with pytest.raises(AuthError):
        await cl.profile()


async def test_parse_order_book_and_positions(mock, session):
    mock.post(C.LOGIN).mock(return_value=LOGIN_OK)
    mock.get(C.ORDER_BOOK).mock(return_value=ok([{
        "orderid": "1", "tradingsymbol": "SBIN-EQ", "symboltoken": "3045", "exchange": "NSE",
        "transactiontype": "BUY", "quantity": "5", "ordertype": "LIMIT", "producttype": "INTRADAY",
        "price": 800, "triggerprice": 0, "status": "complete", "filledshares": "5", "averageprice": 799.9,
        "text": ""}]))
    mock.get(C.POSITIONS).mock(return_value=ok([{
        "tradingsymbol": "SBIN-EQ", "symboltoken": "3045", "exchange": "NSE", "producttype": "INTRADAY",
        "netqty": "5", "netprice": "799.9", "realised": "0", "ltp": "805"}]))
    b = SmartAPIBroker(session)
    [o] = await b.orders()
    assert o.status is OrderStatus.FILLED and o.avg_fill_price == D("799.9")
    [p] = await b.positions()
    assert p.net_qty == 5 and p.unrealized_pnl == D("25.5")


async def test_daily_relogin_logic(mock, session):
    mock.post(C.LOGIN).mock(return_value=LOGIN_OK)
    logout = mock.post(C.LOGOUT).mock(return_value=ok(None))
    await session.login()
    assert not session.needs_daily_relogin()
    session._clock["t"] = datetime(2026, 11, 28, 8, 45, tzinfo=IST)   # next day after 08:30
    assert session.needs_daily_relogin()
    await session.ensure()
    assert logout.call_count == 1 and session.tokens.logged_in_at.day == 28


async def test_next_event(session):
    kind, when = session.next_event()          # 09:00 -> logout at 23:45 same day
    assert kind == "logout" and when.hour == 23
    session._clock["t"] = datetime(2026, 11, 27, 23, 50, tzinfo=IST)
    kind, when = session.next_event()
    assert kind == "login" and when.day == 28 and when.hour == 8


async def test_order_calls_are_rate_limited(mock, sbin):
    from app.core.ratelimit import SlidingWindowLimiter
    calls = []

    class L(SlidingWindowLimiter):
        async def acquire(self):
            calls.append(1)
    mock.post(C.PLACE).mock(return_value=ok({"orderid": "1"}))
    mock.get(C.ORDER_BOOK).mock(return_value=ok([]))
    cl = SmartAPIClient(BASE, "k", "l", "p", "m", order_limiter=L(8))
    cl.jwt = "x"
    await cl.place_order({}); await cl.order_book()
    assert len(calls) == 1   # only order-mutating calls consume order budget
