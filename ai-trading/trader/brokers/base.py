"""Broker interface and a small JSON store for plan details brokers don't keep themselves."""
from __future__ import annotations

import json
from abc import ABC, abstractmethod
from pathlib import Path


class BrokerError(RuntimeError):
    pass


class Broker(ABC):
    name = "broker"
    live = False  # True only when orders reach a real-money account

    @abstractmethod
    def account(self) -> dict:
        """{"equity": float, "cash": float, ...}"""

    @abstractmethod
    def positions(self) -> list[dict]:
        """Open positions and pending orders: symbol, side, qty, entry, stop, target, ..."""

    @abstractmethod
    def submit(self, plan: dict) -> dict:
        """Place the entry with its stop-loss and take-profit. Returns {"id", "status", ...}."""

    def manage(self, frames: dict, trail: bool = True) -> list[dict]:
        """Apply exits the broker does not handle itself. Returns closed trades."""
        return []


class PositionBook:
    """Remembers stop, target, max hold and open date per symbol for live brokers."""

    def __init__(self, path: Path) -> None:
        self.path = Path(path)

    def all(self) -> dict[str, dict]:
        return json.loads(self.path.read_text()) if self.path.exists() else {}

    def put(self, symbol: str, info: dict) -> None:
        book = self.all()
        book[symbol] = info
        self._write(book)

    def remove(self, symbol: str) -> None:
        book = self.all()
        if book.pop(symbol, None) is not None:
            self._write(book)

    def _write(self, book: dict) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(book, indent=2))
        tmp.replace(self.path)
