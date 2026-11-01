"""Built-in paper broker: simulates fills and exits on daily bars and keeps its state in JSON."""
from __future__ import annotations

import json
import time
from datetime import datetime
from pathlib import Path

import pandas as pd

from .base import Broker, BrokerError


class PaperBroker(Broker):
    name = "paper"
    live = False

    def __init__(self, state_dir: str, starting_equity: float = 100_000.0,
                 fee_pct: float = 0.0005, slippage_pct: float = 0.0005) -> None:
        self.path = Path(state_dir) / "paper_account.json"
        self.fee, self.slip = fee_pct, slippage_pct
        if self.path.exists():
            self.state = json.loads(self.path.read_text())
        else:
            self.state = {"cash": starting_equity, "start_equity": starting_equity, "positions": {},
                          "closed": [], "created": datetime.now().isoformat(timespec="seconds")}

    def _save(self) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(self.state, indent=2))
        tmp.replace(self.path)

    def account(self) -> dict:
        equity = self.state["cash"]
        for p in self.state["positions"].values():
            if p["status"] == "open":
                equity += (1 if p["side"] == "long" else -1) * p["qty"] * p["last_price"]
        return {"equity": equity, "cash": self.state["cash"], "start_equity": self.state["start_equity"]}

    def positions(self) -> list[dict]:
        return [{"symbol": s, **p} for s, p in self.state["positions"].items()]

    def closed_trades(self) -> list[dict]:
        return list(self.state["closed"])

    def submit(self, plan: dict) -> dict:
        symbol = plan["symbol"]
        if symbol in self.state["positions"]:
            raise BrokerError(f"{symbol}: a position or order is already open")
        self.state["positions"][symbol] = {
            "status": "pending", "side": plan["side"], "qty": plan["qty"], "entry": None,
            "stop": plan["stop"], "initial_stop": plan["stop"], "target": plan["target"],
            "max_hold": plan["max_hold"], "setup": plan.get("setup"), "signal_date": plan["signal_date"],
            "checked_through": plan["signal_date"], "bars_held": 0, "last_price": plan["entry_ref"],
            "opened": None,
        }
        self._save()
        return {"id": f"paper-{symbol}-{int(time.time())}", "status": "pending",
                "reason": "fills at the next session's open"}

    def manage(self, frames: dict, trail: bool = True) -> list[dict]:
        """Replay new daily bars: fill pending entries at the open, then stops, targets, time exits."""
        done = []
        for symbol, p in list(self.state["positions"].items()):
            df = frames.get(symbol)
            if df is None or df.empty:
                continue
            d = 1 if p["side"] == "long" else -1
            for ts, bar in df[df.index > pd.Timestamp(p["checked_through"])].iterrows():
                o, h, l, c = (float(bar[k]) for k in ("Open", "High", "Low", "Close"))
                day = ts.date().isoformat()
                p["checked_through"] = day
                first = False
                if p["status"] == "pending":
                    fill = o * (1 + d * self.slip)
                    if d * (fill - p["stop"]) <= 0 or d * (p["target"] - fill) <= 0:
                        del self.state["positions"][symbol]
                        done.append({"symbol": symbol, "side": p["side"], "qty": p["qty"], "status": "cancelled",
                                     "reason": "opened beyond the stop or target", "closed": day})
                        break
                    self.state["cash"] -= d * fill * p["qty"] + fill * p["qty"] * self.fee
                    p.update(status="open", entry=fill, opened=day)
                    first = True
                p["bars_held"] += 1
                p["last_price"] = c
                exit_px, reason = None, None
                if d > 0:
                    if l <= p["stop"]:
                        exit_px, reason = (p["stop"] if first else min(o, p["stop"])), "stop"
                    elif h >= p["target"]:
                        exit_px, reason = (p["target"] if first else max(o, p["target"])), "target"
                else:
                    if h >= p["stop"]:
                        exit_px, reason = (p["stop"] if first else max(o, p["stop"])), "stop"
                    elif l <= p["target"]:
                        exit_px, reason = (p["target"] if first else min(o, p["target"])), "target"
                if exit_px is None and p["bars_held"] >= p["max_hold"]:
                    exit_px, reason = c, "time"
                if exit_px is not None:
                    done.append(self._close(symbol, exit_px, reason, day))
                    break
                one_r = abs(p["entry"] - p["initial_stop"])
                if trail and d * (c - p["entry"]) >= one_r:  # lock in break-even after +1R
                    p["stop"] = max(p["stop"], p["entry"]) if d > 0 else min(p["stop"], p["entry"])
        self._save()
        return done

    def _close(self, symbol: str, price: float, reason: str, day: str) -> dict:
        p = self.state["positions"].pop(symbol)
        d = 1 if p["side"] == "long" else -1
        qty = p["qty"]
        fill = price * (1 - d * self.slip)
        self.state["cash"] += d * fill * qty - fill * qty * self.fee
        pnl = d * (fill - p["entry"]) * qty - (p["entry"] + fill) * qty * self.fee
        risk = abs(p["entry"] - p["initial_stop"]) * qty
        trade = {"symbol": symbol, "side": p["side"], "qty": qty, "entry": p["entry"], "exit": fill,
                 "pnl": pnl, "r_multiple": pnl / risk if risk else 0.0, "opened": p["opened"],
                 "closed": day, "reason": reason, "setup": p.get("setup"), "status": "closed"}
        self.state["closed"].append(trade)
        return trade
