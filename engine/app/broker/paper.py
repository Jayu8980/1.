"""Paper broker: realistic simulated fills, Indian costs, positions and margin.

Fill model
- MARKET: fills immediately at LTP +/- slippage (adverse), rounded to tick.
- LIMIT: fills immediately if marketable (at the better of limit/LTP), else rests
  and is filled on the first tick that crosses it.
- SL-M / SL-L: rest until LTP crosses trigger, then behave as MARKET / LIMIT.
Margin model (approximate, conservative): delivery & long options = 100% of value,
intraday equity = 20%, futures & short options = `fno_margin_pct`.
Not modelled (yet): partial fills, circuit limits, auction, physical settlement.
"""
from __future__ import annotations

import asyncio
from datetime import datetime, timezone
from decimal import ROUND_HALF_UP, Decimal
from itertools import count
from typing import Callable

from app.core.enums import Instrument, OrderStatus, OrderType, Product, Side
from app.core.errors import BrokerError
from app.core.models import Contract, Fill, Funds, Order, OrderRequest, Position

from .base import Broker
from .costs import CostSchedule, compute_costs

D = Decimal


def _round_tick(p: Decimal, tick: Decimal) -> Decimal:
    return ((p / tick).quantize(D("1"), rounding=ROUND_HALF_UP) * tick).quantize(D("0.01"))


class PaperBroker(Broker):
    name = "paper"

    def __init__(self, starting_capital: Decimal = D("1000000"), slippage_bps: Decimal = D("5"),
                 costs: CostSchedule = CostSchedule(), intraday_margin_pct: Decimal = D("20"),
                 fno_margin_pct: Decimal = D("15"), short_option_margin_multiple: Decimal = D("10"),
                 clock: Callable[[], datetime] = lambda: datetime.now(timezone.utc)):
        self.starting_capital = D(starting_capital)
        self.slippage_bps = D(slippage_bps)
        self.costs = costs
        self.intraday_margin_pct = D(intraday_margin_pct)
        self.fno_margin_pct = D(fno_margin_pct)
        self.short_option_margin_multiple = D(short_option_margin_multiple)
        self._clock = clock
        self._ltp: dict[str, Decimal] = {}
        self._orders: dict[str, Order] = {}
        self._positions: dict[tuple[str, Product], Position] = {}
        self._fills: list[Fill] = []
        self._ids = count(1)
        self._lock = asyncio.Lock()
        self.fill_listeners: list[Callable[[Fill], None]] = []

    # ----------------------------------------------------------- market data
    async def on_tick(self, contract: Contract, price: Decimal) -> list[Fill]:
        """Feed a price; triggers resting orders. Returns fills generated."""
        async with self._lock:
            self._ltp[contract.key] = D(price)
            for pos in self._positions.values():
                if pos.contract.key == contract.key:
                    pos.ltp = D(price)
            fills = []
            for o in list(self._orders.values()):
                if o.status is OrderStatus.PENDING and o.request.contract.key == contract.key:
                    f = self._try_fill(o)
                    if f:
                        fills.append(f)
            return fills

    async def ltp(self, contract: Contract) -> Decimal:
        try:
            return self._ltp[contract.key]
        except KeyError:
            raise BrokerError(f"no price for {contract.key}") from None

    # --------------------------------------------------------------- orders
    async def place_order(self, req: OrderRequest) -> Order:
        async with self._lock:
            now = self._clock()
            oid = f"P{next(self._ids):08d}"
            order = Order(order_id=oid, request=req, status=OrderStatus.PENDING,
                          created_at=now, updated_at=now)
            self._orders[oid] = order
            if req.contract.key not in self._ltp:
                return self._reject(order, "no market price available")
            ref_price = req.price if req.price is not None else self._ltp[req.contract.key]
            err = self._pretrade_check(req, D(ref_price))
            if err:
                return self._reject(order, err)
            self._try_fill(order)
            return order

    async def cancel_order(self, order_id: str) -> Order:
        async with self._lock:
            o = self._get(order_id)
            if o.status is not OrderStatus.PENDING:
                raise BrokerError(f"cannot cancel order in status {o.status}")
            o.status, o.updated_at, o.message = OrderStatus.CANCELLED, self._clock(), "cancelled"
            return o

    async def modify_order(self, order_id: str, *, price=None, trigger_price=None, quantity=None) -> Order:
        async with self._lock:
            o = self._get(order_id)
            if o.status is not OrderStatus.PENDING:
                raise BrokerError(f"cannot modify order in status {o.status}")
            upd = {k: v for k, v in dict(price=price, trigger_price=trigger_price, quantity=quantity).items()
                   if v is not None}
            o.request = OrderRequest.model_validate({**o.request.model_dump(), **upd})
            o.updated_at = self._clock()
            self._try_fill(o)
            return o

    async def orders(self) -> list[Order]:
        return list(self._orders.values())

    async def positions(self) -> list[Position]:
        return [p for p in self._positions.values() if p.net_qty != 0 or p.realized_pnl != 0]

    @property
    def fills(self) -> list[Fill]:
        return list(self._fills)

    async def funds(self) -> Funds:
        realized = sum((p.realized_pnl for p in self._positions.values()), D("0"))
        charges = sum((p.charges for p in self._positions.values()), D("0"))
        unreal = sum((p.unrealized_pnl for p in self._positions.values()), D("0"))
        used = sum((self._margin(p.contract, p.product, p.net_qty, p.avg_price)
                    for p in self._positions.values()), D("0"))
        ledger = self.starting_capital + realized - charges
        return Funds(available_cash=(ledger - used).quantize(D("0.01")), used_margin=used.quantize(D("0.01")),
                     realized_pnl=(realized - charges).quantize(D("0.01")), unrealized_pnl=unreal.quantize(D("0.01")))

    # ------------------------------------------------------------- internals
    def _get(self, order_id: str) -> Order:
        try:
            return self._orders[order_id]
        except KeyError:
            raise BrokerError(f"unknown order {order_id}") from None

    def _reject(self, o: Order, msg: str) -> Order:
        o.status, o.message, o.updated_at = OrderStatus.REJECTED, msg, self._clock()
        return o

    def _margin(self, c: Contract, product: Product, qty: int, price: Decimal) -> Decimal:
        notional = abs(D(qty)) * D(price)
        if c.instrument is Instrument.OPTION:
            return notional if qty > 0 else notional * self.short_option_margin_multiple  # crude SPAN proxy
        if c.instrument is Instrument.FUTURE:
            return notional * self.fno_margin_pct / 100
        if product is Product.INTRADAY:
            return notional * self.intraday_margin_pct / 100
        return notional

    def _pretrade_check(self, req: OrderRequest, price: Decimal) -> str | None:
        key = (req.contract.key, req.product)
        pos = self._positions.get(key)
        cur = pos.net_qty if pos else 0
        signed = req.quantity if req.side is Side.BUY else -req.quantity
        new = cur + signed
        if (req.product is Product.DELIVERY and req.contract.instrument is Instrument.EQUITY and new < 0):
            return "short selling not allowed in DELIVERY"
        if abs(new) <= abs(cur) and (cur == 0 or (new >= 0) == (cur >= 0)):
            return None  # reduces exposure: always allowed
        old_m = self._margin(req.contract, req.product, cur, pos.avg_price if pos else price)
        new_m = self._margin(req.contract, req.product, new, price)
        incremental = new_m - old_m
        ledger = (self.starting_capital
                  + sum((p.realized_pnl - p.charges for p in self._positions.values()), D("0")))
        used = sum((self._margin(p.contract, p.product, p.net_qty, p.avg_price)
                    for p in self._positions.values()), D("0"))
        if incremental > ledger - used:
            return f"insufficient funds: need {incremental:.2f}, available {ledger - used:.2f}"
        return None

    def _try_fill(self, o: Order) -> Fill | None:
        req = o.request
        ltp = self._ltp.get(req.contract.key)
        if ltp is None:
            return None
        buy = req.side is Side.BUY
        ot = req.order_type
        if ot in (OrderType.SL_MARKET, OrderType.SL_LIMIT):
            trig = D(req.trigger_price)
            if not ((buy and ltp >= trig) or (not buy and ltp <= trig)):
                return None
        if ot in (OrderType.MARKET, OrderType.SL_MARKET):
            slip = ltp * self.slippage_bps / D("10000")
            px = _round_tick(ltp + slip if buy else ltp - slip, req.contract.tick_size)
        else:
            lim = D(req.price)
            if (buy and ltp > lim) or (not buy and ltp < lim):
                return None
            px = min(lim, ltp) if buy else max(lim, ltp)
        return self._execute(o, px)

    def _execute(self, o: Order, px: Decimal) -> Fill:
        req = o.request
        cb = compute_costs(req.side, req.quantity, px, req.contract.instrument, req.product, self.costs)
        now = self._clock()
        key = (req.contract.key, req.product)
        pos = self._positions.setdefault(key, Position(contract=req.contract, product=req.product))
        signed = req.quantity if req.side is Side.BUY else -req.quantity
        cur, avg = pos.net_qty, pos.avg_price
        if cur == 0 or (cur > 0) == (signed > 0):          # open / add
            new = cur + signed
            pos.avg_price = ((avg * abs(cur) + px * abs(signed)) / abs(new)).quantize(D("0.0001"))
            pos.net_qty = new
        else:                                               # reduce / close / flip
            closed = min(abs(cur), abs(signed))
            direction = 1 if cur > 0 else -1
            pos.realized_pnl += (px - avg) * closed * direction
            new = cur + signed
            pos.net_qty = new
            if new == 0:
                pos.avg_price = D("0")
            elif (new > 0) != (cur > 0):                    # flipped
                pos.avg_price = px
        pos.charges += cb.total
        pos.ltp = self._ltp.get(req.contract.key, px)
        o.status, o.filled_qty, o.avg_fill_price = OrderStatus.FILLED, req.quantity, px
        o.charges, o.updated_at, o.message = cb.total, now, "filled (paper)"
        fill = Fill(order_id=o.order_id, contract=req.contract, side=req.side, quantity=req.quantity,
                    price=px, charges=cb.total, ts=now)
        self._fills.append(fill)
        for cb_ in self.fill_listeners:
            cb_(fill)
        return fill
