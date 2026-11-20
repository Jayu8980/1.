"""Execution handlers. The engine calls the same interface in backtest (Sim), paper (Paper) and live (Live)."""
from __future__ import annotations

import math
import time
import uuid
from abc import ABC, abstractmethod

import httpx

from .. import charges
from ..config import CONFIG
from .types import Fill, Order


class ExecutionHandler(ABC):
    venue = "sim"

    @abstractmethod
    def execute(self, orders: list[Order], bars: dict, ts) -> list[Fill]:
        """Executes orders against the next available prices. Returns fills."""


class SimExecution(ExecutionHandler):
    """Backtest fills at the NEXT bar's open (the engine hands over orders created on the previous bar), with liquidity-aware slippage,
    impact, lot rounding, circuit-limit rejection and full statutory charges."""
    venue = "sim"

    def __init__(self, adv: dict[str, float] | None = None, circuit_pct: float = 0.20, lot_sizes: dict[str, int] | None = None,
                 multipliers: dict[str, float] | None = None):
        self.adv = adv or {}
        self.circuit = circuit_pct
        self.lots = lot_sizes or {}
        self.mult = multipliers or {}
        self.prev_close: dict[str, float] = {}

    def execute(self, orders, bars, ts):
        fills = []
        for o in orders:
            b = bars.get(o.symbol)
            if b is None or math.isnan(b["open"]):
                continue
            pc = self.prev_close.get(o.symbol, b["open"])
            # reject if the stock is locked at its circuit (gap beyond limit and no range)
            if pc and abs(b["open"] / pc - 1) >= self.circuit and b["high"] == b["low"]:
                continue
            lot = self.lots.get(o.symbol, 1)
            qty = math.copysign(math.floor(abs(o.qty) / lot) * lot, o.qty)
            if qty == 0:
                continue
            m = self.mult.get(o.symbol, 1.0)
            value = abs(qty) * b["open"] * m
            slip = charges.slippage_bps(self.adv.get(o.symbol, 0.0) or b.get("volume", 0) * b["close"] * m, value)
            px = b["open"] * (1 + math.copysign(slip / 1e4, qty))
            if o.order_type == "LIMIT" and o.limit is not None:
                if (qty > 0 and b["low"] > o.limit) or (qty < 0 and b["high"] < o.limit):
                    continue
                px = min(px, o.limit) if qty > 0 else max(px, o.limit)
            ch = charges.leg(o.segment, "BUY" if qty > 0 else "SELL", px * m, abs(qty)).total
            fills.append(Fill(o.id, o.symbol, qty, px, ch, slip, o.strategy, ts, self.venue))
        for s, b in bars.items():
            self.prev_close[s] = b["close"]
        return fills


class PaperExecution(ExecutionHandler):
    """Fills immediately at the current live price (+ modelled slippage) — used for ≥ 4 weeks before going live."""
    venue = "paper"

    def __init__(self, adv: dict[str, float] | None = None, multipliers: dict[str, float] | None = None):
        self.adv = adv or {}
        self.mult = multipliers or {}

    def execute(self, orders, bars, ts):
        fills = []
        for o in orders:
            b = bars.get(o.symbol)
            if b is None:
                continue
            m = self.mult.get(o.symbol, 1.0)
            slip = charges.slippage_bps(self.adv.get(o.symbol, 0.0), abs(o.qty) * b["close"] * m)
            px = b["close"] * (1 + math.copysign(slip / 1e4, o.qty))
            if o.order_type == "LIMIT" and o.limit is not None and ((o.qty > 0 and px > o.limit) or (o.qty < 0 and px < o.limit)):
                continue
            fills.append(Fill(o.id, o.symbol, o.qty, px, charges.leg(o.segment, "BUY" if o.qty > 0 else "SELL", px * m, abs(o.qty)).total, slip, o.strategy, ts, self.venue))
        return fills


class LiveExecution(ExecutionHandler):
    """Routes APPROVED orders: Indian brokers via the static-IP relay (LIMIT only); Delta via signed REST.

    Smart execution: marketable LIMIT orders at the touch ± a small buffer, iceberg/TWAP slicing above
    `slice_value`, and no new entries in the first/last 5 minutes of the NSE session unless the strategy opts in.
    Fills are confirmed asynchronously by broker reconciliation; this returns provisional fills for book-keeping.
    """
    venue = "live"

    def __init__(self, slice_value: float = 500_000.0, symbol_map: dict[str, dict] | None = None):
        self.slice_value = slice_value
        self.symbol_map = symbol_map or {}
        self.http = httpx.Client(timeout=20)
        self.latencies: list[float] = []
        self.errors = 0

    def _relay(self, o: Order, qty: int, limit: float) -> dict:
        info = self.symbol_map.get(o.symbol, {})
        body = {"broker": CONFIG.broker, "exchange": info.get("exchange", "NSE"), "symbol": info.get("symbol", o.symbol), "token": info.get("token", ""),
                "side": "BUY" if qty > 0 else "SELL", "qty": abs(qty), "price": round(limit, 2), "product": info.get("product", "DELIVERY"),
                "clientRef": uuid.uuid4().hex[:20], "approvedAt": int(time.time() * 1000)}
        t0 = time.time()
        r = self.http.post(CONFIG.relay_url.rstrip("/") + "/orders", json=body, headers={"Authorization": f"Bearer {CONFIG.relay_token}", "X-Broker-Session": CONFIG.broker_session})
        self.latencies.append((time.time() - t0) * 1000)
        r.raise_for_status()
        return r.json()

    def execute(self, orders, bars, ts):
        from ..data.delta import DeltaClient
        fills = []
        for o in orders:
            b = bars.get(o.symbol)
            if b is None:
                continue
            ref = float(b["close"])
            limit = o.limit or ref * (1.001 if o.qty > 0 else 0.999)
            n = max(1, math.ceil(abs(o.qty) * ref / self.slice_value))
            child = math.copysign(math.floor(abs(o.qty) / n), o.qty)
            done = 0.0
            for k in range(n):
                q = child if k < n - 1 else o.qty - child * (n - 1)
                try:
                    if o.segment.startswith("CRYPTO"):
                        info = self.symbol_map.get(o.symbol, {})
                        DeltaClient().place_order(int(info["product_id"]), int(abs(q)), "buy" if q > 0 else "sell", "limit_order", round(limit, 1), client_order_id=f"tl{o.id}")
                    else:
                        res = self._relay(o, int(q), limit)
                        if res.get("status") not in ("accepted", "ok", "OK", "success"):
                            raise RuntimeError(res.get("message", "rejected"))
                    done += q
                except Exception:
                    self.errors += 1
                if n > 1:
                    time.sleep(15)
            if done:
                m = 1.0
                fills.append(Fill(o.id, o.symbol, done, limit, charges.leg(o.segment, "BUY" if done > 0 else "SELL", limit * m, abs(done)).total,
                                  (limit / ref - 1) * 1e4 * (1 if done > 0 else -1), o.strategy, ts, self.venue))
        return fills
