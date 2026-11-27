import asyncio
import json
from datetime import datetime, timedelta, timezone
from decimal import Decimal as D
from zoneinfo import ZoneInfo

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.api.data_routes import router as data_router
from app.api.routes import router
from app.api.security import hash_password
from app.autotrade.bars import Bar, BarSeries
from app.autotrade.engine import AutoSettings, AutoTrader
from app.autotrade.indicators import atr, efficiency_ratio, ema, rsi
from app.autotrade.store import StrategyStore
from app.autotrade.strategies import MeanReversion, TradeSignal, TrendMomentum, regime
from app.config import Settings
from app.core.enums import Exchange, Mode, OrderStatus
from app.core.models import Contract
from app.main import build_state
from tests.test_api_brokers import PW, Phone

IST = ZoneInfo("Asia/Kolkata")
SBIN = Contract(symbol="SBIN-EQ", token="3045", exchange=Exchange.NSE)


# ------------------------------------------------------------------ indicators / regime / strategies
def test_indicators():
    assert ema([1, 2, 3, 4, 5], 5) == 3
    assert rsi([1, 2, 3, 4, 5, 6], 2) == 100.0
    assert efficiency_ratio(list(range(30)), 20) == 1.0
    assert efficiency_ratio([1, 2] * 15, 20) == 0.0
    assert atr([2] * 20, [1] * 20, [1.5] * 20, 14) == pytest.approx(1.0)


def series(closes, spread=0.5):
    s = BarSeries(5)
    t0 = datetime(2026, 11, 27, 4, 0, tzinfo=timezone.utc)
    s.seed([Bar(t0 + timedelta(minutes=5 * i), c, c + spread, c - spread, c) for i, c in enumerate(closes)])
    return s


def test_regime_classification():
    assert regime(series([100 + i * 0.3 for i in range(80)]))[0] == "TREND"
    assert regime(series([100 + (0.2 if i % 2 else -0.2) for i in range(80)], spread=0.2))[0] == "RANGE"
    assert regime(series([100 + (3 if i % 2 else -3) for i in range(80)], spread=2))[0] == "HIGH_VOL"
    assert regime(series([100] * 10))[0] == "UNCERTAIN"


def test_trend_momentum_fires_on_cross_only_in_trend():
    closes = [100 - i * 0.05 for i in range(60)] + [97 + i * 0.4 for i in range(1, 40)]
    s, fired = BarSeries(5), None
    t0 = datetime(2026, 11, 27, 4, 0, tzinfo=timezone.utc)
    bars = []
    for i, c in enumerate(closes):
        bars.append(Bar(t0 + timedelta(minutes=5 * i), c, c + 0.2, c - 0.2, c)); s.seed(bars)
        sig = TrendMomentum().evaluate(s, "TREND", 0.8)
        if sig:
            fired = sig; break
    assert fired and fired.side == "BUY" and fired.stop < fired.entry < fired.target
    assert fired.reward_risk == pytest.approx(2.0)
    assert TrendMomentum().evaluate(s, "RANGE", 0.8) is None


def test_mean_reversion_buys_oversold_in_range():
    closes = [100 + (0.3 if i % 2 else -0.3) for i in range(60)] + [99.0, 98.2, 97.5]
    sig = MeanReversion().evaluate(series(closes, 0.3), "RANGE", 0.7)
    assert sig and sig.side == "BUY" and sig.target > sig.entry > sig.stop


# ------------------------------------------------------------------ autotrader end-to-end on paper
class Clock:
    def __init__(self):
        self.t = datetime(2026, 11, 27, 10, 0, tzinfo=IST)   # Friday 10:00 IST

    def __call__(self):
        return self.t.astimezone(timezone.utc)


class Fake:
    id, name, description, regimes = "fake", "Fake", "test strategy", ("TREND",)

    def __init__(self):
        self.next: dict | None = None

    def evaluate(self, s, reg, conf):
        if self.next is None:
            return None
        px = s.closes[-1]
        d, self.next = self.next, None
        return TradeSignal("fake", d.get("side", "BUY"), px, px - d.get("risk", 10), px + d.get("reward", 20),
                           d.get("conf", 0.7), "TREND", "test", d.get("atr", 5))


@pytest.fixture
def env(tmp_path):
    s = Settings(_env_file=None, audit_log_path=str(tmp_path / "a.jsonl"), token_store_path=str(tmp_path / "t.json"),
                 device_keys_path=str(tmp_path / "d.json"), watchlist_path=str(tmp_path / "w.json"),
                 equity_curve_path=str(tmp_path / "e.jsonl"), risk_limits_path=str(tmp_path / "r.json"),
                 strategies_path=str(tmp_path / "s.json"), trade_journal_path=str(tmp_path / "j.jsonl"),
                 jwt_secret="s" * 40, app_user="me", app_password_hash=hash_password(PW),
                 paper_starting_capital=D("1000000"), paper_slippage_bps=D("0"))
    app = FastAPI(); app.include_router(router); app.include_router(data_router)
    build_state(app, s, sources=[])
    clock, fake = Clock(), Fake()
    store = StrategyStore(tmp_path / "fs.json", tmp_path / "fj.jsonl", ["fake"])
    store.set_autotrade(True)
    at = AutoTrader(app.state.gateway, app.state.risk, store, app.state.audit, AutoSettings(), [fake], clock)
    app.state.hub._on_tick = [lambda c, px: app.state.gateway.paper.on_tick(c, px), at.on_tick]
    return app, at, fake, clock


def feed(app, clock, px, minutes=0):
    clock.t += timedelta(minutes=minutes)
    asyncio.run(app.state.hub.emit(SBIN, D(str(px)), "test"))


def bar(app, clock, px):
    """Advance to the next 5-min bucket with a tick (closes the previous bar)."""
    feed(app, clock, px, minutes=5)


def test_full_trade_lifecycle_entry_stop_partial_trail_exit(env):
    app, at, fake, clock = env
    paper = app.state.gateway.paper
    feed(app, clock, 1000)
    fake.next = {"risk": 10, "reward": 20, "conf": 0.7}
    bar(app, clock, 1000)
    [sig] = list(at.signals)
    assert sig["status"] == "TAKEN", sig
    [t] = at.open_trades()
    assert t.status == "OPEN" and t.venue == "paper" and t.entry_price == 1000 and t.stop == 990
    # size: risk 1% of 10L = 10k / ₹10 risk-per-share = 1000, capped by 10% position (100k / 1000 = 100) & 25% alloc
    assert t.qty == 100
    stop = next(o for o in asyncio.run(paper.orders()) if o.order_id == t.stop_order)
    assert stop.status is OrderStatus.PENDING and stop.request.strategy == "fake:PROTECT"
    assert stop.request.trigger_price == D("990.00")

    feed(app, clock, 1021)                       # target -> book half, stop to breakeven
    assert t.partial_done and t.remaining == 50 and t.stop == 1000
    stop = next(o for o in asyncio.run(paper.orders()) if o.order_id == t.stop_order)
    assert stop.request.quantity == 50 and stop.request.trigger_price == D("1000.00")

    feed(app, clock, 1040)                       # trail: 1040 - 2*5 = 1030
    assert t.stop == pytest.approx(1030)
    feed(app, clock, 1029)                       # paper stop triggers and fills
    bar(app, clock, 1029)                        # bar housekeeping notices the fill
    assert t.status == "CLOSED"
    [rec] = at.store.trades("fake")
    assert rec["venue"] == "paper" and rec["why"] == "stop" and rec["pnl"] > 0
    assert all(p.net_qty == 0 for p in asyncio.run(paper.positions()))


def test_cost_confidence_and_limits_skip(env):
    app, at, fake, clock = env
    feed(app, clock, 1000)
    fake.next = {"conf": 0.5}; bar(app, clock, 1000)
    fake.next = {"risk": 10, "reward": 0.3, "conf": 0.99}; bar(app, clock, 1000)   # tiny target: costs > edge
    fake.next = {"risk": 10, "reward": 12, "conf": 0.40}; bar(app, clock, 1000)
    statuses = [s["status"] for s in reversed(at.signals)]
    assert statuses == ["SKIPPED_CONFIDENCE", "SKIPPED_COST", "SKIPPED_CONFIDENCE"]
    fake.next = {}; bar(app, clock, 1000)
    fake.next = {}; bar(app, clock, 1000)
    assert at.signals[0]["status"] == "SKIPPED_LIMIT" and "already in a trade" in at.signals[0]["reason"]


def test_autotrade_off_and_entry_window(env):
    app, at, fake, clock = env
    at.store.set_autotrade(False)
    feed(app, clock, 1000); fake.next = {}; bar(app, clock, 1000)
    assert not at.signals and not at.open_trades()
    at.store.set_autotrade(True)
    clock.t = datetime(2026, 11, 27, 15, 0, tzinfo=IST)       # after 14:45
    feed(app, clock, 1000); fake.next = {}; bar(app, clock, 1000)
    assert not at.open_trades()
    clock.t = datetime(2026, 11, 28, 11, 0, tzinfo=IST)       # Saturday
    feed(app, clock, 1000); fake.next = {}; bar(app, clock, 1000)
    assert not at.open_trades()


def test_paper_stage_strategy_stays_on_paper_even_when_engine_is_live(env):
    app, at, fake, clock = env

    class Boom:
        name = "live"
        async def place_order(self, req): raise AssertionError("must not reach live broker")
        async def orders(self): return []
        async def positions(self): return []
    app.state.gateway.live = Boom()
    app.state.gateway.mode = Mode.LIVE
    feed(app, clock, 1000); fake.next = {}; bar(app, clock, 1000)
    [t] = at.open_trades()
    assert t.venue == "paper" and t.status == "OPEN"


def test_kill_switch_blocks_entries_but_keeps_protective_stop(env):
    app, at, fake, clock = env
    feed(app, clock, 1000); fake.next = {}; bar(app, clock, 1000)
    [t] = at.open_trades()
    res = asyncio.run(app.state.gateway.engage_kill_switch("test"))
    assert t.stop_order not in res["cancelled"]
    stop = next(o for o in asyncio.run(app.state.gateway.paper.orders()) if o.order_id == t.stop_order)
    assert stop.status is OrderStatus.PENDING
    fake.next = {}
    other = Contract(symbol="INFY-EQ", token="1594", exchange=Exchange.NSE)
    asyncio.run(app.state.hub.emit(other, D("1500"), "t")); clock.t += timedelta(minutes=5)
    asyncio.run(app.state.hub.emit(other, D("1500"), "t"))
    assert at.signals[0]["status"] == "VETOED" and "kill switch" in at.signals[0]["reason"]


def test_time_stop_and_intraday_squareoff(env):
    app, at, fake, clock = env
    at.s.time_stop_bars = 3
    feed(app, clock, 1000); fake.next = {}; bar(app, clock, 1000)
    [t] = at.open_trades()
    for _ in range(3):
        bar(app, clock, 1001)
    assert t.status == "CLOSED" and at.store.trades("fake")[0]["why"] == "time stop"

    at.s.time_stop_bars = 99
    clock.t = datetime(2026, 11, 27, 14, 40, tzinfo=IST)
    feed(app, clock, 1000); fake.next = {}; bar(app, clock, 1000)
    [t2] = at.open_trades()
    clock.t = datetime(2026, 11, 27, 15, 16, tzinfo=IST)
    feed(app, clock, 1002)
    assert t2.status == "CLOSED" and at.store.trades("fake")[-1]["why"] == "intraday square-off"


# ------------------------------------------------------------------ store: gate + drift
def test_promotion_gate_and_drift(tmp_path):
    st = StrategyStore(tmp_path / "c.json", tmp_path / "j.jsonl", ["x"])
    with pytest.raises(ValueError, match="more days"):
        st.promote("x")
    st.cfg["x"].stageSince = (datetime.now(timezone.utc) - timedelta(days=30)).isoformat()
    for i in range(30):
        st.record_trade({"strategy": "x", "venue": "paper", "pnl": 100 if i % 3 else -50, "notional": 10000,
                         "capital": 100000, "opened": "2026-10-01"})
    assert st.gate("x")[0]
    assert st.promote("x") == "LIVE_10"
    with pytest.raises(ValueError, match="more days in LIVE_10"):
        st.promote("x")
    for _ in range(5):
        st.record_trade({"strategy": "x", "venue": "live", "pnl": -400, "notional": 10000, "capital": 100000,
                         "opened": "2026-11-01"})
    reason = st.drift_check("x")
    assert reason and "1.5x" in reason and not st.cfg["x"].enabled
    assert "auto-disabled" in st.update("x", enabled=True)
    st.demote("x")
    assert st.cfg["x"].stage == "PAPER" and st.update("x", enabled=True) is None


# ------------------------------------------------------------------ API
def test_autotrade_api_switches(env):
    app, at, _, _ = env
    app.state.autotrader = at
    phone = Phone()
    c = TestClient(app)
    tok = c.post("/api/v1/auth/login", json={"username": "me", "password": PW, "devicePublicKey": phone.pub}).json()["token"]
    c.headers["Authorization"] = f"Bearer {tok}"
    at.store.set_autotrade(False)
    p = "/api/v1/autotrade/on"
    assert c.post(p).status_code == 403                         # needs biometric signature
    r = c.post(p, headers=phone.sign("POST", p)).json()
    assert r["ok"] and "PAPER only" in r["message"] and at.enabled
    assert c.post("/api/v1/autotrade/off").json()["ok"] and not at.enabled   # off is instant
    assert c.get("/api/v1/autotrade").json()["enabled"] is False
    [s] = c.get("/api/v1/strategies").json()
    assert s["stage"] == "PAPER" and not s["promotable"] and "more days" in s["gate"]
    pp = "/api/v1/strategies/fake/promote"
    r = c.post(pp, headers=phone.sign("POST", pp)).json()
    assert not r["ok"] and "Promotion gate" in r["message"]
    c.post("/api/v1/kill-switch", json={"engaged": True})
    assert "kill switch" in c.post(p, headers=phone.sign("POST", p)).json()["message"]
    assert c.get("/api/v1/dashboard").json()["autoTrade"] is False
