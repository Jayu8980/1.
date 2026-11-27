"""Static-IP compliance guard. LIVE orders are blocked unless our public egress IP
matches the IP registered with SmartAPI."""
from __future__ import annotations

import time
from typing import Awaitable, Callable

import httpx

from .errors import OrderBlocked


async def fetch_public_ip(url: str, timeout: float = 5.0) -> str:
    async with httpx.AsyncClient(timeout=timeout) as c:
        r = await c.get(url)
        r.raise_for_status()
        return r.text.strip()


class StaticIPGuard:
    def __init__(self, expected_ip: str, fetcher: Callable[[], Awaitable[str]],
                 ttl_s: float = 30.0, clock: Callable[[], float] = time.monotonic):
        self.expected_ip = expected_ip.strip()
        self._fetch = fetcher
        self.ttl_s = ttl_s
        self._clock = clock
        self._cached: tuple[float, str] | None = None

    async def current_ip(self) -> str:
        now = self._clock()
        if self._cached and self.ttl_s > 0 and now - self._cached[0] < self.ttl_s:
            return self._cached[1]
        ip = await self._fetch()
        self._cached = (now, ip)
        return ip

    async def assert_ok(self) -> str:
        if not self.expected_ip:
            raise OrderBlocked("static IP not configured (ENGINE_STATIC_IP)", "IP_NOT_CONFIGURED")
        try:
            ip = await self.current_ip()
        except Exception as e:  # fail closed
            self._cached = None
            raise OrderBlocked(f"could not verify public IP: {e}", "IP_CHECK_FAILED") from e
        if ip != self.expected_ip:
            self._cached = None  # re-check next time, don't cache a bad IP
            raise OrderBlocked(f"public IP {ip} != registered {self.expected_ip}", "IP_MISMATCH")
        return ip
