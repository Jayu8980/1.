"""Angel One SmartAPI wrapped in the common connection lifecycle (TOTP login runs on the server)."""
from __future__ import annotations

from datetime import time

from app.broker.smartapi.broker import SmartAPIBroker
from app.broker.smartapi.session import SessionManager

from .base import AuthKind, BrokerInfo, ConnectionStatus

INFO = BrokerInfo("angelone", "Angel One (SmartAPI)", AuthKind.TOTP, ("NSE", "BSE", "NFO", "BFO", "MCX"),
                  10, None, "https://smartapi.angelbroking.com/docs",
                  ("ENGINE_SMARTAPI_API_KEY", "ENGINE_SMARTAPI_CLIENT_CODE", "ENGINE_SMARTAPI_MPIN",
                   "ENGINE_SMARTAPI_TOTP_SECRET"))


class AngelOneBroker(SmartAPIBroker):
    info = INFO
    name = "angelone"

    def __init__(self, session: SessionManager, configured: bool):
        super().__init__(session)
        self._configured = configured

    def configured(self) -> bool:
        return self._configured

    def login_url(self, state: str) -> None:
        return None

    async def complete_login(self, params: dict[str, str]):
        await self.s.login()

    async def status(self) -> ConnectionStatus:
        if not self._configured:
            return ConnectionStatus(INFO.id, False, False, message="API key / TOTP not set on server")
        if not self.s.is_logged_in:
            return ConnectionStatus(INFO.id, True, False, message="Not logged in")
        return ConnectionStatus(INFO.id, True, True, self.s._code,
                                self.s.tokens.logged_in_at.isoformat() if self.s.tokens else None,
                                "Connected (daily TOTP login)")

    async def disconnect(self) -> None:
        await self.s.logout()
