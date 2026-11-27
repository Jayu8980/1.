"""Manual orders from the app reach the REAL broker when LIVE — through every safety check."""
import asyncio
import json
from decimal import Decimal as D

import httpx
import pytest
import respx
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.api.data_routes import router as data_router
from app.api.routes import router
from app.api.security import hash_password
from app.broker.tokens import BrokerToken, next_expiry
from app.config import Settings
from app.core.enums import Exchange, Mode
from app.core.models import Contract
from app.main import build_state
from tests.test_api_brokers import PHRASE, PW, Phone

SBIN = Contract(symbol="SBIN-EQ", token="3045", exchange=Exchange.NSE)
KITE = "https://api.kite.trade"


@pytest.fixture
def env(tmp_path):
    s = Settings(_env_file=None, audit_log_path=str(tmp_path / "a.jsonl"), token_store_path=str(tmp_path / "t.json"),
                 device_keys_path=str(tmp_path / "d.json"), watchlist_path=str(tmp_path / "w.json"),
                 equity_curve_path=str(tmp_path / "e.jsonl"), risk_limits_path=str(tmp_path / "r.json"),
                 strategies_path=str(tmp_path / "s.json"), trade_journal_path=str(tmp_path / "j.jsonl"),
                 jwt_secret="s" * 40, app_user="me", app_password_hash=hash_password(PW),
                 zerodha_api_key="kkey", zerodha_api_secret="ksec", live_broker="zerodha",
                 allow_live=True, static_ip="1.2.3.4", paper_slippage_bps=D("0"))
    app = FastAPI(); app.include_router(router); app.include_router(data_router)
    ip = {"v": "1.2.3.4"}

    async def fetch():
        return ip["v"]
    build_state(app, s, sources=[], ip_fetcher=fetch)
    z = app.state.brokers.get("zerodha")
    z.tokens.put("zerodha", BrokerToken("AT", next_expiry(z.info.token_expiry_ist).isoformat(), "AB1234"))
    phone = Phone()
    c = TestClient(app)
    tok = c.post("/api/v1/auth/login", json={"username": "me", "password": PW, "devicePublicKey": phone.pub}).json()["token"]
    c.headers["Authorization"] = f"Bearer {tok}"
    asyncio.run(app.state.hub.emit(SBIN, D("800"), "test"))
    return c, phone, app, ip


def post_signed(c, phone, path, body):
    raw = json.dumps(body).encode()
    return c.post(path, content=raw, headers={"Content-Type": "application/json", **phone.sign("POST", path, raw)}).json()


def kite_mocks(m, funds=1_000_000):
    m.get("/user/margins/equity").mock(return_value=httpx.Response(200, json={
        "status": "success", "data": {"net": funds, "utilised": {"debits": 0}}}))
    m.get("/portfolio/positions").mock(return_value=httpx.Response(200, json={"status": "success", "data": {"net": []}}))


def ticket(**kw):
    return {"symbol": "SBIN-EQ", "exchange": "NSE", "side": "BUY", "qty": 10, "orderType": "LIMIT", "price": 800,
            "product": "INTRADAY", **kw}


def test_paper_mode_manual_order_is_paper(env):
    c, phone, app, _ = env
    r = post_signed(c, phone, "/api/v1/orders", ticket(orderType="MARKET", price=None))
    assert r["ok"] and r["message"].startswith("PAPER order")
    assert c.post("/api/v1/orders", json=ticket()).status_code == 403      # unsigned


def test_live_manual_order_reaches_real_broker(env):
    c, phone, app, _ = env
    asyncio.run(app.state.gateway.set_mode(Mode.LIVE, confirm_phrase=PHRASE))
    with respx.mock(base_url=KITE, assert_all_called=False) as m:
        kite_mocks(m)
        place = m.post("/orders/regular").mock(return_value=httpx.Response(200, json={
            "status": "success", "data": {"order_id": "250101000123"}}))
        r = post_signed(c, phone, "/api/v1/orders", ticket(orderType="MARKET", price=None, expectLive=True))
        assert r["ok"] and "REAL order at Zerodha" in r["message"] and "250101000123" in r["message"]
        sent = dict(x.split("=") for x in place.calls.last.request.content.decode().split("&"))
        assert sent["order_type"] == "LIMIT" and sent["price"] == "804.00"     # MARKET -> marketable LIMIT +0.5%
        assert place.calls.last.request.headers["Authorization"] == "token kkey:AT"
    ev = [e["event"] for e in app.state.audit.tail(20)]
    assert "order.submit" in ev and "order.ack" in ev and "order.manual" in ev


def test_live_order_refused_on_stale_ticket_ip_mismatch_and_risk(env):
    c, phone, app, ip = env
    asyncio.run(app.state.gateway.set_mode(Mode.LIVE, confirm_phrase=PHRASE))
    r = post_signed(c, phone, "/api/v1/orders", ticket(expectLive=False))      # user saw PAPER
    assert not r["ok"] and "Mode changed" in r["message"]
    with respx.mock(base_url=KITE, assert_all_called=False) as m:
        kite_mocks(m, funds=100_000)
        place = m.post("/orders/regular").mock(return_value=httpx.Response(200, json={"status": "success", "data": {"order_id": "1"}}))
        r = post_signed(c, phone, "/api/v1/orders", ticket(qty=100, expectLive=True))   # 80k > 10% of 1L
        assert not r["ok"] and "of equity" in r["message"]
        ip["v"] = "9.9.9.9"
        r = post_signed(c, phone, "/api/v1/orders", ticket(qty=5, expectLive=True))
        assert not r["ok"] and "9.9.9.9" in r["message"]
        assert place.call_count == 0                                            # nothing reached the broker


def test_real_account_view_and_cancel_while_engine_in_paper(env):
    c, _, app, _ = env
    with respx.mock(base_url=KITE, assert_all_called=False) as m:
        m.get("/user/margins/equity").mock(return_value=httpx.Response(200, json={
            "status": "success", "data": {"net": 250000, "utilised": {"debits": 5000}}}))
        m.get("/portfolio/positions").mock(return_value=httpx.Response(200, json={"status": "success", "data": {"net": [{
            "tradingsymbol": "INFY", "exchange": "NSE", "product": "CNC", "quantity": 10, "average_price": 1500,
            "last_price": 1510, "realised": 0}]}}))
        m.get("/orders").mock(return_value=httpx.Response(200, json={"status": "success", "data": [{
            "order_id": "777", "tradingsymbol": "INFY", "exchange": "NSE", "transaction_type": "BUY", "quantity": 5,
            "order_type": "LIMIT", "product": "CNC", "price": 1400, "status": "OPEN"}]}))
        cancel = m.delete("/orders/regular/777").mock(return_value=httpx.Response(200, json={"status": "success", "data": {}}))
        d = c.get("/api/v1/dashboard", params={"venue": "live"}).json()
        assert d["account"] == "live" and d["broker"] == "zerodha" and d["equity"] == pytest.approx(255100)
        assert c.get("/api/v1/dashboard").json()["account"] == "paper"           # engine itself still PAPER
        [p] = c.get("/api/v1/positions", params={"venue": "live"}).json()
        assert p["symbol"] == "INFY" and p["qty"] == 10
        [o] = c.get("/api/v1/orders", params={"venue": "live"}).json()
        assert o["orderId"] == "777" and o["status"] == "OPEN"
        assert c.post("/api/v1/orders/777/cancel", params={"venue": "live"}).json()["ok"]
        assert cancel.called
