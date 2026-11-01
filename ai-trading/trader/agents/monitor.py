"""Position monitor (runs on its own, after orders are placed)."""
from __future__ import annotations

from ..safety import check_daily_loss, journal
from .base import Agent


class MonitorAgent(Agent):
    name = "monitor"
    title = "Position Monitor"
    layer = 14
    role = ("Watches open positions: fills pending paper orders, applies stops, targets, "
            "break-even trailing and time exits, and trips the kill switch on the daily loss limit.")

    def run(self) -> list[dict]:
        broker = self.services["broker"]
        symbols = [p["symbol"] for p in broker.positions()]
        frames = self.services["data"].get(symbols, refresh=True) if symbols else {}
        closed = broker.manage(frames, trail=self.cfg.execution.trail_to_breakeven)
        for trade in closed:
            journal(self.cfg, "exit", symbol=trade["symbol"], side=trade.get("side"), qty=trade.get("qty"),
                    price=trade.get("exit"), pnl=trade.get("pnl"), r_multiple=trade.get("r_multiple"),
                    reason=trade.get("reason"), status=trade.get("status", "closed"))
            self.bus.publish("position.closed", self.name, **trade)
        equity = float(broker.account().get("equity") or 0)
        alert = check_daily_loss(self.cfg, equity)
        if alert:
            self.bus.publish("risk.alert", self.name, message=alert)
        return closed
