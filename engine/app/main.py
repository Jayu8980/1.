"""Engine entrypoint: brokers, paper broker, gateway, market-data hub, portfolio, risk, API."""
import asyncio
import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.api.data_routes import router as data_router
from app.api.ext_routes import router as ext_router
from app.api.routes import router
from app.mcp_server import ApiKeyMiddleware, LocalBackend, build_mcp
from app.services.apikeys import ApiKeyStore
from app.services.external import External
from app.services.intents import IntentStore
from app.api.security import DeviceKeys, Security
from app.broker.paper import PaperBroker
from app.broker.registry import BrokerManager, build_brokers
from app.broker.tokens import TokenStore
from app.config import Settings, get_settings
from app.core.audit import AuditLog
from app.core.enums import Exchange
from app.core.ipguard import StaticIPGuard, fetch_public_ip
from app.execution.gateway import ExecutionGateway
from app.autotrade.engine import AutoSettings, AutoTrader
from app.autotrade.store import StrategyStore
from app.autotrade.strategies import ALL as STRATEGIES
from app.autotrade.warmup import warm
from app.broker.adapters.delta import REGIONS as DELTA_REGIONS
from app.marketdata.hub import MarketDataHub
from app.marketdata.sources import AngelWsSource, DeltaWsSource, PollingSource
from app.services.instruments import InstrumentSearch
from app.services.portfolio import Portfolio
from app.services.risk import RiskEngine
from app.services.watchlist import NIFTY, Watchlist

log = logging.getLogger("engine")


def build_state(app: FastAPI, s: Settings, transport=None, ip_fetcher=None, sources=None) -> None:
    audit = AuditLog(s.audit_log_path)
    tokens = TokenStore(s.token_store_path)
    brokers = build_brokers(s, tokens, audit, transport=transport)
    ip_guard = StaticIPGuard(s.static_ip, ip_fetcher or (lambda: fetch_public_ip(s.ip_check_url)), s.ip_check_ttl_s)
    paper = PaperBroker(s.paper_starting_capital, s.paper_slippage_bps)
    risk = RiskEngine(s.risk_limits_path)
    gateway = ExecutionGateway(paper, risk, audit, live=brokers[s.live_broker], ip_guard=ip_guard,
                               allow_live=s.allow_live, live_confirm_phrase=s.live_confirm_phrase)
    manager = BrokerManager(brokers, s.live_broker, audit, gateway)

    def data_broker():
        """Quotes come from the active broker if connected, else any connected Indian broker."""
        return manager.active if manager.active_id != "delta" else brokers["angelone"]

    angel = brokers["angelone"]
    if sources is None:
        sources = [DeltaWsSource(s.delta_region),
                   AngelWsSource(angel.s, s.smartapi_api_key.get_secret_value(), s.smartapi_client_code),
                   PollingSource("poll-active", data_broker, s.poll_interval_s)]
        sources += [PollingSource(f"poll-{bid}", (lambda b=b: b), s.poll_interval_s)
                    for bid, b in brokers.items() if bid not in ("angelone", "delta")]
    hub = MarketDataHub(sources)
    hub.add_listener(lambda c, px: paper.on_tick(c, px))   # paper fills use real prices
    watchlist = Watchlist(s.watchlist_path)
    hub.set_contracts(watchlist.all())
    portfolio = Portfolio(gateway, hub, s.equity_curve_path, NIFTY.key)
    risk.portfolio = portfolio
    store = StrategyStore(s.strategies_path, s.trade_journal_path, [x.id for x in STRATEGIES])
    autotrader = AutoTrader(gateway, risk, store, audit, AutoSettings(
        min_confidence=s.auto_min_confidence, max_open_trades=s.auto_max_open_trades,
        max_trades_per_day=s.auto_max_trades_per_day, time_stop_bars=s.auto_time_stop_bars,
        protection_pct=s.live_market_protection_pct, allow_crypto=s.auto_allow_crypto))
    hub.add_listener(autotrader.on_tick)
    portfolio.autotrader = autotrader
    app.state.autotrader = autotrader

    app.state.settings, app.state.audit, app.state.gateway = s, audit, gateway
    app.state.brokers, app.state.hub, app.state.watchlist = manager, hub, watchlist
    app.state.portfolio, app.state.risk = portfolio, risk
    app.state.instruments = InstrumentSearch(angel, s.delta_region, transport=transport)
    app.state.apikeys = ApiKeyStore(s.apikeys_path, s.api_rate_per_min)
    app.state.intents = IntentStore(s.intent_ttl_s)
    app.state.external = External(app.state)
    app.state.security = Security(s.jwt_secret.get_secret_value(), s.jwt_ttl_min, DeviceKeys(s.device_keys_path))


async def _every(seconds: float, fn, stop: asyncio.Event):
    while not stop.is_set():
        try:
            await fn()
        except Exception as e:
            log.warning("%s failed: %s", getattr(fn, "__name__", fn), e)
        try:
            await asyncio.wait_for(stop.wait(), seconds)
        except asyncio.TimeoutError:
            pass


async def risk_monitor(app: FastAPI) -> None:
    """Auto kill switch on breach. Runs server-side regardless of the app."""
    st = app.state
    breaches = await st.risk.evaluate()
    if breaches and not st.gateway.kill_switch:
        await st.gateway.engage_kill_switch("risk-engine", "; ".join(breaches))


def start_background(app: FastAPI) -> tuple[asyncio.Event, list[asyncio.Task]]:
    s, st = app.state.settings, app.state
    stop = asyncio.Event()
    tasks = [asyncio.create_task(_every(s.equity_record_interval_s, st.portfolio.record, stop)),
             asyncio.create_task(_every(s.risk_monitor_interval_s, lambda: risk_monitor(app), stop))]
    if s.feed_enabled:
        tasks.append(asyncio.create_task(st.hub.run(stop)))
        # re-seed bars every 30 min (covers broker logins that happen after startup)
        tasks.append(asyncio.create_task(_every(1800, lambda: warm(
            st.autotrader, st.watchlist.all(), st.brokers.get("angelone"), DELTA_REGIONS[s.delta_region]), stop)))
        angel = st.brokers.get("angelone")
        if angel.configured():
            tasks.append(asyncio.create_task(angel.s.run_daily_cycle(stop)))
    return stop, tasks


def mount_mcp(app: FastAPI, s: Settings):
    """Mount the MCP server at /mcp/ (streamable HTTP, stateless JSON) behind the API-key guard."""
    from mcp.server.transport_security import TransportSecuritySettings
    mcp = build_mcp(LocalBackend(app.state))
    hosts = ["127.0.0.1:*", "localhost:*", "testserver"] + ([s.domain, f"{s.domain}:*"] if s.domain else [])
    inner = mcp.streamable_http_app(streamable_http_path="/", stateless_http=True, json_response=True,
                                    transport_security=TransportSecuritySettings(
                                        enable_dns_rebinding_protection=True, allowed_hosts=hosts,
                                        allowed_origins=[f"https://{s.domain}"] if s.domain else []))
    app.mount("/mcp", ApiKeyMiddleware(inner, lambda: app.state.apikeys))
    return mcp


_mcp_holder: dict = {}


@asynccontextmanager
async def lifespan(app: FastAPI):
    if not hasattr(app.state, "gateway"):
        build_state(app, get_settings())
    stop, tasks = start_background(app)
    mcp = _mcp_holder.get("mcp")
    if mcp is not None:
        async with mcp.session_manager.run():
            yield
    else:
        yield
    stop.set()
    for t in tasks:
        t.cancel()


app = FastAPI(title="Trading Engine", version="0.4.0", lifespan=lifespan)
app.include_router(router)
app.include_router(data_router)
app.include_router(ext_router)


def _init_mcp():
    s = get_settings()
    if s.mcp_enabled:
        # state is filled lazily in lifespan; LocalBackend reads app.state at call time
        _mcp_holder["mcp"] = mount_mcp(app, s)


_init_mcp()


@app.get("/health")
async def health():
    s = get_settings()
    return {"status": "ok", "mode": s.mode, "allow_live": s.allow_live, "live_broker": s.live_broker}
