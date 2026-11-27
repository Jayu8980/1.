"""Broker registry + manager: builds every configured adapter, runs connect/login flows,
and decides which broker the LIVE path uses. Switching brokers is only allowed in PAPER."""
from __future__ import annotations

import secrets
import time as _time
from dataclasses import asdict
from typing import Any

import httpx

from app.broker.adapters.angelone import AngelOneBroker
from app.broker.adapters.base import AuthKind, RestBroker
from app.broker.adapters.delta import DeltaBroker
from app.broker.adapters.dhan import DhanBroker
from app.broker.adapters.fyers import FyersBroker
from app.broker.adapters.upstox import UpstoxBroker
from app.broker.adapters.zerodha import ZerodhaBroker
from app.broker.smartapi.client import SmartAPIClient
from app.broker.smartapi.session import SessionManager
from app.broker.tokens import TokenStore
from app.config import Settings
from app.core.audit import AuditLog
from app.core.errors import BrokerError, OrderBlocked
from app.core.ratelimit import SlidingWindowLimiter

SUPPORTED = ("angelone", "zerodha", "upstox", "dhan", "fyers", "delta")


def redirect_uri(s: Settings, broker_id: str) -> str:
    return f"{s.public_base_url.rstrip('/')}/api/v1/brokers/{broker_id}/callback" if s.public_base_url else ""


def build_brokers(s: Settings, tokens: TokenStore, audit: AuditLog,
                  transport: httpx.AsyncBaseTransport | None = None) -> dict[str, Any]:
    ops = s.orders_per_sec
    smart = SmartAPIClient(s.smartapi_base_url, s.smartapi_api_key.get_secret_value(), s.smartapi_local_ip,
                           s.static_ip or "0.0.0.0", s.smartapi_mac, s.smartapi_timeout_s,
                           order_limiter=SlidingWindowLimiter(ops), transport=transport)
    session = SessionManager(smart, s.smartapi_client_code, s.smartapi_mpin.get_secret_value(),
                             s.smartapi_totp_secret.get_secret_value(), audit,
                             s.daily_login_time_ist, s.daily_logout_time_ist)
    angel_ok = bool(s.smartapi_api_key.get_secret_value() and s.smartapi_client_code and
                    s.smartapi_totp_secret.get_secret_value())
    return {
        "angelone": AngelOneBroker(session, angel_ok),
        "zerodha": ZerodhaBroker(tokens, s.zerodha_api_key, s.zerodha_api_secret.get_secret_value(), ops, transport=transport),
        "upstox": UpstoxBroker(tokens, s.upstox_client_id, s.upstox_client_secret.get_secret_value(),
                               redirect_uri(s, "upstox"), ops, transport=transport),
        "dhan": DhanBroker(tokens, s.dhan_client_id, s.dhan_access_token.get_secret_value(), ops, transport=transport),
        "fyers": FyersBroker(tokens, s.fyers_app_id, s.fyers_secret.get_secret_value(),
                             redirect_uri(s, "fyers"), ops, transport=transport),
        "delta": DeltaBroker(tokens, s.delta_api_key, s.delta_api_secret.get_secret_value(), s.delta_region,
                             s.delta_settle_asset, ops, transport=transport),
    }


class BrokerManager:
    STATE_TTL_S = 600

    def __init__(self, brokers: dict[str, Any], active_id: str, audit: AuditLog, gateway=None):
        if active_id not in brokers:
            raise ValueError(f"unknown broker {active_id}; supported: {', '.join(brokers)}")
        self.brokers, self.active_id, self.audit, self.gateway = brokers, active_id, audit, gateway
        self._states: dict[str, tuple[str, str, float]] = {}   # state -> (broker, actor, created)

    def get(self, broker_id: str):
        try:
            return self.brokers[broker_id]
        except KeyError:
            raise BrokerError(f"unknown broker '{broker_id}'") from None

    @property
    def active(self):
        return self.brokers[self.active_id]

    async def list(self) -> list[dict]:
        out = []
        for bid, b in self.brokers.items():
            st = asdict(await b.status())
            i = b.info
            out.append({"id": bid, "name": i.name, "auth": i.auth.value, "segments": list(i.segments),
                        "docs": i.docs, "requiredSettings": list(i.required_settings), "active": bid == self.active_id,
                        "configured": st["configured"], "connected": st["connected"], "account": st["account"],
                        "expiresAt": st["expires_at"], "message": st["message"]})
        return out

    async def start_connect(self, broker_id: str, actor: str) -> dict:
        """OAUTH -> returns a login URL to open in the browser. TOTP/TOKEN -> connects now."""
        b = self.get(broker_id)
        if not b.configured():
            raise BrokerError(f"{b.info.name} is not configured on the server: set {', '.join(b.info.required_settings)}")
        if b.info.auth is AuthKind.OAUTH:
            self._gc()
            state = secrets.token_urlsafe(24)
            self._states[state] = (broker_id, actor, _time.monotonic())
            await self.audit.record("broker.login_started", broker=broker_id, actor=actor)
            return {"ok": True, "loginUrl": b.login_url(state), "message": "Complete the login in your browser"}
        await b.complete_login({})
        await self.audit.record("broker.connected", broker=broker_id, actor=actor)
        return {"ok": True, "loginUrl": None, "message": f"{b.info.name} connected"}

    async def finish_oauth(self, broker_id: str, params: dict[str, str]) -> str:
        state = params.get("state", "")
        rec = self._states.pop(state, None)
        if rec is None or rec[0] != broker_id or _time.monotonic() - rec[2] > self.STATE_TTL_S:
            await self.audit.record("broker.login_rejected", broker=broker_id, reason="bad or expired state")
            raise BrokerError("Login link expired or invalid — start again from the app")
        b = self.get(broker_id)
        try:
            tok = await b.complete_login(params)
        except Exception as e:
            await self.audit.record("broker.login_failed", broker=broker_id, error=str(e))
            raise
        await self.audit.record("broker.connected", broker=broker_id, actor=rec[1], account=tok.account)
        return tok.account

    async def disconnect(self, broker_id: str, actor: str) -> None:
        await self.get(broker_id).disconnect()
        await self.audit.record("broker.disconnected", broker=broker_id, actor=actor)

    async def activate(self, broker_id: str, actor: str) -> None:
        b = self.get(broker_id)
        if self.gateway is not None:
            if self.gateway.mode.value == "LIVE":
                raise OrderBlocked("switch to PAPER before changing the live broker", "BROKER_SWITCH_LIVE")
            if not (await b.status()).connected:
                raise OrderBlocked(f"{b.info.name} is not connected", "BROKER_NOT_CONNECTED")
            self.gateway.live = b
        prev, self.active_id = self.active_id, broker_id
        await self.audit.record("broker.activated", broker=broker_id, previous=prev, actor=actor)

    def _gc(self):
        now = _time.monotonic()
        for k in [k for k, v in self._states.items() if now - v[2] > self.STATE_TTL_S]:
            self._states.pop(k, None)
