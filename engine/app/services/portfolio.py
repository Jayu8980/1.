"""Builds the app's Dashboard / Position / Order views from whichever broker is in use
(paper or the active live broker), and records the equity curve vs NIFTY."""
from __future__ import annotations

import json
from datetime import datetime, timezone
from decimal import Decimal
from pathlib import Path
from zoneinfo import ZoneInfo

from app.core.enums import Mode
from app.core.models import Order, Position

IST = ZoneInfo("Asia/Kolkata")
D = Decimal


def position_view(p: Position, strategy: str = "") -> dict:
    return {"symbol": p.contract.symbol, "exchange": p.contract.exchange.value, "product": p.product.value,
            "qty": p.net_qty, "avgPrice": float(p.avg_price), "ltp": float(p.ltp or p.avg_price),
            "realizedPnl": float(p.realized_pnl - p.charges), "strategy": strategy or "",
            "multiplier": float(p.contract.multiplier)}


def order_view(o: Order) -> dict:
    r = o.request
    return {"orderId": o.order_id, "symbol": r.contract.symbol, "side": r.side.value, "qty": r.quantity,
            "type": r.order_type.value, "price": float(r.price) if r.price is not None else None,
            "status": o.status.value, "avgFillPrice": float(o.avg_fill_price), "strategy": r.strategy,
            "ts": int(o.updated_at.timestamp() * 1000)}


class Portfolio:
    def __init__(self, gateway, hub, curve_path: str | Path, benchmark_key: str, max_points: int = 2000):
        self.gw, self.hub = gateway, hub
        self.curve_path = Path(curve_path); self.curve_path.parent.mkdir(parents=True, exist_ok=True)
        self.benchmark_key = benchmark_key
        self.max_points = max_points
        self._sod: dict[str, tuple[str, Decimal]] = {}   # broker name -> (IST date, start-of-day equity)
        self._last_nifty: float | None = None

    async def positions(self, broker=None) -> list[Position]:
        ps = await (broker or self.gw.broker).positions()
        for p in ps:   # live brokers' LTP lags; overlay the streaming price when we have one
            t = self.hub.latest.get(p.contract.key)
            if t is not None:
                p.ltp = t.ltp
        return ps

    async def snapshot(self, broker=None) -> dict:
        """Account snapshot for `broker` (default: the one the engine mode currently uses)."""
        broker = broker or self.gw.broker
        ps = await self.positions(broker)
        f = await broker.funds()
        unreal = sum((p.unrealized_pnl for p in ps), D("0"))
        realized = sum((p.realized_pnl - p.charges for p in ps), D("0"))
        equity = f.available_cash + f.used_margin + unreal
        today = datetime.now(IST).date().isoformat()
        name = getattr(broker, "name", "broker")
        if name not in self._sod or self._sod[name][0] != today:
            self._sod[name] = (today, equity - (realized + unreal))
        day_pnl = realized + unreal
        sod = self._sod[name][1] or D("1")
        return {"mode": self.gw.mode.value, "killSwitch": self.gw.kill_switch,
                "dayPnl": float(day_pnl), "dayPnlPct": float(day_pnl / sod * 100),
                "totalPnl": float(equity - self.starting_equity(equity)), "equity": float(equity),
                "openPositions": sum(1 for p in ps if p.net_qty), "equityDec": equity, "dayPnlDec": day_pnl}

    def starting_equity(self, fallback: Decimal) -> Decimal:
        if self.gw.mode is Mode.PAPER and hasattr(self.gw.paper, "starting_capital"):
            return self.gw.paper.starting_capital
        pts = self.curve()
        return D(str(pts[0]["equity"])) if pts else fallback

    def _nifty(self) -> float | None:
        t = self.hub.latest.get(self.benchmark_key)
        if t is not None:
            self._last_nifty = float(t.ltp)
        return self._last_nifty

    async def record(self) -> dict:
        snap = await self.snapshot()
        pt = {"t": int(datetime.now(timezone.utc).timestamp() * 1000), "equity": snap["equity"],
              "nifty": self._nifty(), "mode": snap["mode"]}
        with self.curve_path.open("a") as fh:
            fh.write(json.dumps(pt) + "\n")
        return pt

    def curve(self) -> list[dict]:
        if not self.curve_path.exists():
            return []
        pts = [json.loads(x) for x in self.curve_path.read_text().splitlines() if x.strip()]
        if len(pts) > self.max_points:
            step = len(pts) / self.max_points
            pts = [pts[int(i * step)] for i in range(self.max_points)] + [pts[-1]]
        return pts

    async def dashboard(self) -> dict:
        snap = await self.snapshot()
        pts = self.curve()
        now = {"t": int(datetime.now(timezone.utc).timestamp() * 1000), "equity": snap["equity"], "nifty": self._nifty()}
        pts = pts + [now]
        # If no benchmark price was ever seen, draw NIFTY flat rather than inventing data.
        first_n = next((p["nifty"] for p in pts if p.get("nifty")), None)
        curve, last_n = [], first_n
        for p in pts:
            last_n = p.get("nifty") or last_n
            curve.append({"t": p["t"], "equity": p["equity"],
                          "nifty": last_n if last_n is not None else pts[0]["equity"]})
        snap.pop("equityDec"); snap.pop("dayPnlDec")
        at = getattr(self, "autotrader", None)
        return {**snap, "curve": curve, "updatedAt": now["t"], "benchmarkLive": first_n is not None,
                "autoTrade": bool(at and at.enabled), "autoOpenTrades": len(at.open_trades()) if at else 0}
