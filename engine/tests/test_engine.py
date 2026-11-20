import numpy as np
import pandas as pd

from tradelens.config import RiskLimits
from tradelens.core.engine import Engine
from tradelens.core.execution import SimExecution
from tradelens.core.portfolio import Book
from tradelens.core.strategy import Context, Strategy
from tradelens.core.types import Direction, Fill, Signal
from tradelens.strategies import REGISTRY
from conftest import make_bars


class AlwaysLong(Strategy):
    name = "always_long"

    def on_bar(self, ctx):
        return [Signal(s, Direction.LONG, 1.0, 0.6) for s in ctx.symbols() if s != "NIFTY"]


class Peeker(Strategy):
    """Asserts that the context never exposes future bars."""
    name = "peeker"

    def on_bar(self, ctx):
        for s in ctx.symbols():
            assert ctx.history(s).index[-1] <= ctx.now
        return []


def test_book_pnl_accounting():
    b = Book(1000.0)
    b.apply(Fill(1, "X", 10, 100.0, 1.0, 0, "s", 0))
    b.apply(Fill(2, "X", -10, 110.0, 1.0, 0, "s", 1))
    assert abs(b.cash - (1000 + 100 - 2)) < 1e-9
    assert len(b.trades) == 1 and abs(b.trades[0]["pnl"] - 98) < 1e-9


def test_no_lookahead_and_next_bar_fills(panel):
    eng = Engine([Peeker(), AlwaysLong()], panel, SimExecution(), 1_000_000, RiskLimits(maxDrawdownPct=99, dailyLossPct=99))
    res = eng.run()
    assert len(res.fills) > 0
    first_fill_ts = min(f.ts for f in res.fills if f.order_id > 0)
    first_bar = min(d.index[0] for d in panel.values())
    assert first_fill_ts > first_bar  # orders from bar t fill at bar t+1


def test_charges_reduce_equity(panel):
    res = Engine([AlwaysLong()], panel, SimExecution(), 1_000_000, RiskLimits(maxDrawdownPct=99, dailyLossPct=99)).run()
    assert sum(f.charges for f in res.fills) > 0
    gross = sum(t["pnl"] + t["charges"] for t in res.trades)
    net = res.equity.iloc[-1] - 1_000_000
    assert net < gross


def test_position_limits_respected(panel):
    eng = Engine([AlwaysLong()], panel, SimExecution(), 1_000_000, RiskLimits(maxPositionPct=10, maxDrawdownPct=99, dailyLossPct=99))
    res = eng.run()
    pos: dict = {}
    for f in eng.book.fills:
        if f.order_id < 0:
            continue
        pos[f.symbol] = pos.get(f.symbol, 0) + f.qty
        eq_prev = res.equity[res.equity.index < f.ts].iloc[-1] if (res.equity.index < f.ts).any() else 1_000_000
        # position value right after an entry never exceeds 10% of the equity it was sized on (+ next-open gap/slippage)
        assert abs(pos[f.symbol] * f.price) <= eq_prev * 0.10 * 1.10


def test_drawdown_circuit_breaker_flattens():
    crash = make_bars(400, drift=-0.01, vol=0.02, seed=3)
    eng = Engine([AlwaysLong()], {"A": crash, "NIFTY": make_bars(400, seed=4)}, SimExecution(), 1_000_000,
                 RiskLimits(maxDrawdownPct=1.0, dailyLossPct=50, maxPositionPct=100))
    res = eng.run()
    assert any(e[1] == "circuit_break" for e in res.risk_events)
    assert eng.risk.state.circuit_broken
    ok, why = eng.risk.can_trade("always_long")
    assert not ok and "circuit" in why


def test_all_strategies_run(panel):
    for name, cls in REGISTRY.items():
        if name in ("ml_ensemble",):
            continue
        eng = Engine([cls()], panel, SimExecution(), 1_000_000)
        eng.ctx.meta = {"sectors": {s: "Bank" if i % 2 else "IT" for i, s in enumerate(panel)}}
        res = eng.run()
        assert len(res.equity) > 0, name
