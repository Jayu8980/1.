"""Persisted watchlist of contracts the market-data hub streams."""
from __future__ import annotations

import json
from decimal import Decimal
from pathlib import Path

from app.core.enums import Exchange, Instrument
from app.core.models import Contract

NIFTY = Contract(symbol="Nifty 50", token="99926000", exchange=Exchange.NSE, instrument=Instrument.EQUITY)

# Angel One symboltokens for a starter list (verify against the instrument master).
DEFAULTS = [
    NIFTY,
    Contract(symbol="RELIANCE-EQ", token="2885", exchange=Exchange.NSE),
    Contract(symbol="HDFCBANK-EQ", token="1333", exchange=Exchange.NSE),
    Contract(symbol="ICICIBANK-EQ", token="4963", exchange=Exchange.NSE),
    Contract(symbol="INFY-EQ", token="1594", exchange=Exchange.NSE),
    Contract(symbol="TCS-EQ", token="11536", exchange=Exchange.NSE),
    Contract(symbol="SBIN-EQ", token="3045", exchange=Exchange.NSE),
    Contract(symbol="BTCUSD", token="27", exchange=Exchange.DELTA, instrument=Instrument.FUTURE,
             multiplier=Decimal("0.001"), tick_size=Decimal("0.5")),
]


class Watchlist:
    def __init__(self, path: str | Path):
        self.path = Path(path); self.path.parent.mkdir(parents=True, exist_ok=True)
        self.items: dict[str, Contract] = {}
        if self.path.exists():
            for d in json.loads(self.path.read_text()):
                c = Contract.model_validate(d); self.items[c.key] = c
        else:
            for c in DEFAULTS:
                self.items[c.key] = c
            self._save()

    def _save(self) -> None:
        self.path.write_text(json.dumps([c.model_dump(mode="json") for c in self.items.values()]))

    def all(self) -> list[Contract]:
        return list(self.items.values())

    def add(self, c: Contract) -> None:
        self.items[c.key] = c; self._save()

    def remove(self, key: str) -> bool:
        ok = self.items.pop(key, None) is not None
        self._save()
        return ok

    def find(self, symbol: str, exchange: str) -> Contract | None:
        return self.items.get(f"{exchange}:{symbol}")
