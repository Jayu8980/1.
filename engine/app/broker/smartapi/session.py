"""Session lifecycle: TOTP login, token refresh, and the mandatory daily logout/login cycle.
The TOTP secret is read only here, on the server, and never serialised anywhere."""
from __future__ import annotations

import asyncio
from dataclasses import dataclass
from datetime import datetime, time, timedelta
from typing import Callable
from zoneinfo import ZoneInfo

import pyotp

from app.core.audit import AuditLog
from app.core.errors import AuthError

from .client import SmartAPIClient

IST = ZoneInfo("Asia/Kolkata")


@dataclass
class SessionTokens:
    jwt: str
    refresh: str
    feed: str
    logged_in_at: datetime


class SessionManager:
    def __init__(self, client: SmartAPIClient, client_code: str, mpin: str, totp_secret: str,
                 audit: AuditLog, login_at: str = "08:30", logout_at: str = "23:45",
                 now: Callable[[], datetime] = lambda: datetime.now(IST)):
        self.client = client
        self._code, self._mpin = client_code, mpin
        self._totp = pyotp.TOTP(totp_secret) if totp_secret else None
        self.audit = audit
        self.login_at = time.fromisoformat(login_at)
        self.logout_at = time.fromisoformat(logout_at)
        self._now = now
        self.tokens: SessionTokens | None = None
        self._lock = asyncio.Lock()

    @property
    def is_logged_in(self) -> bool:
        return self.tokens is not None

    async def login(self) -> SessionTokens:
        if not self._totp:
            raise AuthError("TOTP secret not configured")
        async with self._lock:
            code = self._totp.now()
            try:
                d = await self.client.login(self._code, self._mpin, code)
            except Exception as e:
                await self.audit.record("auth.login_failed", error=str(e))
                raise
            self.tokens = SessionTokens(d["jwtToken"], d["refreshToken"], d.get("feedToken", ""), self._now())
            await self.audit.record("auth.login", client=self._code)
            return self.tokens

    async def logout(self) -> None:
        async with self._lock:
            if not self.tokens:
                return
            try:
                await self.client.logout(self._code)
            finally:
                self.tokens = None
                await self.audit.record("auth.logout", client=self._code)

    def needs_daily_relogin(self) -> bool:
        """True if the current session predates today's login time (stale token from yesterday)."""
        if not self.tokens:
            return True
        now = self._now()
        today_login = datetime.combine(now.date(), self.login_at, tzinfo=IST)
        return now >= today_login and self.tokens.logged_in_at < today_login

    async def ensure(self) -> None:
        if self.needs_daily_relogin():
            if self.tokens:
                await self.logout()
            await self.login()

    async def call_with_reauth(self, fn, *args, **kwargs):
        """Run a client call; on an auth error, do one fresh TOTP login and retry once."""
        await self.ensure()
        try:
            return await fn(*args, **kwargs)
        except AuthError:
            await self.audit.record("auth.reauth")
            self.tokens = None
            await self.login()
            return await fn(*args, **kwargs)

    def next_event(self) -> tuple[str, datetime]:
        """Next scheduled lifecycle event ('login' or 'logout') in IST."""
        now = self._now()
        cands = []
        for d in (0, 1):
            day = now.date() + timedelta(days=d)
            cands += [("login", datetime.combine(day, self.login_at, tzinfo=IST)),
                      ("logout", datetime.combine(day, self.logout_at, tzinfo=IST))]
        return min((c for c in cands if c[1] > now), key=lambda c: c[1])

    async def run_daily_cycle(self, stop: asyncio.Event) -> None:
        """Background task: logout at logout_at, fresh TOTP login at login_at, forever."""
        while not stop.is_set():
            kind, when = self.next_event()
            delay = max((when - self._now()).total_seconds(), 0)
            try:
                await asyncio.wait_for(stop.wait(), timeout=delay)
                return
            except asyncio.TimeoutError:
                pass
            try:
                await (self.login() if kind == "login" else self.logout())
            except Exception as e:  # keep the loop alive; Monitor agent alerts on audit events
                await self.audit.record("auth.cycle_error", kind=kind, error=str(e))
