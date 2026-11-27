"""Instrument search: Angel One searchScrip (NSE/BSE/NFO, needs an Angel session) and Delta
Exchange's public product list (crypto). Returns Contract-shaped dicts the app can add."""
from __future__ import annotations

import time
from decimal import Decimal

import httpx

from app.broker.adapters.delta import REGIONS
from app.core.enums import Exchange, Instrument


class InstrumentSearch:
    def __init__(self, angel, delta_region: str, transport: httpx.AsyncBaseTransport | None = None):
        self.angel = angel
        self.delta_url = REGIONS[delta_region]
        self._transport = transport
        self._delta_cache: tuple[float, list[dict]] | None = None

    async def _delta_products(self) -> list[dict]:
        if self._delta_cache and time.monotonic() - self._delta_cache[0] < 3600:
            return self._delta_cache[1]
        async with httpx.AsyncClient(base_url=self.delta_url, timeout=10, transport=self._transport) as c:
            r = await c.get("/v2/products", params={"states": "live"})
            rows = r.json().get("result", [])
        self._delta_cache = (time.monotonic(), rows)
        return rows

    async def search(self, q: str, exchange: str) -> list[dict]:
        q = q.strip()
        if exchange == "DELTA":
            out = []
            for p in await self._delta_products():
                if q.upper() in p.get("symbol", "").upper():
                    ct = p.get("contract_type", "")
                    out.append({"symbol": p["symbol"], "token": str(p["id"]), "exchange": "DELTA",
                                "instrument": "OPTION" if "options" in ct else "FUTURE", "lot_size": 1,
                                "tick_size": str(p.get("tick_size") or "0.5"),
                                "multiplier": str(p.get("contract_value") or "1"),
                                "ids": {"delta": p["symbol"]}, "description": p.get("description", ct)})
            return out[:30]
        if self.angel is None or not self.angel.s.is_logged_in:
            return []
        rows = await self.angel.s.call_with_reauth(self.angel.c.search, exchange, q)
        fno = exchange in ("NFO", "BFO")
        return [{"symbol": r["tradingsymbol"], "token": str(r["symboltoken"]), "exchange": r.get("exchange", exchange),
                 "instrument": ("OPTION" if r["tradingsymbol"].endswith(("CE", "PE")) else "FUTURE") if fno else "EQUITY",
                 "lot_size": 1, "tick_size": "0.05", "multiplier": "1", "ids": {}, "description": r["tradingsymbol"]}
                for r in rows[:30]]
