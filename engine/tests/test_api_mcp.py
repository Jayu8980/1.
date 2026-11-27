import asyncio
import json
from contextlib import asynccontextmanager
from decimal import Decimal as D

import httpx
import pytest
import respx
from fastapi import FastAPI
from fastapi.testclient import TestClient
from mcp.client.client import Client

from app.api.data_routes import router as data_router
from app.api.ext_routes import router as ext_router
from app.api.routes import router
from app.api.security import hash_password
from app.broker.tokens import BrokerToken, next_expiry
from app.config import Settings
from app.core.enums import Exchange, Mode
from app.core.models import Contract
from app.main import build_state, mount_mcp
from app.mcp_server import RemoteBackend, build_mcp
from tests.test_api_brokers import PHRASE, PW, Phone

SBIN = Contract(symbol="SBIN-EQ", token="3045", exchange=Exchange.NSE)
KITE = "https://api.kite.trade"


@pytest.fixture
def env(tmp_path):
    s = Settings(_env_file=None, **{k: str(tmp_path / v) for k, v in dict(
        audit_log_path="a.jsonl", token_store_path="t.json", device_keys_path="d.json", watchlist_path="w.json",
        equity_curve_path="e.jsonl", risk_limits_path="r.json", strategies_path="s.json",
        trade_journal_path="j.jsonl", apikeys_path="k.json").items()},
        jwt_secret="s" * 40, app_user="me", app_password_hash=hash_password(PW), zerodha_api_key="kkey",
        zerodha_api_secret="ksec", live_broker="zerodha", allow_live=True, static_ip="1.2.3.4",
        paper_slippage_bps=D("0"), api_rate_per_min=1000)
    holder = {}

    @asynccontextmanager
    async def lifespan(a):
        async with holder["mcp"].session_manager.run():
            yield
    app = FastAPI(lifespan=lifespan)
    for r in (router, data_router, ext_router):
        app.include_router(r)

    async def ip():
        return "1.2.3.4"
    build_state(app, s, sources=[], ip_fetcher=ip)
    holder["mcp"] = mount_mcp(app, s)
    z = app.state.brokers.get("zerodha")
    z.tokens.put("zerodha", BrokerToken("AT", next_expiry(z.info.token_expiry_ist).isoformat(), "AB1234"))
    phone = Phone()
    c = TestClient(app)
    c.__enter__()
    tok = c.post("/api/v1/auth/login", json={"username": "me", "password": PW, "devicePublicKey": phone.pub}).json()["token"]
    c.headers["Authorization"] = f"Bearer {tok}"
    asyncio.run(app.state.hub.emit(SBIN, D("800"), "test"))
    yield c, phone, app
    c.__exit__(None, None, None)


def signed(c, phone, method, path, body=None):
    raw = json.dumps(body).encode() if body is not None else b""
    h = {"Content-Type": "application/json", **phone.sign(method, path, raw)}
    return c.request(method, path, content=raw, headers=h).json()


def new_key(c, phone, scopes, name="bot"):
    r = signed(c, phone, "POST", "/api/v1/apikeys", {"name": name, "scopes": scopes})
    assert r["ok"] and r["key"].startswith("atk_")
    return r["key"], r["info"]["id"]


def ext(app, key):
    return TestClient(app, headers={"X-API-Key": key})


TICKET = {"symbol": "SBIN-EQ", "exchange": "NSE", "side": "BUY", "qty": 5, "orderType": "LIMIT", "price": 800,
          "product": "INTRADAY"}


# ------------------------------------------------------------------ keys & scopes
def test_key_creation_needs_biometric_and_is_hashed(env, tmp_path):
    c, phone, app = env
    assert c.post("/api/v1/apikeys", json={"name": "x", "scopes": ["read"]}).status_code == 403
    key, kid = new_key(c, phone, ["read"])
    assert key not in (tmp_path / "k.json").read_text()                         # only the hash is stored
    assert c.get("/api/v1/apikeys").json()[0]["id"] == kid and "hash" not in c.get("/api/v1/apikeys").json()[0]
    bad = signed(c, phone, "POST", "/api/v1/apikeys", {"name": "x", "scopes": ["admin"]})
    assert not bad["ok"]


def test_scopes_auth_revocation_and_rate_limit(env):
    c, phone, app = env
    rkey, rid = new_key(c, phone, ["read"])
    e = ext(app, rkey)
    assert TestClient(app).get("/api/v1/ext/status").status_code == 401
    assert ext(app, "atk_wrong").get("/api/v1/ext/status").status_code == 401
    st = e.get("/api/v1/ext/status").json()
    assert st["mode"] == "PAPER" and st["keyScopes"] == ["read"]
    r = e.post("/api/v1/ext/orders", json=TICKET)
    assert r.status_code == 403 and "'trade' scope" in r.json()["detail"]
    assert e.post("/api/v1/ext/kill-switch", json={"reason": "x"}).status_code == 403
    assert {b["id"] for b in e.get("/api/v1/ext/brokers").json()} >= {"zerodha", "delta"}
    assert e.get("/api/v1/ext/quotes", params={"symbols": "SBIN-EQ"}).json()[0]["ltp"] == 800
    assert c.delete(f"/api/v1/apikeys/{rid}").json()["ok"]
    assert e.get("/api/v1/ext/status").status_code == 401
    app.state.apikeys.rate_per_min = 2
    k2, _ = new_key(c, phone, ["read"])
    e2 = ext(app, k2)
    e2.get("/api/v1/ext/status"); e2.get("/api/v1/ext/status")
    assert e2.get("/api/v1/ext/status").status_code == 429


# ------------------------------------------------------------------ paper vs live approval
def test_paper_order_executes_directly(env):
    c, phone, app = env
    key, _ = new_key(c, phone, ["read", "trade"])
    r = ext(app, key).post("/api/v1/ext/orders", json={**TICKET, "orderType": "MARKET", "price": None}).json()
    assert r["ok"] and r["venue"] == "paper" and r["status"] == "FILLED"
    assert ext(app, key).get("/api/v1/ext/positions", params={"account": "paper"}).json()[0]["qty"] == 5


def test_live_order_needs_phone_approval(env):
    c, phone, app = env
    key, _ = new_key(c, phone, ["read", "trade"])
    asyncio.run(app.state.gateway.set_mode(Mode.LIVE, confirm_phrase=PHRASE))
    with respx.mock(base_url=KITE, assert_all_called=False) as m:
        m.get("/user/margins/equity").mock(return_value=httpx.Response(200, json={"status": "success", "data": {"net": 1e6, "utilised": {"debits": 0}}}))
        m.get("/portfolio/positions").mock(return_value=httpx.Response(200, json={"status": "success", "data": {"net": []}}))
        place = m.post("/orders/regular").mock(return_value=httpx.Response(200, json={"status": "success", "data": {"order_id": "Z99"}}))

        r = ext(app, key).post("/api/v1/ext/orders", json={**TICKET, "reason": "breakout"}).json()
        assert r["status"] == "PENDING_APPROVAL" and place.call_count == 0          # nothing sent yet
        iid = r["intentId"]
        [pend] = c.get("/api/v1/intents").json()
        assert pend["id"] == iid and pend["reason"] == "breakout" and "key:" in pend["requested_by"]

        p = f"/api/v1/intents/{iid}/approve"
        assert c.post(p).status_code == 403                                          # needs biometric signature
        r = signed(c, phone, "POST", p)
        assert r["ok"] and "REAL order at Zerodha" in r["message"] and place.call_count == 1
        st = ext(app, key).get(f"/api/v1/ext/intents/{iid}").json()
        assert st["status"] == "APPROVED" and st["order_id"] == "Z99"
        assert not signed(c, phone, "POST", p)["ok"]                                 # can't approve twice

        r2 = ext(app, key).post("/api/v1/ext/orders", json=TICKET).json()
        assert c.post(f"/api/v1/intents/{r2['intentId']}/reject").json()["ok"]
        assert place.call_count == 1

        app.state.intents.ttl_s = 0
        r3 = ext(app, key).post("/api/v1/ext/orders", json=TICKET).json()
        assert not signed(c, phone, "POST", f"/api/v1/intents/{r3['intentId']}/approve")["ok"]
        assert ext(app, key).get(f"/api/v1/ext/intents/{r3['intentId']}").json()["status"] == "EXPIRED"
        assert place.call_count == 1

    bad = ext(app, key).post("/api/v1/ext/orders", json={**TICKET, "symbol": "NOPE-EQ"}).json()
    assert not bad["ok"] and "watchlist" in bad["message"]


def test_control_scope_kill_switch_and_cannot_release(env):
    c, phone, app = env
    key, _ = new_key(c, phone, ["read", "control"])
    r = ext(app, key).post("/api/v1/ext/kill-switch", json={"reason": "bot saw anomaly"}).json()
    assert r["ok"] and app.state.gateway.kill_switch
    assert not any("release" in str(x.path) for x in app.routes if "/ext/" in str(getattr(x, "path", "")))


# ------------------------------------------------------------------ MCP
def test_mcp_stdio_mode_via_api(env):
    """Same tools, RemoteBackend proxying to /api/v1/ext in-process."""
    c, phone, app = env
    key, _ = new_key(c, phone, ["read", "trade"])

    async def run():
        backend = RemoteBackend("http://testserver", key, transport=httpx.ASGITransport(app=app))
        async with Client(build_mcp(backend)) as cl:
            tools = {t.name: t for t in (await cl.list_tools()).tools}
            assert tools["aitrader_get_quotes"].annotations.read_only_hint
            assert tools["aitrader_place_order"].annotations.destructive_hint
            st = await cl.call_tool("aitrader_status", {})
            assert not st.is_error and json.loads(st.content[0].text)["mode"] == "PAPER"
            q = await cl.call_tool("aitrader_get_quotes", {"symbols": ["SBIN-EQ"]})
            assert "800" in q.content[0].text
            o = await cl.call_tool("aitrader_place_order", {"symbol": "SBIN-EQ", "exchange": "NSE", "side": "BUY",
                                                             "qty": 2, "order_type": "MARKET", "reason": "test buy"})
            assert not o.is_error and '"FILLED"' in o.content[0].text
            k = await cl.call_tool("aitrader_engage_kill_switch", {"reason": "should fail"})
            assert k.is_error and "control" in k.content[0].text
    asyncio.run(run())


def test_mcp_http_mount_requires_key_and_lists_tools(env):
    c, phone, app = env
    key, _ = new_key(c, phone, ["read"])
    h = {"Accept": "application/json, text/event-stream", "Content-Type": "application/json"}
    body = {"jsonrpc": "2.0", "id": 1, "method": "tools/list", "params": {}}
    assert c.post("/mcp/", json=body, headers={**h, "Authorization": ""}).status_code == 401
    init = {"jsonrpc": "2.0", "id": 0, "method": "initialize", "params": {"protocolVersion": "2025-06-18",
            "capabilities": {}, "clientInfo": {"name": "t", "version": "1"}}}
    r0 = c.post("/mcp/", json=init, headers={**h, "X-API-Key": key, "Authorization": ""})
    assert r0.status_code == 200, r0.text
    r = c.post("/mcp/", json=body, headers={**h, "X-API-Key": key, "Authorization": ""})
    assert r.status_code == 200, r.text
    names = {t["name"] for t in r.json()["result"]["tools"]}
    assert {"aitrader_place_order", "aitrader_list_brokers", "aitrader_engage_kill_switch"} <= names
    call = {"jsonrpc": "2.0", "id": 2, "method": "tools/call", "params": {"name": "aitrader_get_risk", "arguments": {}}}
    rr = c.post("/mcp/", json=call, headers={**h, "X-API-Key": key, "Authorization": ""}).json()
    assert not rr["result"].get("isError") and "dailyLossLimit" in rr["result"]["content"][0]["text"]
    kill = {"jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": {"name": "aitrader_engage_kill_switch",
                                                                          "arguments": {"reason": "nope"}}}
    rk = c.post("/mcp/", json=kill, headers={**h, "X-API-Key": key, "Authorization": ""}).json()
    assert rk["result"]["isError"] and not app.state.gateway.kill_switch
