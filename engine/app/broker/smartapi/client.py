"""Thin async REST client for Angel One SmartAPI (no SDK dependency, fully mockable)."""
from __future__ import annotations

from typing import Any

import httpx

from app.core.errors import AuthError, BrokerError
from app.core.ratelimit import SlidingWindowLimiter

LOGIN = "/rest/auth/angelbroking/user/v1/loginByPassword"
REFRESH = "/rest/auth/angelbroking/jwt/v1/generateTokens"
LOGOUT = "/rest/secure/angelbroking/user/v1/logout"
PROFILE = "/rest/secure/angelbroking/user/v1/getProfile"
RMS = "/rest/secure/angelbroking/user/v1/getRMS"
PLACE = "/rest/secure/angelbroking/order/v1/placeOrder"
MODIFY = "/rest/secure/angelbroking/order/v1/modifyOrder"
CANCEL = "/rest/secure/angelbroking/order/v1/cancelOrder"
ORDER_BOOK = "/rest/secure/angelbroking/order/v1/getOrderBook"
TRADE_BOOK = "/rest/secure/angelbroking/order/v1/getTradeBook"
POSITIONS = "/rest/secure/angelbroking/order/v1/getPosition"
HOLDINGS = "/rest/secure/angelbroking/portfolio/v1/getAllHolding"
LTP = "/rest/secure/angelbroking/order/v1/getLtpData"
CANDLES = "/rest/secure/angelbroking/historical/v1/getCandleData"
QUOTE = "/rest/secure/angelbroking/market/v1/quote/"
SEARCH = "/rest/secure/angelbroking/order/v1/searchScrip"

# Error codes that mean the session token is dead.
AUTH_ERRORS = {"AG8001", "AG8002", "AG8003", "AB1010", "AB8050", "AB8051"}


class SmartAPIClient:
    def __init__(self, base_url: str, api_key: str, local_ip: str, public_ip: str, mac: str,
                 timeout: float = 10.0, order_limiter: SlidingWindowLimiter | None = None,
                 transport: httpx.AsyncBaseTransport | None = None):
        self.api_key = api_key
        self._local_ip, self._public_ip, self._mac = local_ip, public_ip, mac
        self.jwt: str | None = None
        self.order_limiter = order_limiter
        self._http = httpx.AsyncClient(base_url=base_url, timeout=timeout, transport=transport)

    async def aclose(self) -> None:
        await self._http.aclose()

    def _headers(self, auth: bool) -> dict[str, str]:
        h = {
            "Content-Type": "application/json", "Accept": "application/json",
            "X-UserType": "USER", "X-SourceID": "WEB",
            "X-ClientLocalIP": self._local_ip, "X-ClientPublicIP": self._public_ip,
            "X-MACAddress": self._mac, "X-PrivateKey": self.api_key,
        }
        if auth:
            if not self.jwt:
                raise AuthError("not logged in")
            h["Authorization"] = f"Bearer {self.jwt}"
        return h

    async def _call(self, method: str, path: str, body: dict | None = None, *, auth: bool = True,
                    is_order: bool = False) -> Any:
        if is_order and self.order_limiter:
            await self.order_limiter.acquire()
        try:
            r = await self._http.request(method, path, json=body, headers=self._headers(auth))
        except httpx.HTTPError as e:
            raise BrokerError(f"network error on {path}: {e}") from e
        if r.status_code in (401, 403):
            raise AuthError(f"HTTP {r.status_code} on {path}")
        try:
            payload = r.json()
        except ValueError:
            raise BrokerError(f"non-JSON response ({r.status_code}) on {path}") from None
        if not payload.get("status", False):
            code = payload.get("errorcode") or payload.get("errorCode")
            exc = AuthError if code in AUTH_ERRORS else BrokerError
            raise exc(payload.get("message", "unknown SmartAPI error"), code)
        return payload.get("data")

    # ------------------------------------------------------------- auth
    async def login(self, client_code: str, mpin: str, totp: str) -> dict:
        data = await self._call("POST", LOGIN, {"clientcode": client_code, "password": mpin, "totp": totp},
                                auth=False)
        self.jwt = data["jwtToken"]
        return data

    async def refresh(self, refresh_token: str) -> dict:
        data = await self._call("POST", REFRESH, {"refreshToken": refresh_token})
        self.jwt = data["jwtToken"]
        return data

    async def logout(self, client_code: str) -> None:
        try:
            await self._call("POST", LOGOUT, {"clientcode": client_code})
        finally:
            self.jwt = None

    async def profile(self) -> dict:
        return await self._call("GET", PROFILE)

    # ------------------------------------------------------------- trading
    async def place_order(self, params: dict) -> dict:
        return await self._call("POST", PLACE, params, is_order=True)

    async def modify_order(self, params: dict) -> dict:
        return await self._call("POST", MODIFY, params, is_order=True)

    async def cancel_order(self, variety: str, order_id: str) -> dict:
        return await self._call("POST", CANCEL, {"variety": variety, "orderid": order_id}, is_order=True)

    async def order_book(self) -> list[dict]:
        return await self._call("GET", ORDER_BOOK) or []

    async def trade_book(self) -> list[dict]:
        return await self._call("GET", TRADE_BOOK) or []

    async def positions(self) -> list[dict]:
        return await self._call("GET", POSITIONS) or []

    async def holdings(self) -> dict:
        return await self._call("GET", HOLDINGS) or {}

    async def rms(self) -> dict:
        return await self._call("GET", RMS) or {}

    async def ltp(self, exchange: str, symbol: str, token: str) -> dict:
        return await self._call("POST", LTP, {"exchange": exchange, "tradingsymbol": symbol, "symboltoken": token})

    async def quote_ltp(self, exchange_tokens: dict[str, list[str]]) -> list[dict]:
        """Batch LTP: {"NSE": ["3045", ...]} -> [{exchange, tradingSymbol, symbolToken, ltp}, ...] (<=50 tokens)."""
        d = await self._call("POST", QUOTE, {"mode": "LTP", "exchangeTokens": exchange_tokens}) or {}
        return d.get("fetched", [])

    async def search(self, exchange: str, query: str) -> list[dict]:
        return await self._call("POST", SEARCH, {"exchange": exchange, "searchscrip": query}) or []

    async def candles(self, exchange: str, token: str, interval: str, from_: str, to: str) -> list[list]:
        return await self._call("POST", CANDLES, {"exchange": exchange, "symboltoken": token,
                                                   "interval": interval, "fromdate": from_, "todate": to}) or []
