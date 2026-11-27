"""Market-data hub: one place every tick flows through.

Sources (first that accepts a contract wins):
  DeltaWsSource   - Delta Exchange public WebSocket (crypto)
  AngelWsSource   - Angel One SmartStream v2 WebSocket (needs Angel session)
  PollingSource   - batch REST quotes through any connected broker (fallback)
Ticks update the paper broker (so paper fills use real prices), the latest-price table,
and every subscriber (the app's /stream WebSocket, the equity recorder).
"""
from __future__ import annotations

import asyncio
import logging
from dataclasses import dataclass
from datetime import datetime, timezone
from decimal import Decimal
from typing import Awaitable, Callable, Protocol

from app.core.models import Contract

log = logging.getLogger("marketdata")


@dataclass(frozen=True)
class Tick:
    key: str
    symbol: str
    exchange: str
    ltp: Decimal
    ts: datetime
    source: str

    def to_json(self) -> dict:
        return {"type": "tick", "key": self.key, "symbol": self.symbol, "exchange": self.exchange,
                "ltp": float(self.ltp), "ts": int(self.ts.timestamp() * 1000), "source": self.source}


Emit = Callable[[Contract, Decimal, str], Awaitable[None]]


class Source(Protocol):
    name: str

    def accepts(self, c: Contract) -> bool: ...
    async def available(self) -> bool: ...
    async def run(self, contracts: list[Contract], emit: Emit, stop: asyncio.Event) -> None: ...


class MarketDataHub:
    def __init__(self, sources: list[Source], on_tick: list[Callable[[Contract, Decimal], Awaitable]] | None = None,
                 resync_s: float = 5.0):
        self.sources = sources
        self.contracts: dict[str, Contract] = {}
        self.latest: dict[str, Tick] = {}
        self.assignment: dict[str, str] = {}      # contract key -> source name
        self._on_tick = list(on_tick or [])
        self._subs: set[asyncio.Queue] = set()
        self._resync_s = resync_s
        self._tasks: dict[str, tuple[asyncio.Task, asyncio.Event, frozenset[str]]] = {}
        self._wake = asyncio.Event()
        self._main: asyncio.Task | None = None

    # ------------------------------------------------------------ subscriptions
    def set_contracts(self, contracts: list[Contract]) -> None:
        self.contracts = {c.key: c for c in contracts}
        self._wake.set()

    def add(self, c: Contract) -> None:
        self.contracts[c.key] = c; self._wake.set()

    def remove(self, key: str) -> None:
        self.contracts.pop(key, None); self.latest.pop(key, None); self._wake.set()

    def subscribe(self) -> asyncio.Queue:
        q: asyncio.Queue = asyncio.Queue(maxsize=1000)
        self._subs.add(q)
        return q

    def unsubscribe(self, q: asyncio.Queue) -> None:
        self._subs.discard(q)

    def add_listener(self, fn: Callable[[Contract, Decimal], Awaitable]) -> None:
        self._on_tick.append(fn)

    # ------------------------------------------------------------ ticks
    async def emit(self, c: Contract, ltp: Decimal, source: str) -> None:
        t = Tick(c.key, c.symbol, c.exchange.value, Decimal(ltp), datetime.now(timezone.utc), source)
        self.latest[c.key] = t
        for fn in self._on_tick:
            try:
                await fn(c, t.ltp)
            except Exception:
                log.exception("tick listener failed")
        msg = t.to_json()
        for q in list(self._subs):
            if q.full():           # slow client: drop oldest, never block the feed
                q.get_nowait()
            q.put_nowait(msg)

    def status(self) -> dict:
        return {"sources": {n: sorted(keys) for n, (_, _, keys) in self._tasks.items()},
                "unassigned": sorted(set(self.contracts) - set(self.assignment))}

    # ------------------------------------------------------------ lifecycle
    async def _plan(self) -> dict[str, list[Contract]]:
        plan: dict[str, list[Contract]] = {}
        avail = {s.name: await _safe_available(s) for s in self.sources}
        self.assignment = {}
        for c in self.contracts.values():
            for s in self.sources:
                if avail[s.name] and s.accepts(c):
                    plan.setdefault(s.name, []).append(c); self.assignment[c.key] = s.name
                    break
        return plan

    async def _sync(self) -> None:
        plan = await self._plan()
        by_name = {s.name: s for s in self.sources}
        for name in list(self._tasks):
            task, stop, keys = self._tasks[name]
            want = frozenset(c.key for c in plan.get(name, []))
            if task.done() or want != keys:
                stop.set(); task.cancel()
                self._tasks.pop(name)
        for name, cs in plan.items():
            if name not in self._tasks:
                stop = asyncio.Event()
                task = asyncio.create_task(self._run_source(by_name[name], cs, stop))
                self._tasks[name] = (task, stop, frozenset(c.key for c in cs))

    async def _run_source(self, s: Source, cs: list[Contract], stop: asyncio.Event) -> None:
        delay = 1.0
        while not stop.is_set():
            try:
                await s.run(cs, self.emit, stop)
                delay = 1.0
            except asyncio.CancelledError:
                raise
            except Exception as e:
                log.warning("source %s failed: %s (retry in %.0fs)", s.name, e, delay)
                await asyncio.sleep(delay)
                delay = min(delay * 2, 60)

    async def run(self, stop: asyncio.Event) -> None:
        while not stop.is_set():
            await self._sync()
            self._wake.clear()
            try:
                await asyncio.wait_for(self._wake.wait(), self._resync_s)
            except asyncio.TimeoutError:
                pass
        for task, st, _ in self._tasks.values():
            st.set(); task.cancel()
        self._tasks.clear()


async def _safe_available(s: Source) -> bool:
    try:
        return await s.available()
    except Exception:
        return False
