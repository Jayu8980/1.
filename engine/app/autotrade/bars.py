"""Builds OHLC bars from the tick stream (LTP feeds carry no volume)."""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta, timezone


@dataclass
class Bar:
    start: datetime
    open: float
    high: float
    low: float
    close: float


class BarSeries:
    def __init__(self, minutes: int, keep: int = 500):
        self.minutes, self.keep = minutes, keep
        self.bars: list[Bar] = []
        self.current: Bar | None = None

    def _bucket(self, ts: datetime) -> datetime:
        ts = ts.astimezone(timezone.utc).replace(second=0, microsecond=0)
        return ts - timedelta(minutes=ts.minute % self.minutes)

    def on_tick(self, px: float, ts: datetime) -> Bar | None:
        """Returns the bar that just CLOSED (if this tick opened a new bucket)."""
        b = self._bucket(ts)
        closed = None
        if self.current is not None and b > self.current.start:
            closed = self.current
            self.bars.append(closed)
            del self.bars[:-self.keep]
            self.current = None
        if self.current is None:
            self.current = Bar(b, px, px, px, px)
        else:
            c = self.current
            c.high, c.low, c.close = max(c.high, px), min(c.low, px), px
        return closed

    def seed(self, bars: list[Bar]) -> None:
        self.bars = bars[-self.keep:]

    @property
    def closes(self) -> list[float]:
        return [b.close for b in self.bars]

    @property
    def highs(self) -> list[float]:
        return [b.high for b in self.bars]

    @property
    def lows(self) -> list[float]:
        return [b.low for b in self.bars]
