"""Live prices for the phone app: the broker's real-time last traded price, else Yahoo Finance.

The broker adapters already fetch last traded prices for orders (``ltp`` / ``index_ltp``); this module
reuses them for a market watch, with a short cache and a rate limit so polling from the app stays
inside the brokers' API limits (Kite, SmartAPI, DhanHQ and Groww allow a few requests a second).
"""
from __future__ import annotations

import json
import logging
import threading
import time
import urllib.parse
import urllib.request

log = logging.getLogger("trader.quotes")

YAHOO_CHART = "https://query1.finance.yahoo.com/v8/finance/chart/{}?range=1d&interval=1d"
MAX_SYMBOLS = 40


def yahoo_quote(symbol: str, timeout: float = 8.0) -> dict | None:
    """Latest price, previous close and quote time from Yahoo's chart API (may be delayed)."""
    url = YAHOO_CHART.format(urllib.parse.quote(symbol, safe=""))
    req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 trader", "Accept": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            meta = json.load(r)["chart"]["result"][0]["meta"]
    except Exception as exc:  # network, 404, unexpected shape: no quote
        log.debug("yahoo quote %s failed: %s", symbol, exc)
        return None
    price = meta.get("regularMarketPrice")
    if not price:
        return None
    return {"price": float(price), "prev_close": meta.get("chartPreviousClose") or meta.get("previousClose"),
            "time": meta.get("regularMarketTime"), "source": "yahoo"}


class QuoteService:
    """Caches quotes per symbol; broker prices first when the broker can give them."""

    def __init__(self, broker=None, ttl: float = 2.0, min_gap: float = 0.12, budget: float = 4.0,
                 yahoo=yahoo_quote) -> None:
        self.broker = broker
        self.ttl, self.min_gap, self.budget = ttl, min_gap, budget
        self.yahoo = yahoo
        self.cache: dict[str, dict] = {}
        self.prev: dict[str, float] = {}  # previous close from Yahoo, for the day's change
        self.lock = threading.Lock()
        self._last_call = 0.0

    def _broker_price(self, symbol: str) -> float | None:
        b = self.broker
        if b is None or not hasattr(b, "underlying_ltp"):
            return None
        wait = self.min_gap - (time.monotonic() - self._last_call)
        if wait > 0:
            time.sleep(wait)
        self._last_call = time.monotonic()
        try:
            from .brokers.india import underlying_of

            name, _, index = underlying_of(symbol)
            price = b.index_ltp(name) if index else b.ltp(b.instrument(symbol))
            return float(price) if price else None
        except Exception as exc:  # unknown instrument, not logged in, rate limited...
            log.debug("broker quote %s failed: %s", symbol, exc)
            return None

    def _fetch(self, symbol: str) -> dict | None:
        price = self._broker_price(symbol)
        if price is not None:
            if symbol not in self.prev:
                y = self.yahoo(symbol)
                self.prev[symbol] = (y or {}).get("prev_close")
            return {"price": price, "prev_close": self.prev.get(symbol), "time": int(time.time()),
                    "source": self.broker.name}
        return self.yahoo(symbol)

    def quotes(self, symbols: list[str]) -> dict[str, dict]:
        symbols = list(dict.fromkeys(symbols))[:MAX_SYMBOLS]
        out: dict[str, dict] = {}
        with self.lock:
            start = time.monotonic()
            for s in symbols:
                hit = self.cache.get(s)
                fresh = hit and time.monotonic() - hit["_at"] < self.ttl
                if not fresh and time.monotonic() - start < self.budget:
                    q = self._fetch(s)
                    if q:
                        hit = self.cache[s] = {**q, "_at": time.monotonic()}
                if hit:
                    out[s] = {k: v for k, v in hit.items() if k != "_at"} | {"age": round(time.monotonic() - hit["_at"], 1)}
        return out
