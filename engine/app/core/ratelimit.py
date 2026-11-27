"""Sliding-window rate limiter: at most `limit` acquisitions in ANY `window` seconds.

A token bucket can allow 2x burst at a window boundary; a sliding log cannot,
which is what we want to stay under the exchange orders-per-second cap.
"""
from __future__ import annotations

import asyncio
import time
from collections import deque
from typing import Callable


class SlidingWindowLimiter:
    def __init__(self, limit: int, window: float = 1.0,
                 clock: Callable[[], float] = time.monotonic,
                 sleep: Callable[[float], "asyncio.Future"] = asyncio.sleep):
        if limit < 1:
            raise ValueError("limit must be >= 1")
        self.limit = limit
        self.window = window
        self._clock = clock
        self._sleep = sleep
        self._stamps: deque[float] = deque()
        self._lock = asyncio.Lock()

    async def acquire(self) -> None:
        async with self._lock:
            while True:
                now = self._clock()
                while self._stamps and now - self._stamps[0] >= self.window:
                    self._stamps.popleft()
                if len(self._stamps) < self.limit:
                    self._stamps.append(now)
                    return
                await self._sleep(self.window - (now - self._stamps[0]) + 1e-4)
