"""AutoTrader: turns live ticks into orders, end to end.

ticks -> 5-min bars -> regime -> strategy signal -> confidence gate -> fractional-Kelly sizing
-> cost filter -> (kill switch / risk veto / static IP in the gateway) -> entry order
-> protective SL-L stop at the broker -> target: book half + stop to breakeven -> ATR trailing
-> time stop / intraday square-off -> journal -> per-strategy stats, drift guard, promotion gate.

Routing: a strategy in PAPER stage always trades on the paper broker, even when the engine is
LIVE. LIVE_10 / LIVE strategies trade on the live broker only while the engine mode is LIVE
(else paper). LIVE_10 uses 10% of the strategy's allocation.
Auto-trade OFF stops new entries; open trades keep being managed (their stops stay in place).
"""
from __future__ import annotations

import logging
import math
from collections import deque
from dataclasses import dataclass, field
from datetime import datetime, time, timezone
from decimal import ROUND_HALF_UP, Decimal
from itertools import count
from typing import Callable
from zoneinfo import ZoneInfo

from app.broker.costs import compute_costs
from app.core.enums import Exchange, Instrument, Mode, OrderStatus, OrderType, Product, Side
from app.core.errors import BrokerError, OrderBlocked
from app.core.models import Contract, OrderRequest
from app.execution.gateway import PROTECT

from .bars import BarSeries
from .store import StrategyStore, trade_stats
from .strategies import ALL, Strategy, TradeSignal, regime

IST = ZoneInfo("Asia/Kolkata")
D = Decimal
log = logging.getLogger("autotrade")


@dataclass
class AutoSettings:
    timeframe_min: int = 5
    min_confidence: float = 0.55
    max_open_trades: int = 3
    max_trades_per_day: int = 5          # per strategy
    entry_start: time = time(9, 30)
    entry_end: time = time(14, 45)
    squareoff: time = time(15, 15)       # INTRADAY positions flattened at/after this (IST)
    time_stop_bars: int = 24             # 2 hours on 5-min bars
    entry_timeout_bars: int = 2
    trail_atr: float = 2.0
    protection_pct: float = 0.5          # LIVE entries/exits = LIMIT this far through LTP; SL-L band
    allow_crypto: bool = False
    min_edge_bps: float = 5.0


@dataclass
class ManagedTrade:
    id: str
    strategy: str
    contract: Contract
    side: Side
    qty: int
    venue: str                           # paper | live
    product: Product
    signal: TradeSignal
    capital: float
    status: str = "PENDING_ENTRY"        # PENDING_ENTRY / OPEN / CLOSED / CANCELLED
    entry_order: str | None = None
    entry_price: float = 0.0
    stop: float = 0.0
    target: float = 0.0
    stop_order: str | None = None
    remaining: int = 0
    partial_done: bool = False
    realized: float = 0.0
    bars: int = 0
    opened: datetime = field(default_factory=lambda: datetime.now(timezone.utc))

    @property
    def long(self) -> bool:
        return self.side is Side.BUY

    @property
    def paper(self) -> bool:
        return self.venue == "paper"

    def view(self) -> dict:
        return {"id": self.id, "strategy": self.strategy, "symbol": self.contract.symbol, "side": self.side.value,
                "qty": self.qty, "remaining": self.remaining, "venue": self.venue, "status": self.status,
                "entry": self.entry_price, "stop": self.stop, "target": self.target, "partial": self.partial_done}


def _tick_round(px: float, tick: Decimal) -> Decimal:
    return (D(str(px)) / tick).quantize(D("1"), rounding=ROUND_HALF_UP) * tick


class AutoTrader:
    def __init__(self, gateway, risk, store: StrategyStore, audit, settings: AutoSettings | None = None,
                 strategies: list[Strategy] | None = None, clock: Callable[[], datetime] | None = None):
        self.gw, self.risk, self.store, self.audit = gateway, risk, store, audit
        self.s = settings or AutoSettings()
        self.strategies = {st.id: st for st in (strategies or ALL)}
        self.clock = clock or (lambda: datetime.now(timezone.utc))
        self.series: dict[str, BarSeries] = {}
        self.trades: dict[str, ManagedTrade] = {}
        self.signals: deque[dict] = deque(maxlen=300)
        self._ids = count(1)
        self.last_ltp: dict[str, float] = {}

    # ------------------------------------------------------------------ helpers
    @property
    def enabled(self) -> bool:
        return self.store.autotrade_enabled

    def _ist(self) -> datetime:
        return self.clock().astimezone(IST)

    def _in_entry_window(self, c: Contract) -> bool:
        if c.exchange is Exchange.DELTA:
            return self.s.allow_crypto
        t = self._ist()
        return t.weekday() < 5 and self.s.entry_start <= t.time() <= self.s.entry_end

    def _product(self, c: Contract) -> Product:
        if c.exchange is Exchange.DELTA or c.instrument is not Instrument.EQUITY:
            return Product.CARRYFORWARD
        return Product.INTRADAY

    def _venue(self, sid: str) -> str:
        stage = self.store.cfg[sid].stage
        return "live" if stage != "PAPER" and self.gw.mode is Mode.LIVE else "paper"

    def open_trades(self) -> list[ManagedTrade]:
        return [t for t in self.trades.values() if t.status in ("PENDING_ENTRY", "OPEN")]

    def _record_signal(self, sig: TradeSignal, c: Contract, status: str, detail: str = "", edge_bps: float = 0.0):
        self.signals.appendleft({
            "id": f"s{next(self._ids)}", "symbol": c.symbol, "side": sig.side, "strategy": sig.strategy,
            "confidence": sig.confidence, "regime": sig.regime, "reason": sig.reason + (f" — {detail}" if detail else ""),
            "entry": sig.entry, "stop": sig.stop, "target": sig.target, "expectedEdgeBps": round(edge_bps, 1),
            "status": status, "ts": int(self.clock().timestamp() * 1000)})

    # ------------------------------------------------------------------ tick entry point
    async def on_tick(self, c: Contract, px: Decimal) -> None:
        p = float(px)
        self.last_ltp[c.key] = p
        s = self.series.setdefault(c.key, BarSeries(self.s.timeframe_min))
        closed = s.on_tick(p, self.clock())
        for t in [t for t in self.open_trades() if t.contract.key == c.key and t.status == "OPEN"]:
            try:
                await self._manage_tick(t, p)
            except Exception as e:
                await self.audit.record("auto.manage_error", trade=t.id, error=str(e))
        if closed is not None:
            await self.on_bar(c, s)

    async def on_bar(self, c: Contract, s: BarSeries) -> None:
        await self._manage_bar(c)
        if not self.enabled or not self._in_entry_window(c):
            return
        reg, reg_conf = regime(s)
        for sid, strat in self.strategies.items():
            cfg = self.store.cfg[sid]
            if not cfg.enabled:
                continue
            sig = strat.evaluate(s, reg, reg_conf)
            if sig is not None:
                await self._consider(sig, c)

    # ------------------------------------------------------------------ decision
    async def _consider(self, sig: TradeSignal, c: Contract) -> None:
        sid = sig.strategy
        cfg = self.store.cfg[sid]
        if sig.confidence < self.s.min_confidence:
            return self._record_signal(sig, c, "SKIPPED_CONFIDENCE", f"{sig.confidence:.2f} < {self.s.min_confidence}")
        if any(t.contract.key == c.key for t in self.open_trades()):
            return self._record_signal(sig, c, "SKIPPED_LIMIT", "already in a trade on this symbol")
        if len(self.open_trades()) >= self.s.max_open_trades:
            return self._record_signal(sig, c, "SKIPPED_LIMIT", f"max {self.s.max_open_trades} open trades")
        if self.store.trades_today(sid, self._ist().date().isoformat()) + sum(
                1 for t in self.trades.values() if t.strategy == sid and t.opened.astimezone(IST).date() == self._ist().date()
                and t.status != "CLOSED") >= self.s.max_trades_per_day:
            return self._record_signal(sig, c, "SKIPPED_LIMIT", f"max {self.s.max_trades_per_day} trades/day")

        # ---- sizing: fractional Kelly, capped by max risk per trade and strategy capital
        p, rr = sig.confidence, sig.reward_risk
        kelly = p - (1 - p) / rr if rr > 0 else -1
        if kelly <= 0:
            return self._record_signal(sig, c, "SKIPPED_COST", f"no edge (Kelly {kelly:.2f})")
        venue = self._venue(sid)
        snap = await self.risk.portfolio.snapshot(self.gw.venue_broker(venue == "paper"))
        equity = float(snap["equityDec"])
        lim = self.risk.limits
        risk_frac = min(lim.maxRiskPerTradePct / 100, lim.kellyFraction * kelly)
        stage_mult = 0.10 if cfg.stage == "LIVE_10" else 1.0
        capital = equity * cfg.allocationPct / 100 * stage_mult
        mult = float(c.multiplier)
        qty_risk = math.floor(equity * risk_frac * stage_mult / (sig.risk_per_unit * mult))
        qty_cap = math.floor(min(capital, equity * lim.maxPositionPct / 100) / (sig.entry * mult))
        qty = min(qty_risk, qty_cap) // c.lot_size * c.lot_size
        if qty < c.lot_size:
            return self._record_signal(sig, c, "SKIPPED_LIMIT", "position size rounds to zero for this capital/risk")

        # ---- cost filter: expected edge after brokerage, taxes, fees and slippage
        product = self._product(c)
        e = D(str(sig.entry)); x = D(str(sig.target)); st = D(str(sig.stop))
        costs = float(compute_costs(Side.BUY, qty, e, c.instrument, product, exchange=c.exchange, multiplier=c.multiplier).total
                      + compute_costs(Side.SELL, qty, x, c.instrument, product, exchange=c.exchange, multiplier=c.multiplier).total)
        slip = qty * mult * float(e + x) * float(self.gw.paper.slippage_bps) / 1e4
        gross = qty * mult * (p * abs(float(x - e)) - (1 - p) * abs(float(e - st)))
        edge = gross - costs - slip
        notional = qty * mult * sig.entry
        edge_bps = edge / notional * 1e4 if notional else 0.0
        if edge <= 0 or edge_bps < self.s.min_edge_bps:
            return self._record_signal(sig, c, "SKIPPED_COST",
                                       f"edge ₹{gross:.0f} − costs ₹{costs + slip:.0f} = ₹{edge:.0f}", edge_bps)

        # ---- place entry
        side = Side(sig.side)
        if venue == "live":
            band = 1 + self.s.protection_pct / 100 if side is Side.BUY else 1 - self.s.protection_pct / 100
            req = OrderRequest(contract=c, side=side, quantity=qty, order_type=OrderType.LIMIT,
                               price=_tick_round(sig.entry * band, c.tick_size), product=product, strategy=sid)
        else:
            req = OrderRequest(contract=c, side=side, quantity=qty, order_type=OrderType.MARKET, product=product, strategy=sid)
        try:
            o = await self.gw.place(req, e, force_paper=venue == "paper")
        except OrderBlocked as ex:
            return self._record_signal(sig, c, "VETOED", ex.reason, edge_bps)
        except BrokerError as ex:
            await self.audit.record("auto.entry_error", strategy=sid, symbol=c.key, error=str(ex))
            return self._record_signal(sig, c, "VETOED", f"broker error: {ex}", edge_bps)
        t = ManagedTrade(f"T{next(self._ids)}", sid, c, side, qty, venue, product, sig, capital,
                         entry_order=o.order_id, opened=self.clock())
        self.trades[t.id] = t
        self._record_signal(sig, c, "TAKEN", f"{venue.upper()} {side.value} {qty} (risk {risk_frac * 100:.2f}%)", edge_bps)
        await self.audit.record("auto.entry", trade=t.id, strategy=sid, symbol=c.key, side=side, qty=qty, venue=venue,
                                order_id=o.order_id, stop=sig.stop, target=sig.target, confidence=sig.confidence)
        if o.status is OrderStatus.FILLED:
            await self._activate(t, float(o.avg_fill_price))

    # ------------------------------------------------------------------ lifecycle
    async def _activate(self, t: ManagedTrade, fill: float) -> None:
        """Entry filled: re-anchor stop/target to the real fill and place the broker-side stop."""
        sig = t.signal
        t.entry_price, t.remaining, t.status = fill, t.qty, "OPEN"
        d = sig.risk_per_unit
        t.stop = fill - d if t.long else fill + d
        t.target = fill + abs(sig.target - sig.entry) if t.long else fill - abs(sig.target - sig.entry)
        await self._place_stop(t)
        await self.audit.record("auto.opened", trade=t.id, fill=fill, stop=t.stop, target=t.target)

    def _stop_prices(self, t: ManagedTrade) -> tuple[Decimal, Decimal]:
        band = self.s.protection_pct / 100
        trig = _tick_round(t.stop, t.contract.tick_size)
        lim = _tick_round(t.stop * (1 - band) if t.long else t.stop * (1 + band), t.contract.tick_size)
        return trig, lim

    async def _place_stop(self, t: ManagedTrade) -> None:
        trig, lim = self._stop_prices(t)
        req = OrderRequest(contract=t.contract, side=Side.SELL if t.long else Side.BUY, quantity=t.remaining,
                           order_type=OrderType.SL_LIMIT, price=lim, trigger_price=trig, product=t.product,
                           strategy=t.strategy + PROTECT)
        try:
            o = await self.gw.place(req, trig, exit_only=True, force_paper=t.paper)
            t.stop_order = o.order_id
            if o.status is OrderStatus.FILLED:
                await self._closed(t, float(o.avg_fill_price), "stop")
        except (OrderBlocked, BrokerError) as e:
            # No broker-side stop: the engine guards it (tick check) and alerts loudly.
            t.stop_order = None
            await self.audit.record("auto.stop_place_failed", trade=t.id, error=str(e))

    async def _manage_tick(self, t: ManagedTrade, px: float) -> None:
        sig = t.signal
        beyond = (px <= t.stop * (1 - self.s.protection_pct / 100)) if t.long else (px >= t.stop * (1 + self.s.protection_pct / 100))
        if beyond or (t.stop_order is None and ((px <= t.stop) if t.long else (px >= t.stop))):
            return await self.close(t, "stop (engine guard)")
        hit_target = px >= t.target if t.long else px <= t.target
        if hit_target and not t.partial_done:
            half = (t.remaining // 2) // t.contract.lot_size * t.contract.lot_size
            if half >= t.contract.lot_size:
                fill = await self._exit_order(t, half)
                t.realized += (fill - t.entry_price) * half * float(t.contract.multiplier) * (1 if t.long else -1)
                t.remaining -= half
            t.partial_done = True
            t.stop = t.entry_price                               # breakeven
            await self._move_stop(t)
            await self.audit.record("auto.partial", trade=t.id, booked=half, remaining=t.remaining, stop=t.stop)
            return
        if t.partial_done:                                       # ATR trail after partial
            trail = px - self.s.trail_atr * sig.atr if t.long else px + self.s.trail_atr * sig.atr
            if (t.long and trail > t.stop + 0.25 * sig.atr) or (not t.long and trail < t.stop - 0.25 * sig.atr):
                t.stop = trail
                await self._move_stop(t)

    async def _move_stop(self, t: ManagedTrade) -> None:
        if t.remaining <= 0:
            return
        trig, lim = self._stop_prices(t)
        if t.stop_order is None:
            return await self._place_stop(t)
        try:
            await self.gw.modify(t.stop_order, force_paper=t.paper, actor="autotrader",
                                 price=lim, trigger_price=trig, quantity=t.remaining)
        except (BrokerError, OrderBlocked) as e:
            await self.audit.record("auto.stop_modify_failed", trade=t.id, error=str(e))

    async def _exit_order(self, t: ManagedTrade, qty: int) -> float:
        px = self.last_ltp.get(t.contract.key, t.entry_price)
        side = Side.SELL if t.long else Side.BUY
        if t.venue == "live":
            band = 1 - self.s.protection_pct / 100 if side is Side.SELL else 1 + self.s.protection_pct / 100
            req = OrderRequest(contract=t.contract, side=side, quantity=qty, order_type=OrderType.LIMIT,
                               price=_tick_round(px * band, t.contract.tick_size), product=t.product, strategy=t.strategy)
        else:
            req = OrderRequest(contract=t.contract, side=side, quantity=qty, order_type=OrderType.MARKET,
                               product=t.product, strategy=t.strategy)
        o = await self.gw.place(req, D(str(px)), exit_only=True, force_paper=t.paper)
        return float(o.avg_fill_price) or px

    async def close(self, t: ManagedTrade, why: str) -> None:
        if t.status != "OPEN":
            return
        if t.stop_order:
            try:
                await self.gw.cancel(t.stop_order, "autotrader", force_paper=t.paper)
            except BrokerError:
                pass
        fill = await self._exit_order(t, t.remaining) if t.remaining > 0 else t.entry_price
        await self._closed(t, fill, why)

    async def _closed(self, t: ManagedTrade, fill: float, why: str) -> None:
        mult = float(t.contract.multiplier)
        pnl = t.realized + (fill - t.entry_price) * t.remaining * mult * (1 if t.long else -1)
        notional = t.entry_price * t.qty * mult
        cost = float(compute_costs(Side.BUY, t.qty, D(str(t.entry_price)), t.contract.instrument, t.product,
                                   exchange=t.contract.exchange, multiplier=t.contract.multiplier).total * 2)
        t.status, t.remaining = "CLOSED", 0
        rec = {"id": t.id, "strategy": t.strategy, "symbol": t.contract.key, "side": t.side.value, "qty": t.qty,
               "venue": t.venue, "entry": t.entry_price, "exit": fill, "pnl": round(pnl - cost, 2),
               "notional": notional, "capital": t.capital, "why": why,
               "opened": t.opened.astimezone(IST).isoformat(), "closed": self._ist().isoformat()}
        self.store.record_trade(rec)
        await self.audit.record("auto.closed", **rec)
        reason = self.store.drift_check(t.strategy)
        if reason:
            await self.audit.record("strategy.auto_disabled", strategy=t.strategy, reason=reason)

    async def _manage_bar(self, c: Contract) -> None:
        """Per-bar housekeeping for trades on this contract: fills, timeouts, time stop, square-off."""
        mine = [t for t in self.open_trades() if t.contract.key == c.key]
        if not mine:
            return
        books = {}
        for venue in {t.venue for t in mine}:
            b = self.gw.venue_broker(venue == "paper")
            books[venue] = {o.order_id: o for o in await b.orders()}
        now = self._ist()
        for t in mine:
            t.bars += 1
            book = books[t.venue]
            if t.status == "PENDING_ENTRY":
                o = book.get(t.entry_order)
                if o and o.status is OrderStatus.FILLED:
                    await self._activate(t, float(o.avg_fill_price))
                elif o and o.status in (OrderStatus.REJECTED, OrderStatus.CANCELLED):
                    t.status = "CANCELLED"
                elif t.bars >= self.s.entry_timeout_bars:
                    try:
                        await self.gw.cancel(t.entry_order, "autotrader", force_paper=t.paper)
                    except BrokerError:
                        pass
                    t.status = "CANCELLED"
                    await self.audit.record("auto.entry_timeout", trade=t.id)
                continue
            so = book.get(t.stop_order) if t.stop_order else None
            if so and so.status is OrderStatus.FILLED:
                await self._closed(t, float(so.avg_fill_price), "stop")
            elif t.bars >= self.s.time_stop_bars:
                await self.close(t, "time stop")
            elif t.product is Product.INTRADAY and now.time() >= self.s.squareoff:
                await self.close(t, "intraday square-off")

    # ------------------------------------------------------------------ api views
    def strategies_view(self) -> list[dict]:
        out = []
        for sid, st in self.strategies.items():
            cfg = self.store.cfg[sid]
            paper = trade_stats(self.store.trades(sid, "paper"))
            live = trade_stats(self.store.trades(sid, "live"))
            cur = live if cfg.stage != "PAPER" else paper
            ok, why, _ = self.store.gate(sid)
            out.append({"id": sid, "name": st.name, "description": st.description, "enabled": cfg.enabled,
                        "allocationPct": cfg.allocationPct, "stage": cfg.stage, "regimes": list(st.regimes),
                        "liveSharpe": round(cur["sharpe"], 2), "backtestSharpe": round(paper["sharpe"], 2),
                        "trades": cur["trades"], "winRate": round(cur["winRate"], 3), "pnl": round(cur["pnl"], 2),
                        "drawdownPct": round(cur["ddPct"], 2), "backtestMaxDdPct": round(paper["ddPct"], 2),
                        "autoDisabledReason": cfg.autoDisabledReason, "promotable": ok, "gate": why,
                        "daysInStage": round(self.store.days_in_stage(sid), 1),
                        "openTrades": sum(1 for t in self.open_trades() if t.strategy == sid)})
        return out
