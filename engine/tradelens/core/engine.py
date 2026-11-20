"""Event-driven engine. Backtest, paper and live all run this exact loop; only the data feed and the
ExecutionHandler differ, so results are comparable across modes."""
from __future__ import annotations

import math
from dataclasses import dataclass, field
from typing import Callable

import numpy as np
import pandas as pd

from .. import charges
from ..config import RiskLimits
from ..construction import StrategyStats, meta_weights, target_positions
from ..risk import RiskManager
from .execution import ExecutionHandler, SimExecution
from .metrics import summary
from .portfolio import Book
from .strategy import Context, Strategy
from .types import Fill, MarketEvent, Order, Signal


@dataclass
class EngineResult:
    equity: pd.Series
    trades: list[dict]
    fills: list[Fill]
    signals: list[Signal]
    metrics: dict
    risk_events: list[tuple] = field(default_factory=list)
    turnover: float = 0.0


class Engine:
    def __init__(
        self,
        strategies: list[Strategy],
        data: dict[str, pd.DataFrame],
        execution: ExecutionHandler | None = None,
        capital: float = 1_000_000.0,
        limits: RiskLimits | None = None,
        regimes: pd.Series | None = None,
        benchmark: pd.Series | None = None,
        sectors: dict[str, str] | None = None,
        stats: dict[str, StrategyStats] | None = None,
        target_vol: float = 0.13,
        rebalance_band: float = 0.15,
        multipliers: dict[str, float] | None = None,
        approval: Callable[[Order], bool] | None = None,
        on_event: Callable[[str, dict], None] | None = None,
        risk: RiskManager | None = None,
        periods_per_year: int = 252,
    ):
        self.strategies = strategies
        self.data = {k: v for k, v in data.items() if v is not None and not v.empty}
        self.exec = execution or SimExecution()
        self.limits = limits or RiskLimits()
        self.regimes = regimes
        self.benchmark = benchmark
        self.sectors = sectors or {}
        self.stats = stats or {s.name: StrategyStats() for s in strategies}
        self.target_vol = target_vol
        self.band = rebalance_band
        self.mult = multipliers or {}
        self.book = Book(capital, multiplier=self.mult)
        self.risk = risk or RiskManager(self.limits)
        self.approval = approval
        self.on_event = on_event or (lambda kind, d: None)
        self.ctx = Context(self.data)
        self.ppy = periods_per_year
        closes = pd.DataFrame({s: d["close"] for s, d in self.data.items()})
        self.returns = closes.pct_change()
        self.vols = self.returns.rolling(60, min_periods=20).std() * math.sqrt(periods_per_year)
        self.equity_curve: list[tuple] = []
        self.all_signals: list[Signal] = []
        self.risk_events: list[tuple] = []
        self.last_prices: dict[str, float] = {}

    # ---------------------------------------------------------------- one bar
    def step(self, ts, bars: dict[str, pd.Series]) -> list[Fill]:
        ev = MarketEvent(ts, bars)
        self.ctx.advance(ev)
        self.ctx.regime = str(self.regimes.asof(ts)) if self.regimes is not None and len(self.regimes) and ts >= self.regimes.index[0] else "range"
        for s, b in bars.items():
            self.last_prices[s] = float(b["close"])

        # 1) fills for orders created on the previous bar
        fills = self.exec.execute(self._pending, bars, ts) if hasattr(self, "_pending") else self.exec.execute([], bars, ts)
        self._pending = []
        for f in fills:
            self.book.apply(f)
            self.on_event("fill", {"symbol": f.symbol, "qty": f.qty, "price": f.price, "strategy": f.strategy, "venue": f.venue, "charges": f.charges})

        # 2) protective stops / targets (intrabar)
        for (strat, sym), lot in list(self.book.lots.items()):
            b = bars.get(sym)
            if b is None or lot.qty == 0:
                continue
            hit = None
            if lot.stop is not None and ((lot.qty > 0 and b["low"] <= lot.stop) or (lot.qty < 0 and b["high"] >= lot.stop)):
                hit = min(lot.stop, b["open"]) if lot.qty > 0 else max(lot.stop, b["open"])
            elif lot.target is not None and ((lot.qty > 0 and b["high"] >= lot.target) or (lot.qty < 0 and b["low"] <= lot.target)):
                hit = max(lot.target, b["open"]) if lot.qty > 0 else min(lot.target, b["open"])
            if hit is not None:
                seg = self._segment.get(sym, "EQ_DELIVERY") if hasattr(self, "_segment") else "EQ_DELIVERY"
                m = self.mult.get(sym, 1.0)
                ch = charges.leg(seg, "SELL" if lot.qty > 0 else "BUY", hit * m, abs(lot.qty)).total
                f = Fill(-1, sym, -lot.qty, hit, ch, 0.0, strat, ts, self.exec.venue)
                self.book.apply(f); fills.append(f)

        # 3) risk checks on mark-to-market equity
        eq = self.book.equity(self.last_prices)
        day = pd.Timestamp(ts).tz_convert("Asia/Kolkata").date() if pd.Timestamp(ts).tzinfo else pd.Timestamp(ts).date()
        self.risk.new_day(day, eq)
        breach = self.risk.on_equity(eq)
        if breach:
            self.risk_events.append((ts, breach, eq))
            self.on_event("risk", {"kind": breach, "message": f"{breach} at equity {eq:,.0f}"})
            self._pending = self._flatten_orders("risk:" + breach)
            self.equity_curve.append((ts, eq))
            return fills

        # 4) strategies → signals → meta-model → target positions → orders
        signals: list[Signal] = []
        for st in self.strategies:
            ok, why = self.risk.can_trade(st.name)
            if not ok or not self.stats.get(st.name, StrategyStats()).enabled:
                continue
            self.ctx.positions = self.book.by_strategy(st.name)
            try:
                signals += st.generate_signals(self.ctx)
            except Exception as e:  # a faulty strategy must never stop the loop
                self.on_event("risk", {"kind": "strategy_error", "message": f"{st.name}: {e}"})
        self.all_signals += signals
        if signals:
            self._segment = getattr(self, "_segment", {}) | {s.symbol: s.segment for s in signals}
            weights = meta_weights({k: v for k, v in self.stats.items() if k in {s.name for s in self.strategies}}, self.ctx.regime)
            vols = {s: float(self.vols[s].asof(ts)) if s in self.vols and not np.isnan(self.vols[s].asof(ts)) else 0.3 for s in {x.symbol for x in signals}}
            tgt = target_positions(signals, self.last_prices, vols, eq, weights, self.stats, self.limits, self.target_vol, self.sectors,
                                   self.returns.loc[:ts].tail(130), multipliers=self.mult)
            stops = {(s.strategy, s.symbol): (s.stop, s.target) for s in signals}
            orders = []
            for key, q in tgt.items():
                cur = self.book.lots[key].qty if key in self.book.lots else 0.0
                diff = q - cur
                if abs(diff) < 1e-9 or (q != 0 and cur != 0 and abs(diff) < self.band * abs(q)):
                    continue
                risk_reducing = abs(q) < abs(cur) and np.sign(q) in (0, np.sign(cur))
                ok, why = self.risk.can_trade(key[0], risk_reducing)
                if not ok:
                    continue
                o = Order(key[1], diff, "LIMIT", self.last_prices[key[1]] * (1.005 if diff > 0 else 0.995), key[0], self._segment.get(key[1], "EQ_DELIVERY"))
                if self.approval and not self.approval(o):
                    continue
                self.risk.record_order()
                orders.append(o)
                sl, tp = stops.get(key, (None, None))
                lot = self.book.lots[key]
                if q != 0:
                    lot.stop, lot.target = sl, tp
            self._pending = orders
        self.equity_curve.append((ts, self.book.equity(self.last_prices)))
        return fills

    def _flatten_orders(self, reason: str) -> list[Order]:
        out = []
        for (st, sym), lot in self.book.lots.items():
            if lot.qty:
                out.append(Order(sym, -lot.qty, "MARKET", None, st, getattr(self, "_segment", {}).get(sym, "EQ_DELIVERY"), reason))
        return out

    # ---------------------------------------------------------------- backtest driver
    def run(self, start=None, end=None) -> EngineResult:
        idx = sorted(set().union(*[d.index for d in self.data.values()]))
        idx = [t for t in idx if (start is None or t >= pd.Timestamp(start, tz=idx[0].tz)) and (end is None or t <= pd.Timestamp(end, tz=idx[0].tz))]
        frames = {s: d for s, d in self.data.items()}
        for ts in idx:
            bars = {s: d.loc[ts] for s, d in frames.items() if ts in d.index}
            self.step(ts, bars)
        # close everything at the end at last close (with charges) so metrics include all costs
        for (st, sym), lot in list(self.book.lots.items()):
            if lot.qty:
                px = self.last_prices[sym]
                seg = getattr(self, "_segment", {}).get(sym, "EQ_DELIVERY")
                self.book.apply(Fill(-2, sym, -lot.qty, px, charges.leg(seg, "SELL" if lot.qty > 0 else "BUY", px * self.mult.get(sym, 1.0), abs(lot.qty)).total, 0, st, idx[-1]))
        eq = pd.Series(dict(self.equity_curve)).sort_index()
        if len(eq):
            eq.iloc[-1] = self.book.equity(self.last_prices)
        years = max((eq.index[-1] - eq.index[0]).days / 365.25, 1e-9) if len(eq) > 1 else 1
        turnover = self.book.traded_value / max(eq.mean(), 1) / years if len(eq) else 0.0
        m = summary(eq, self.book.trades, self.benchmark, self.ppy, turnover)
        return EngineResult(eq, self.book.trades, self.book.fills, self.all_signals, m, self.risk_events, turnover)
