import asyncio
import json
import struct
from decimal import Decimal as D

from app.broker.paper import PaperBroker
from app.core.enums import Exchange, Instrument
from app.core.models import Contract
from app.marketdata.hub import MarketDataHub
from app.marketdata.sources import AngelWsSource, DeltaWsSource, parse_angel_ltp, parse_delta

SBIN = Contract(symbol="SBIN-EQ", token="3045", exchange=Exchange.NSE)
BTC = Contract(symbol="BTCUSD", token="27", exchange=Exchange.DELTA, instrument=Instrument.FUTURE, multiplier=D("0.001"))


def angel_packet(exch: int, token: str, ltp_paise: int, ts_ms: int = 1_790_000_000_000) -> bytes:
    b = bytearray(51)
    b[0], b[1] = 1, exch
    b[2:2 + len(token)] = token.encode()
    struct.pack_into("<q", b, 27, 42)
    struct.pack_into("<q", b, 35, ts_ms)
    struct.pack_into("<q", b, 43, ltp_paise)
    return bytes(b)


def test_parse_angel_ltp_packet():
    assert parse_angel_ltp(angel_packet(1, "3045", 81235)) == (1, "3045", 1_790_000_000_000, D("812.35"))
    assert parse_angel_ltp(b"\x01\x01short") is None


def test_parse_delta_ticker():
    assert parse_delta({"type": "v2/ticker", "symbol": "BTCUSD", "mark_price": "64001.5"}) == ("BTCUSD", D("64001.5"))
    assert parse_delta({"type": "v2/ticker", "symbol": "BTCUSD", "close": 64000}) == ("BTCUSD", D("64000"))
    assert parse_delta({"type": "subscriptions"}) is None


class FakeSource:
    def __init__(self, name, exch, avail=True, prices=None):
        self.name, self.exch, self.avail, self.prices = name, exch, avail, prices or {}
        self.ran_with = None

    def accepts(self, c):
        return c.exchange in self.exch

    async def available(self):
        return self.avail

    async def run(self, contracts, emit, stop):
        self.ran_with = [c.key for c in contracts]
        for c in contracts:
            await emit(c, self.prices.get(c.key, D("1")), self.name)
        await stop.wait()


async def test_hub_routes_falls_back_and_fans_out():
    ws = FakeSource("ws", {Exchange.NSE}, avail=False)
    poll = FakeSource("poll", {Exchange.NSE}, prices={SBIN.key: D("812.5")})
    delta = FakeSource("delta", {Exchange.DELTA}, prices={BTC.key: D("64000")})
    paper = PaperBroker()
    hub = MarketDataHub([delta, ws, poll], resync_s=0.05)
    hub.add_listener(lambda c, px: paper.on_tick(c, px))
    q = hub.subscribe()
    hub.set_contracts([SBIN, BTC])
    stop = asyncio.Event()
    task = asyncio.create_task(hub.run(stop))
    msgs = [await asyncio.wait_for(q.get(), 2) for _ in range(2)]
    assert {m["key"]: m["source"] for m in msgs} == {SBIN.key: "poll", BTC.key: "delta"}
    assert hub.latest[SBIN.key].ltp == D("812.5")
    assert await paper.ltp(SBIN) == D("812.5")        # paper now fills at real prices
    assert ws.ran_with is None and poll.ran_with == [SBIN.key]

    ws.avail = True                                     # WS comes up -> hub moves SBIN onto it
    for _ in range(40):
        await asyncio.sleep(0.05)
        if hub.assignment.get(SBIN.key) == "ws":
            break
    assert hub.assignment[SBIN.key] == "ws"
    stop.set(); await asyncio.wait_for(task, 2)


class FakeWS:
    def __init__(self, incoming):
        self.sent, self.incoming = [], list(incoming)

    async def __aenter__(self):
        return self

    async def __aexit__(self, *a):
        return False

    async def send(self, m):
        self.sent.append(m)

    async def recv(self):
        if not self.incoming:
            await asyncio.sleep(3600)
        return self.incoming.pop(0)


async def test_delta_ws_subscribes_and_emits():
    fake = FakeWS([json.dumps({"type": "subscriptions"}),
                   json.dumps({"type": "v2/ticker", "symbol": "BTCUSD", "mark_price": "64100"})])
    seen_url = {}

    def connect(url, **kw):
        seen_url["u"] = url
        return fake
    src = DeltaWsSource("india-testnet", connect=connect)
    got = []

    async def emit(c, px, name):
        got.append((c.key, px, name))
    stop = asyncio.Event()
    t = asyncio.create_task(src.run([BTC], emit, stop))
    await asyncio.sleep(0.05); t.cancel()
    assert "testnet" in seen_url["u"]
    sub = json.loads(fake.sent[0])
    assert sub["payload"]["channels"][0] == {"name": "v2/ticker", "symbols": ["BTCUSD"]}
    assert got == [(BTC.key, D("64100"), "delta-ws")]


async def test_angel_ws_headers_subscribe_and_binary_ticks():
    class Tok:
        jwt, feed = "JWT", "FEED"

    class Sess:
        is_logged_in, tokens = True, Tok()
    fake = FakeWS(["pong", angel_packet(1, "3045", 80010), angel_packet(1, "9999", 100)])
    seen = {}

    def connect(url, **kw):
        seen.update(kw); return fake
    src = AngelWsSource(Sess(), "APIKEY", "A123", connect=connect)
    assert await src.available()
    got = []

    async def emit(c, px, name):
        got.append((c.key, px))
    t = asyncio.create_task(src.run([SBIN], emit, asyncio.Event()))
    await asyncio.sleep(0.05); t.cancel()
    h = seen["additional_headers"]
    assert h == {"Authorization": "JWT", "x-api-key": "APIKEY", "x-client-code": "A123", "x-feed-token": "FEED"}
    sub = json.loads(fake.sent[0])
    assert sub["action"] == 1 and sub["params"]["mode"] == 1
    assert sub["params"]["tokenList"] == [{"exchangeType": 1, "tokens": ["3045"]}]
    assert got == [(SBIN.key, D("800.10"))]           # unknown token 9999 ignored
