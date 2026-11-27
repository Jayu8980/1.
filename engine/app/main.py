"""Engine entrypoint: builds the paper broker, every broker adapter, the gateway and the API."""
from contextlib import asynccontextmanager
from decimal import Decimal

from fastapi import FastAPI

from app.api.routes import router
from app.api.security import DeviceKeys, Security
from app.broker.paper import PaperBroker
from app.broker.registry import BrokerManager, build_brokers
from app.broker.tokens import TokenStore
from app.config import Settings, get_settings
from app.core.audit import AuditLog
from app.core.ipguard import StaticIPGuard, fetch_public_ip
from app.execution.gateway import ExecutionGateway
from app.execution.risk_hook import HardLimits


def build_state(app: FastAPI, s: Settings, transport=None, ip_fetcher=None) -> None:
    audit = AuditLog(s.audit_log_path)
    tokens = TokenStore(s.token_store_path)
    brokers = build_brokers(s, tokens, audit, transport=transport)
    ip_guard = StaticIPGuard(s.static_ip, ip_fetcher or (lambda: fetch_public_ip(s.ip_check_url)), s.ip_check_ttl_s)
    paper = PaperBroker(s.paper_starting_capital, s.paper_slippage_bps)
    gateway = ExecutionGateway(paper, HardLimits(Decimal("50000")), audit, live=brokers[s.live_broker],
                               ip_guard=ip_guard, allow_live=s.allow_live, live_confirm_phrase=s.live_confirm_phrase)
    app.state.settings = s
    app.state.audit = audit
    app.state.gateway = gateway
    app.state.brokers = BrokerManager(brokers, s.live_broker, audit, gateway)
    app.state.security = Security(s.jwt_secret.get_secret_value(), s.jwt_ttl_min, DeviceKeys(s.device_keys_path))


@asynccontextmanager
async def lifespan(app: FastAPI):
    if not hasattr(app.state, "gateway"):
        build_state(app, get_settings())
    yield


app = FastAPI(title="Trading Engine", version="0.2.0", lifespan=lifespan)
app.include_router(router)


@app.get("/health")
async def health():
    s = get_settings()
    return {"status": "ok", "mode": s.mode, "allow_live": s.allow_live, "live_broker": s.live_broker}
