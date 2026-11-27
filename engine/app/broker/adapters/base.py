"""Shared plumbing for REST broker adapters + the connection-lifecycle contract."""
from __future__ import annotations

from abc import abstractmethod
from dataclasses import dataclass, field
from datetime import datetime, time, timezone
from enum import StrEnum
from typing import Any

import httpx

from app.broker.base import Broker
from app.broker.tokens import BrokerToken, TokenStore
from app.core.errors import AuthError, BrokerError
from app.core.models import Contract
from app.core.ratelimit import SlidingWindowLimiter


class AuthKind(StrEnum):
    OAUTH = "OAUTH"        # browser login -> redirect to engine callback (Zerodha, Upstox, Fyers)
    TOTP = "TOTP"          # server logs in itself with TOTP (Angel One)
    TOKEN = "TOKEN"        # long-lived access token configured on server (Dhan)


@dataclass(frozen=True)
class BrokerInfo:
    id: str
    name: str
    auth: AuthKind
    segments: tuple[str, ...]
    max_orders_per_sec: int          # broker's own API cap; engine uses min(this, ENGINE_ORDERS_PER_SEC)
    token_expiry_ist: time | None    # daily expiry for OAUTH tokens
    docs: str
    required_settings: tuple[str, ...] = field(default=())


@dataclass
class ConnectionStatus:
    broker: str
    configured: bool
    connected: bool
    account: str = ""
    expires_at: str | None = None
    message: str = ""


def now_utc() -> datetime:
    return datetime.now(timezone.utc)


class RestBroker(Broker):
    """Base for adapters: httpx client, order rate limiter, token store, error mapping."""

    info: BrokerInfo

    def __init__(self, tokens: TokenStore, *, base_url: str, orders_per_sec: int,
                 transport: httpx.AsyncBaseTransport | None = None, timeout: float = 10.0):
        self.tokens = tokens
        self.name = self.info.id
        self.limiter = SlidingWindowLimiter(min(orders_per_sec, self.info.max_orders_per_sec))
        self.http = httpx.AsyncClient(base_url=base_url, timeout=timeout, transport=transport)
        self._known: dict[str, Any] = {}

    # ----------------------------------------------------- connection lifecycle
    @abstractmethod
    def configured(self) -> bool: ...

    def login_url(self, state: str) -> str | None:   # OAUTH brokers override
        return None

    async def complete_login(self, params: dict[str, str]) -> BrokerToken:  # OAUTH / TOKEN
        raise BrokerError(f"{self.info.name} does not use browser login")

    def token(self) -> BrokerToken:
        t = self.tokens.get(self.info.id)
        if t is None or t.expired():
            raise AuthError(f"{self.info.name}: not connected or session expired — reconnect from the app")
        return t

    async def status(self) -> ConnectionStatus:
        if not self.configured():
            return ConnectionStatus(self.info.id, False, False, message="API keys not set on server")
        t = self.tokens.get(self.info.id)
        if t is None:
            return ConnectionStatus(self.info.id, True, False, message="Not connected")
        if t.expired():
            return ConnectionStatus(self.info.id, True, False, t.account, t.expires_at, "Session expired")
        return ConnectionStatus(self.info.id, True, True, t.account, t.expires_at, "Connected")

    async def disconnect(self) -> None:
        self.tokens.delete(self.info.id)

    # ----------------------------------------------------- http helpers
    @abstractmethod
    def auth_headers(self) -> dict[str, str]: ...

    def contract_id(self, c: Contract) -> str:
        cid = c.ids.get(self.info.id)
        if not cid:
            raise BrokerError(f"{c.key} has no {self.info.name} instrument id — load the instrument master")
        return cid

    async def request(self, method: str, path: str, *, order: bool = False, auth: bool = True,
                      json: Any = None, data: Any = None, params: Any = None,
                      headers: dict | None = None, base_url: str | None = None) -> Any:
        if order:
            await self.limiter.acquire()
        h = {"Accept": "application/json", **(self.auth_headers() if auth else {}), **(headers or {})}
        url = (base_url.rstrip("/") + path) if base_url else path
        try:
            r = await self.http.request(method, url, json=json, data=data, params=params, headers=h)
        except httpx.HTTPError as e:
            raise BrokerError(f"{self.info.name}: network error on {path}: {e}") from e
        try:
            body = r.json()
        except ValueError:
            body = {"raw": r.text}
        if r.status_code in (401, 403):
            raise AuthError(f"{self.info.name}: {self.error_message(body) or r.status_code}")
        if r.status_code >= 400 or not self.ok(body):
            raise BrokerError(f"{self.info.name}: {self.error_message(body) or r.status_code}",
                              self.error_code(body))
        return self.unwrap(body)

    # Adapters override these four for their envelope format.
    def ok(self, body: Any) -> bool:
        return True

    def unwrap(self, body: Any) -> Any:
        return body

    def error_message(self, body: Any) -> str:
        return str(body.get("message") or body.get("errorMessage") or "") if isinstance(body, dict) else ""

    def error_code(self, body: Any) -> str | None:
        return (body.get("error_type") or body.get("errorCode") or body.get("code")) if isinstance(body, dict) else None

    async def aclose(self):
        await self.http.aclose()
