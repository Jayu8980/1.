"""Long-running paper/live service behind the API: keeps one Engine (same code as backtests), feeds it new
bars on a schedule, queues signals for one-tap approval in the app and broadcasts events over WebSocket."""
from __future__ import annotations

import asyncio
import json
import time
import uuid
from collections import deque
from dataclasses import asdict, dataclass, field
from datetime import datetime, time as dtime
from zoneinfo import ZoneInfo

import numpy as np
import pandas as pd

from . import charges, __version__
from .config import CONFIG, RiskLimits
from .construction import StrategyStats, hedge_qty
from .core.engine import Engine
from .core.execution import LiveExecution, PaperExecution
from .core.types import Order
from .data import nse, yahoo
from .promotion import Pipeline
from .regime import detect, probabilities
from .risk import RiskManager
from .strategies import REGISTRY

IST = ZoneInfo("Asia/Kolkata")


@dataclass
class PendingSignal:
    id: str
    strategy: str
    symbol: str
    direction: str
    strength: float
    confidence: float
    entry: float | None
    stop: float | None
    target: float | None
    horizon: str
    qty: float
    status: str
    createdAt: int
    reason: str
    venue: str
    charges: float
    marginRequired: float
    leverage: float = 1.0
    liquidationPrice: float | None = None
    order: dict = field(default_factory=dict)


class Audit:
    def __init__(self):
        self.path = CONFIG.state_dir / "audit.jsonl"

    def log(self, category: str, message: str, payload: dict | None = None) -> None:
        with self.path.open("a") as f:
            f.write(json.dumps({"time": int(time.time()), "category": category, "message": message, "payload": json.dumps(payload or {}, default=str)}) + "\n")

    def tail(self, n: int = 300) -> list[dict]:
        if not self.path.exists():
            return []
        return [json.loads(l) for l in deque(self.path.open(), maxlen=n)][::-1]


class EngineService:
    def __init__(self):
        CONFIG.ensure_dirs()
        self.audit = Audit()
        self.pipeline = Pipeline(CONFIG.state_dir / "pipeline.json")
        self.risk = RiskManager(CONFIG.risk, CONFIG.state_dir / "risk.json")
        self.signals: dict[str, PendingSignal] = {}
        self.clients: set = set()
        self.regime = "range"
        self.regime_probs: dict[str, float] = {}
        self.engine: Engine | None = None
        self.data: dict[str, pd.DataFrame] = {}
        self.sectors: dict[str, str] = {}
        self.universe: list[str] = []
        self.last_cycle = 0.0
        self.slippage: list[float] = []
        self.latency: list[float] = []
        self._task: asyncio.Task | None = None
        for name in REGISTRY:
            self.pipeline.get(name)
        self.pipeline.save()

    # ------------------------------------------------------------ events
    async def broadcast(self, kind: str, data: dict) -> None:
        msg = json.dumps({"type": kind, "data": data}, default=str)
        for ws in list(self.clients):
            try:
                await ws.send_text(msg)
            except Exception:
                self.clients.discard(ws)

    def emit(self, kind: str, data: dict) -> None:
        if kind == "risk":
            self.audit.log("RISK", data.get("message", kind), data)
        if kind == "fill":
            self.audit.log("RESPONSE", f"fill {data['qty']:+.0f} {data['symbol']} @ {data['price']:.2f} ({data['strategy']})", data)
            self.slippage.append(0.0)
        try:
            asyncio.get_running_loop().create_task(self.broadcast(kind, data))
        except RuntimeError:
            pass

    # ------------------------------------------------------------ engine lifecycle
    def _stats(self) -> dict[str, StrategyStats]:
        out = {}
        for n, r in self.pipeline.records.items():
            active = r.enabled and not r.killed and r.stage in ("PAPER", "LIVE_SMALL", "LIVE")
            out[n] = StrategyStats(oos_sharpe=max(r.oos_sharpe, 0.1), enabled=active)
        return out

    def _approval(self, o: Order) -> bool:
        rec = self.pipeline.get(o.strategy)
        live = CONFIG.mode == "live" and rec.stage in ("LIVE_SMALL", "LIVE")
        if rec.auto and (not live or rec.stage in ("LIVE_SMALL", "LIVE")):
            self.audit.log("APPROVAL", f"auto-approved {o.strategy} {o.qty:+.0f} {o.symbol}", asdict(o))
            return True
        px = self.engine.last_prices.get(o.symbol, o.limit or 0) if self.engine else (o.limit or 0)
        ch = charges.leg(o.segment, "BUY" if o.qty > 0 else "SELL", px, abs(o.qty)).total
        lev = 1.0 if o.segment in ("EQ_DELIVERY",) else 5.0
        sid = uuid.uuid4().hex[:12]
        self.signals[sid] = PendingSignal(
            sid, o.strategy, o.symbol, "LONG" if o.qty > 0 else "SHORT", 1.0, 0.6, px, None, None, "", abs(o.qty), "PENDING", int(time.time()), o.reason,
            "live" if live else "paper", ch, abs(o.qty) * px / lev, lev, None, asdict(o))
        self.emit("signal", {"strategy": o.strategy, "direction": "LONG" if o.qty > 0 else "SHORT", "symbol": o.symbol})
        return False

    def load(self, universe_index: str = "nifty100", years: int = 3) -> None:
        start = int(time.time()) - years * 365 * 86400
        try:
            cons = nse.constituents(universe_index)
            self.universe = cons["Symbol"].tolist()
            self.sectors = dict(zip(cons["Symbol"], cons["Industry"]))
        except Exception:
            self.universe = ["RELIANCE", "TCS", "HDFCBANK", "INFY", "ICICIBANK", "SBIN", "BHARTIARTL", "ITC", "LT", "KOTAKBANK"]
        self.data = {"NIFTY": yahoo.download("NIFTY 50", "1D", start=start)}
        for s in self.universe:
            try:
                self.data[s] = yahoo.download(s, "1D", start=start)
            except Exception:
                continue
        self._rebuild()

    def _rebuild(self) -> None:
        strategies = [REGISTRY[n]() for n, r in self.pipeline.records.items() if n in REGISTRY and r.enabled]
        ex = LiveExecution() if CONFIG.mode == "live" else PaperExecution()
        reg = detect(self.data["NIFTY"]) if "NIFTY" in self.data and len(self.data["NIFTY"]) > 260 else None
        if reg is not None:
            self.regime = str(reg["regime"].iloc[-1]); self.regime_probs = probabilities(reg)
        self.engine = Engine(strategies, self.data, ex, CONFIG.capital, CONFIG.risk, reg["regime"] if reg is not None else None,
                             None, self.sectors, self._stats(), CONFIG.target_vol, approval=self._approval, on_event=self.emit, risk=self.risk)
        self.engine.ctx.meta = {"sectors": self.sectors}
        # replay history silently so indicators/positions are warm; only the latest bar can create new orders
        idx = sorted(set().union(*[d.index for d in self.data.values()]))
        warm = idx[:-1][-260:]
        approval, self.engine.approval = self.engine.approval, (lambda o: False)
        for ts in warm:
            self.engine.step(ts, {s: d.loc[ts] for s, d in self.data.items() if ts in d.index})
        self.engine.approval = approval

    def cycle(self) -> None:
        """Fetch the latest bar for every symbol and run one engine step (identical code path to backtests)."""
        if self.engine is None:
            self.load()
        now = int(time.time())
        for s in list(self.data):
            try:
                new = yahoo.download("NIFTY 50" if s == "NIFTY" else s, "1D", start=now - 10 * 86400, use_cache=False)
                self.data[s] = pd.concat([self.data[s], new]).loc[lambda d: ~d.index.duplicated(keep="last")].sort_index()
                self.engine._data_ref = self.data
            except Exception:
                self.risk.state.api_errors += 1
        self.engine.ctx._data = self.data
        ts = max(d.index[-1] for d in self.data.values())
        self.engine.step(ts, {s: d.iloc[-1] for s, d in self.data.items() if d.index[-1] == ts})
        self.last_cycle = time.time()
        # promotion / demotion from realised daily P&L of each strategy
        eq = [e for _, e in self.engine.equity_curve[-2:]]
        if len(eq) == 2:
            for name in list(self.pipeline.records):
                if self.pipeline.get(name).enabled:
                    if self.pipeline.record_live_return(name, eq[1] / eq[0] - 1) == "demoted":
                        self.emit("risk", {"kind": "demotion", "message": f"{name} demoted: live drift > 2σ"})
                    self.pipeline.try_promote(name)
        self.emit("status", self.status())

    async def run_forever(self) -> None:
        """Daily strategies run once after the NSE close (15:45 IST) on trading days."""
        last_day = None
        while True:
            now = datetime.now(IST)
            if now.time() >= dtime(15, 45) and last_day != now.date() and nse.is_trading_day(now.date(), set(nse.holidays())):
                try:
                    await asyncio.to_thread(self.cycle)
                    last_day = now.date()
                except Exception as e:
                    self.audit.log("RISK", f"cycle failed: {e}")
            await asyncio.sleep(60)

    def start(self) -> None:
        self._task = asyncio.get_running_loop().create_task(self.run_forever())

    # ------------------------------------------------------------ actions from the app
    def approve(self, sid: str) -> dict:
        s = self.signals.get(sid)
        if not s or s.status != "PENDING":
            return {"ok": False, "message": "not pending"}
        ok, why = self.risk.can_trade(s.strategy)
        if not ok:
            s.status = "REJECTED"; self.audit.log("RISK", f"approval blocked: {why}")
            return {"ok": False, "message": why}
        o = Order(**{k: v for k, v in s.order.items() if k in ("symbol", "qty", "order_type", "limit", "strategy", "segment", "reason")})
        self.audit.log("APPROVAL", f"user approved {s.strategy} {o.qty:+.0f} {o.symbol}", s.order)
        bars = {o.symbol: self.data[o.symbol].iloc[-1]} if o.symbol in self.data else {}
        fills = self.engine.exec.execute([o], bars, pd.Timestamp.now(tz="UTC")) if self.engine else []
        for f in fills:
            self.engine.book.apply(f); self.emit("fill", {"symbol": f.symbol, "qty": f.qty, "price": f.price, "strategy": f.strategy, "venue": f.venue, "side": "BUY" if f.qty > 0 else "SELL"})
        self.risk.record_order()
        s.status = "EXECUTED" if fills else "FAILED"
        return {"ok": bool(fills), "message": s.status}

    def reject(self, sid: str) -> dict:
        if sid in self.signals:
            self.signals[sid].status = "REJECTED"; self.audit.log("APPROVAL", f"user rejected {sid}")
        return {"ok": True}

    def flatten(self, reason: str) -> None:
        if not self.engine:
            return
        orders = self.engine._flatten_orders(reason)
        bars = {o.symbol: self.data[o.symbol].iloc[-1] for o in orders if o.symbol in self.data}
        for f in self.engine.exec.execute(orders, bars, pd.Timestamp.now(tz="UTC")):
            self.engine.book.apply(f)
        self.audit.log("KILL", f"flattened all positions ({reason})")

    def set_kill(self, on: bool) -> None:
        self.risk.state.global_kill = on; self.risk.save()
        self.audit.log("KILL", f"global kill switch {'ON' if on else 'OFF'}")
        if on:
            self.flatten("global kill switch")
            for r in self.pipeline.records.values():
                r.auto = False
            self.pipeline.save()

    def restart(self) -> None:
        self.risk.restart(self.equity()); self.audit.log("KILL", "manual restart after circuit breaker")

    # ------------------------------------------------------------ views
    def equity(self) -> float:
        return self.engine.book.equity(self.engine.last_prices) if self.engine else CONFIG.capital

    def status(self) -> dict:
        eq = self.equity(); st = self.risk.state
        sod = st.start_of_day_equity or eq
        return {"mode": CONFIG.mode, "equity": eq, "cash": self.engine.book.cash if self.engine else eq, "dayPnl": eq - sod, "dayPnlPct": (eq / sod - 1) * 100 if sod else 0,
                "drawdownPct": self.risk.drawdown_pct(eq), "halted": st.halted_for_day, "circuitBroken": st.circuit_broken, "globalKill": st.global_kill,
                "regime": self.regime, "regimeProbs": self.regime_probs, "openPositions": sum(1 for l in (self.engine.book.lots.values() if self.engine else []) if l.qty),
                "ordersToday": st.orders_today, "lastUpdate": int(self.last_cycle), "liveEnabled": CONFIG.mode == "live", "version": __version__}

    def strategies(self) -> list[dict]:
        out = []
        for n, r in self.pipeline.records.items():
            if n not in REGISTRY:
                continue
            cls = REGISTRY[n]
            out.append({"name": n, "description": cls.description, "stage": r.stage, "enabled": r.enabled, "auto": r.auto, "killed": r.killed,
                        "weight": 0.0, "oosSharpe": r.oos_sharpe, "liveSharpe": float(np.mean(r.live_returns) / (np.std(r.live_returns) + 1e-12) * np.sqrt(252)) if len(r.live_returns) > 5 else None,
                        "pbo": r.pbo, "deflatedSharpe": r.dsr, "trades": 0, "regimeActive": cls.active_regimes is None or self.regime in cls.active_regimes,
                        "drift": r.drift_sigma(), "notes": r.notes})
        tot = sum(max(s["oosSharpe"], 0) for s in out if s["enabled"]) or 1
        for s in out:
            s["weight"] = max(s["oosSharpe"], 0) / tot if s["enabled"] else 0.0
        return out

    def positions(self) -> list[dict]:
        if not self.engine:
            return []
        return [{"symbol": s, "qty": l.qty, "avgPrice": l.avg, "ltp": self.engine.last_prices.get(s, l.avg), "pnl": (self.engine.last_prices.get(s, l.avg) - l.avg) * l.qty,
                 "strategy": st, "venue": CONFIG.mode} for (st, s), l in self.engine.book.lots.items() if l.qty]

    def risk_snapshot(self) -> dict:
        eq = self.equity()
        pos = self.positions()
        gross = sum(abs(p["qty"] * p["ltp"]) for p in pos); net = sum(p["qty"] * p["ltp"] for p in pos)
        sect: dict[str, float] = {}
        for p in pos:
            sect[self.sectors.get(p["symbol"], "Other")] = sect.get(self.sectors.get(p["symbol"], "Other"), 0) + abs(p["qty"] * p["ltp"]) / eq * 100
        fo = sum(abs(p["qty"] * p["ltp"]) * 0.15 for p in pos if p["symbol"] in ("NIFTY", "BANKNIFTY"))
        vol = 0.0
        if self.engine and pos:
            w = np.array([p["qty"] * p["ltp"] / eq for p in pos]); syms = [p["symbol"] for p in pos]
            r = self.engine.returns[[s for s in syms if s in self.engine.returns]].tail(60)
            if r.shape[1] == len(syms):
                vol = float(np.sqrt(max(w @ r.cov().values @ w * 252, 0)) * 100)
        lim = asdict(CONFIG.risk)
        return {"limits": lim, "grossExposurePct": gross / eq * 100, "netExposurePct": net / eq * 100, "marginUtilPct": fo / eq * 100,
                "greeks": {"delta": net / max(self.engine.last_prices.get("NIFTY", 1), 1) if self.engine else 0, "gamma": 0.0, "theta": 0.0, "vega": 0.0},
                "avgSlippageBps": float(np.mean([f.slippage_bps for f in self.engine.book.fills[-50:]])) if self.engine and self.engine.book.fills else 0.0,
                "latencyMs": float(np.mean(self.engine.exec.latencies[-20:])) if self.engine and getattr(self.engine.exec, "latencies", None) else 0.0,
                "apiErrors": self.risk.state.api_errors + (getattr(self.engine.exec, "errors", 0) if self.engine else 0),
                "sectorExposure": sect, "portfolioVolPct": vol}

    def set_limits(self, d: dict) -> None:
        for k, v in d.items():
            if hasattr(CONFIG.risk, k):
                setattr(CONFIG.risk, k, type(getattr(CONFIG.risk, k))(v))
        self.audit.log("RISK", "risk limits updated by user", d)

    def hedge_suggestion(self) -> int:
        net = sum(p["qty"] * p["ltp"] for p in self.positions())
        return hedge_qty(self.regime, net, self.engine.last_prices.get("NIFTY", 0) if self.engine else 0)
