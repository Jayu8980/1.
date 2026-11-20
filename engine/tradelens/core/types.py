"""Shared event and order types for the event-driven engine."""
from __future__ import annotations

import itertools
import time
from dataclasses import dataclass, field
from enum import Enum

_ids = itertools.count(1)


class Direction(int, Enum):
    SHORT = -1
    FLAT = 0
    LONG = 1


@dataclass
class Signal:
    """Output of every strategy plugin."""
    symbol: str
    direction: Direction
    strength: float            # 0..1 — conviction used for sizing
    confidence: float          # 0..1 — calibrated probability / quality
    stop: float | None = None
    target: float | None = None
    horizon: str = "swing"     # intraday | swing | position
    strategy: str = ""
    reason: str = ""
    segment: str = "EQ_DELIVERY"
    meta: dict = field(default_factory=dict)


@dataclass
class Order:
    symbol: str
    qty: float                 # signed: + buy, - sell
    order_type: str = "LIMIT"  # MARKET | LIMIT
    limit: float | None = None
    strategy: str = ""
    segment: str = "EQ_DELIVERY"
    reason: str = ""
    id: int = field(default_factory=lambda: next(_ids))
    created: float = field(default_factory=time.time)


@dataclass
class Fill:
    order_id: int
    symbol: str
    qty: float
    price: float
    charges: float
    slippage_bps: float
    strategy: str
    ts: object
    venue: str = "sim"


@dataclass
class MarketEvent:
    ts: object                 # pandas Timestamp
    bars: dict                 # symbol -> pandas Series (open, high, low, close, volume)
