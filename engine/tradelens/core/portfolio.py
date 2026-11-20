"""Book-keeping of positions, cash and equity (shared by sim, paper and live)."""
from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass, field

from .types import Fill


@dataclass
class Lot:
    qty: float = 0.0
    avg: float = 0.0
    opened: object = None
    stop: float | None = None
    target: float | None = None
    entry_charges: float = 0.0


@dataclass
class Book:
    cash: float
    multiplier: dict[str, float] = field(default_factory=dict)   # contract value per unit (Delta) — 1 for cash equities
    lots: dict[tuple[str, str], Lot] = field(default_factory=lambda: defaultdict(Lot))  # (strategy, symbol) -> lot
    trades: list[dict] = field(default_factory=list)
    fills: list[Fill] = field(default_factory=list)
    traded_value: float = 0.0

    def mult(self, s: str) -> float:
        return self.multiplier.get(s, 1.0)

    def position(self, symbol: str) -> float:
        return sum(l.qty for (st, s), l in self.lots.items() if s == symbol)

    def by_strategy(self, strategy: str) -> dict[str, float]:
        return {s: l.qty for (st, s), l in self.lots.items() if st == strategy and l.qty}

    def equity(self, prices: dict[str, float]) -> float:
        return self.cash + sum(l.qty * prices.get(s, l.avg) * self.mult(s) for (st, s), l in self.lots.items())

    def gross_exposure(self, prices: dict[str, float]) -> float:
        return sum(abs(l.qty * prices.get(s, l.avg) * self.mult(s)) for (st, s), l in self.lots.items())

    def apply(self, f: Fill) -> None:
        self.fills.append(f)
        m = self.mult(f.symbol)
        self.cash -= f.qty * f.price * m + f.charges
        self.traded_value += abs(f.qty * f.price * m)
        lot = self.lots[(f.strategy, f.symbol)]
        if lot.qty == 0 or (lot.qty > 0) == (f.qty > 0):
            new = lot.qty + f.qty
            lot.avg = (lot.avg * abs(lot.qty) + f.price * abs(f.qty)) / abs(new)
            lot.qty = new
            lot.entry_charges += f.charges
            lot.opened = lot.opened or f.ts
            return
        closing = min(abs(lot.qty), abs(f.qty))
        sign = 1 if lot.qty > 0 else -1
        pnl = (f.price - lot.avg) * closing * sign * m
        share = closing / abs(lot.qty)
        ch = f.charges * closing / abs(f.qty) + lot.entry_charges * share
        self.trades.append({"strategy": f.strategy, "symbol": f.symbol, "side": "LONG" if sign > 0 else "SHORT", "qty": closing,
                            "entry": lot.avg, "exit": f.price, "pnl": pnl - ch, "charges": ch, "opened": str(lot.opened), "closed": str(f.ts)})
        lot.entry_charges -= lot.entry_charges * share
        remaining = lot.qty + f.qty
        if abs(remaining) < 1e-12:
            self.lots[(f.strategy, f.symbol)] = Lot()
        elif (remaining > 0) == (lot.qty > 0):
            lot.qty = remaining
        else:  # flipped
            self.lots[(f.strategy, f.symbol)] = Lot(remaining, f.price, f.ts)
