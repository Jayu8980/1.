from __future__ import annotations

import asyncio
import json
import struct
import uuid
from decimal import Decimal
from typing import Callable

import websockets

from app.core.enums import Exchange
from app.core.models import Contract

from .hub import Emit

INDIAN = {Exchange.NSE, Exchange.BSE, Exchange.NFO, Exchange.BFO}


class PollingSource:
    """Batch REST quotes through a broker (fallback for any broker). Default every 2 s."""

    def __init__(self, name: str, broker_fn: Callable, interval_s: float = 2.0, exchanges: set[Exchange] = INDIAN):
        self.name, self._broker_fn, self.interval_s, self.exchanges = name, broker_fn, interval_s, exchanges

    def accepts(self, c: Contract) -> bool:
        return c.exchange in self.exchanges

    async def available(self) -> bool:
        b = self._broker_fn()
        return b is not None and (not hasattr(b, "status") or (await b.status()).connected)

    async def run(self, contracts: list[Contract], emit: Emit, stop: asyncio.Event) -> None:
        by_key = {c.key: c for c in contracts}
        while not stop.is_set():
            b = self._broker_fn()
            prices = await b.ltps(contracts)
            for k, px in prices.items():
                await emit(by_key[k], px, self.name)
            try:
                await asyncio.wait_for(stop.wait(), self.interval_s)
            except asyncio.TimeoutError:
                pass


# ------------------------------------------------------------------ Delta Exchange
DELTA_WS = {"india": "wss://socket.india.delta.exchange", "global": "wss://socket.delta.exchange",
            "india-testnet": "wss://socket-ind.testnet.deltaex.org", "global-testnet": "wss://testnet-socket.delta.exchange"}


def parse_delta(msg: dict) -> tuple[str, Decimal] | None:
    if msg.get("type") != "v2/ticker" or "symbol" not in msg:
        return None
    px = msg.get("mark_price") or msg.get("close")
    return (msg["symbol"], Decimal(str(px))) if px not in (None, "") else None


class DeltaWsSource:
    name = "delta-ws"

    def __init__(self, region: str = "india", connect=websockets.connect):
        self.url = DELTA_WS[region]
        self._connect = connect

    def accepts(self, c: Contract) -> bool:
        return c.exchange is Exchange.DELTA

    async def available(self) -> bool:
        return True   # public market data, no login needed

    async def run(self, contracts: list[Contract], emit: Emit, stop: asyncio.Event) -> None:
        by_sym = {(c.ids.get("delta") or c.symbol): c for c in contracts}
        async with self._connect(self.url, ping_interval=20) as ws:
            await ws.send(json.dumps({"type": "subscribe", "payload": {
                "channels": [{"name": "v2/ticker", "symbols": list(by_sym)}]}}))
            while not stop.is_set():
                raw = await ws.recv()
                p = parse_delta(json.loads(raw))
                if p and p[0] in by_sym:
                    await emit(by_sym[p[0]], p[1], self.name)


# ------------------------------------------------------------------ Angel One SmartStream v2
ANGEL_WS = "wss://smartapisocket.angelone.in/smart-stream"
ANGEL_EXCH = {Exchange.NSE: 1, Exchange.NFO: 2, Exchange.BSE: 3, Exchange.BFO: 4}


def parse_angel_ltp(b: bytes) -> tuple[int, str, int, Decimal] | None:
    """LTP-mode binary packet (little-endian):
    [0] mode, [1] exchange type, [2:27] token (null padded), [27:35] seq, [35:43] exch ts ms, [43:51] LTP in paise."""
    if len(b) < 51:
        return None
    exch = b[1]
    token = b[2:27].split(b"\x00", 1)[0].decode()
    ts_ms = struct.unpack_from("<q", b, 35)[0]
    ltp = struct.unpack_from("<q", b, 43)[0]
    return exch, token, ts_ms, Decimal(ltp) / 100


class AngelWsSource:
    name = "angelone-ws"

    def __init__(self, session, api_key: str, client_code: str, connect=websockets.connect):
        self.session, self.api_key, self.client_code = session, api_key, client_code
        self._connect = connect

    def accepts(self, c: Contract) -> bool:
        return c.exchange in ANGEL_EXCH and bool(c.token)

    async def available(self) -> bool:
        return self.session.is_logged_in and bool(self.session.tokens.feed)

    async def run(self, contracts: list[Contract], emit: Emit, stop: asyncio.Event) -> None:
        t = self.session.tokens
        headers = {"Authorization": t.jwt, "x-api-key": self.api_key, "x-client-code": self.client_code,
                   "x-feed-token": t.feed}
        by_tok = {(ANGEL_EXCH[c.exchange], c.token): c for c in contracts}
        token_list: dict[int, list[str]] = {}
        for ex, tok in by_tok:
            token_list.setdefault(ex, []).append(tok)
        async with self._connect(ANGEL_WS, additional_headers=headers, ping_interval=None) as ws:
            await ws.send(json.dumps({"correlationID": uuid.uuid4().hex[:10], "action": 1, "params": {
                "mode": 1, "tokenList": [{"exchangeType": ex, "tokens": toks} for ex, toks in token_list.items()]}}))

            async def heartbeat():
                while True:
                    await asyncio.sleep(30)
                    await ws.send("ping")
            hb = asyncio.create_task(heartbeat())
            try:
                while not stop.is_set():
                    raw = await ws.recv()
                    if isinstance(raw, str):
                        continue          # "pong" / control messages
                    p = parse_angel_ltp(raw)
                    if p and (p[0], p[1]) in by_tok:
                        await emit(by_tok[(p[0], p[1])], p[3], self.name)
            finally:
                hb.cancel()
