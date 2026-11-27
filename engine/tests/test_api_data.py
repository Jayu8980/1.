import asyncio
import json
from decimal import Decimal as D

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.api.data_routes import router as data_router
from app.api.routes import router
from app.api.security import hash_password
from app.config import Settings
from app.core.enums import Exchange, OrderType, Product, Side
from app.core.models import Contract, OrderRequest
from app.main import build_state, risk_monitor
from tests.test_api_brokers import PW, Phone

SBIN = Contract(symbol="SBIN-EQ", token="3045", exchange=Exchange.NSE)


@pytest.fixture
def env(tmp_path):
    s = Settings(_env_file=None, audit_log_path=str(tmp_path / "a.jsonl"), token_store_path=str(tmp_path / "t.json"),
                 device_keys_path=str(tmp_path / "d.json"), watchlist_path=str(tmp_path / "w.json"),
                 equity_curve_path=str(tmp_path / "e.jsonl"), risk_limits_path=str(tmp_path / "r.json"),
                 jwt_secret="s" * 40, app_user="me", app_password_hash=hash_password(PW),
                 paper_starting_capital=D("1000000"), paper_slippage_bps=D("0"))
    app = FastAPI(); app.include_router(router); app.include_router(data_router)
    build_state(app, s, sources=[])
    phone = Phone()
    c = TestClient(app)
    tok = c.post("/api/v1/auth/login", json={"username": "me", "password": PW, "devicePublicKey": phone.pub}).json()["token"]
    c.headers["Authorization"] = f"Bearer {tok}"
    run = lambda coro: asyncio.run(coro)  # noqa: E731
    return c, phone, app, run


def tick(app, run, contract, px):
    run(app.state.hub.emit(contract, D(px), "test"))


def buy(app, run, qty=100):
    return run(app.state.gateway.place(OrderRequest(contract=SBIN, side=Side.BUY, quantity=qty,
                                                    order_type=OrderType.MARKET, product=Product.INTRADAY)))


def test_reads_require_jwt(env):
    _, _, app, _ = env
    for p in ("/api/v1/dashboard", "/api/v1/positions", "/api/v1/orders", "/api/v1/risk", "/api/v1/watchlist"):
        assert TestClient(app).get(p).status_code == 401


def test_dashboard_positions_orders_from_real_ticks(env):
    c, _, app, run = env
    tick(app, run, SBIN, "800")
    buy(app, run)
    tick(app, run, SBIN, "810")
    d = c.get("/api/v1/dashboard").json()
    assert d["mode"] == "PAPER" and d["openPositions"] == 1
    assert d["dayPnl"] == pytest.approx(1000 - float(app.state.gateway.paper._positions[(SBIN.key, Product.INTRADAY)].charges))
    assert d["curve"][-1]["equity"] == pytest.approx(d["equity"]) and d["benchmarkLive"] is False
    [p] = c.get("/api/v1/positions").json()
    assert p["symbol"] == "SBIN-EQ" and p["qty"] == 100 and p["ltp"] == 810
    [o] = c.get("/api/v1/orders").json()
    assert o["status"] == "FILLED" and o["avgFillPrice"] == 800


def test_benchmark_curve_uses_live_nifty(env):
    c, _, app, run = env
    from app.services.watchlist import NIFTY
    tick(app, run, NIFTY, "25000")
    run(app.state.portfolio.record())
    tick(app, run, NIFTY, "25250")
    d = c.get("/api/v1/dashboard").json()
    assert d["benchmarkLive"] and [p["nifty"] for p in d["curve"]] == [25000, 25250]


def test_signed_exit_closes_position_even_under_kill_switch(env):
    c, phone, app, run = env
    tick(app, run, SBIN, "800")
    buy(app, run, 50)
    assert c.post("/api/v1/kill-switch", json={"engaged": True, "reason": "test"}).json()["ok"]
    body = json.dumps({"symbol": "SBIN-EQ", "exchange": "NSE", "product": "INTRADAY"}).encode()
    p = "/api/v1/positions/exit"
    assert c.post(p, content=body, headers={"Content-Type": "application/json"}).status_code == 403   # unsigned
    r = c.post(p, content=body, headers={"Content-Type": "application/json", **phone.sign("POST", p, body)}).json()
    assert r["ok"] and "FILLED" in r["message"]
    assert all(p["qty"] == 0 for p in c.get("/api/v1/positions").json())
    r = c.post(p, content=body, headers={"Content-Type": "application/json", **phone.sign("POST", p, body)}).json()
    assert not r["ok"] and "No open position" in r["message"]


def test_kill_switch_blocks_new_orders_and_non_exits(env):
    c, _, app, run = env
    from app.core.errors import OrderBlocked
    tick(app, run, SBIN, "800")
    c.post("/api/v1/kill-switch", json={"engaged": True, "reason": "t"})
    with pytest.raises(OrderBlocked, match="kill switch"):
        buy(app, run, 1)
    with pytest.raises(OrderBlocked, match="does not reduce"):     # "exit" that would open a position
        run(app.state.gateway.place(OrderRequest(contract=SBIN, side=Side.BUY, quantity=1, order_type=OrderType.MARKET,
                                                 product=Product.INTRADAY), exit_only=True))
    assert c.get("/api/v1/risk").json()["killSwitch"]
    assert c.post("/api/v1/kill-switch", json={"engaged": False}).json()["ok"]


def test_risk_limits_signed_validated_and_enforced(env):
    c, phone, app, run = env
    p = "/api/v1/risk"
    lim = {"dailyLossLimit": 500, "maxDrawdownPct": 10, "maxRiskPerTradePct": 1, "maxPositionPct": 10,
           "maxSectorPct": 25, "kellyFraction": 0.25}
    bad = json.dumps({**lim, "kellyFraction": 0.5}).encode()
    r = c.put(p, content=bad, headers={"Content-Type": "application/json", **phone.sign("PUT", p, bad)}).json()
    assert not r["ok"] and "kellyFraction" in r["message"]
    good = json.dumps(lim).encode()
    assert c.put(p, content=good, headers={"Content-Type": "application/json", **phone.sign("PUT", p, good)}).json()["ok"]

    from app.core.errors import OrderBlocked
    tick(app, run, SBIN, "800")
    with pytest.raises(OrderBlocked, match="10% of equity"):          # 2000*800 = 16L > 10% of 10L
        buy(app, run, 2000)
    buy(app, run, 100)
    tick(app, run, SBIN, "790")                                       # -1000 < -500 daily limit
    with pytest.raises(OrderBlocked, match="daily loss"):
        buy(app, run, 1)
    run(risk_monitor(app))
    assert app.state.gateway.kill_switch
    assert any(e["event"] == "kill_switch.engaged" and "risk-engine" in e["data"] for e in c.get("/api/v1/audit").json())


def test_watchlist_crud_and_stream(env):
    c, _, app, run = env
    w = c.get("/api/v1/watchlist").json()
    keys = {f"{x['contract']['exchange']}:{x['contract']['symbol']}" for x in w}
    assert {"NSE:Nifty 50", "NSE:SBIN-EQ", "DELTA:BTCUSD"} <= keys
    infy = Contract(symbol="WIPRO-EQ", token="3787", exchange=Exchange.NSE)
    assert c.post("/api/v1/watchlist", content=infy.model_dump_json(), headers={"Content-Type": "application/json"}).json()["ok"]
    assert "NSE:WIPRO-EQ" in app.state.hub.contracts
    assert c.delete("/api/v1/watchlist/NSE/WIPRO-EQ").json()["ok"]
    assert "NSE:WIPRO-EQ" not in app.state.hub.contracts

    with pytest.raises(Exception):
        with TestClient(app).websocket_connect("/api/v1/stream") as ws:
            ws.receive_json()
    tick(app, run, SBIN, "805.5")
    with c.websocket_connect("/api/v1/stream", headers={"Authorization": c.headers["Authorization"]}) as ws:
        msgs = [ws.receive_json() for _ in range(2)]
    types = {m["type"] for m in msgs}
    assert "tick" in types and "status" in types
    t = next(m for m in msgs if m["type"] == "tick")
    assert t["key"] == "NSE:SBIN-EQ" and t["ltp"] == 805.5


def test_phase_placeholders_are_honest(env):
    c, _, _, _ = env
    assert c.get("/api/v1/signals").json() == []
    assert {x["stage"] for x in c.get("/api/v1/strategies").json()} == {"PAPER"}
    assert c.get("/api/v1/analyst/SBIN").status_code == 503
