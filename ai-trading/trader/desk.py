"""The trading desk: wires the agents to the message bus and runs the layer-by-layer funnel."""
from __future__ import annotations

import logging
from datetime import datetime

from .agents import FUNNEL, ExecutionAgent, MonitorAgent
from .brokers import make_broker
from .bus import Blackboard, Candidate, Message, MessageBus
from .data import MarketData
from .llm import ClaudeClient, llm_available

log = logging.getLogger("trader.desk")


class TradingDesk:
    def __init__(self, cfg, broker=None, use_llm: bool | None = None) -> None:
        self.cfg = cfg
        self.bus = MessageBus()
        self.board = Blackboard()
        self.data = MarketData(cfg.data.source, cfg.data.cache_dir, cfg.data.cache_hours,
                               cfg.universe.history_years)
        self.broker = broker or make_broker(cfg)
        if use_llm is None:
            use_llm = llm_available(cfg.llm.enabled)
        self.llm = ClaudeClient(cfg.llm.model) if use_llm else None
        self.services = {"data": self.data, "broker": self.broker, "llm": self.llm}
        self.agents = [cls(cfg, self.bus, self.board, self.services) for cls in FUNNEL]
        self.bus.subscribe("*", self._narrate)

    def scan(self) -> Blackboard:
        """Run every layer of the funnel and return the blackboard with all dossiers."""
        u = self.cfg.universe
        # A generated volatility index means nothing; synthetic runs use realized volatility.
        vol_symbol = u.volatility_index if self.cfg.data.source != "synthetic" else ""
        frames = self.data.get(u.symbols + [u.benchmark] + ([vol_symbol] if vol_symbol else []))
        bench = frames.get(u.benchmark)
        ctx = self.board.context
        ctx.update(started=datetime.now().isoformat(timespec="minutes"), benchmark=bench,
                   vol_index=frames.get(vol_symbol) if vol_symbol else None,
                   account=self.broker.account(), positions=self.broker.positions(),
                   ai_enabled=self.llm is not None)
        bench_close = bench["Close"] if bench is not None else None
        for symbol in u.symbols:
            self.board.candidates[symbol] = Candidate(symbol, frames.get(symbol), bench_close=bench_close)

        self.bus.publish("scan.start", "desk", symbols=len(u.symbols), source=self.cfg.data.source,
                         broker=self.broker.name, live=self.broker.live, ai=self.llm is not None)
        for agent in self.agents:
            entered = len(self.board.alive())
            if entered:
                agent.run()
            rejected = [(c.symbol, c.rejection) for c in self.board.candidates.values()
                        if c.rejected_by == agent.name]
            passed = len(self.board.alive())
            self.board.funnel.append({"layer": agent.layer, "agent": agent.name, "title": agent.title,
                                      "entered": entered, "passed": passed, "rejected": rejected})
            self.bus.publish("layer.done", agent.name, layer=agent.layer, title=agent.title,
                             entered=entered, passed=passed)
        return self.board

    def execute(self) -> list[dict]:
        """Send the approved plans to the broker (after scan)."""
        return ExecutionAgent(self.cfg, self.bus, self.board, self.services).run()

    def monitor(self) -> list[dict]:
        return MonitorAgent(self.cfg, self.bus, self.board, self.services).run()

    # -- live commentary of the agents' conversation -------------------------------------------
    def _narrate(self, msg: Message) -> None:
        p = msg.payload
        if msg.topic == "scan.start":
            log.info("Desk opens: %d symbols | data %s | broker %s%s | AI review %s", p["symbols"],
                     p["source"], p["broker"], " (LIVE)" if p["live"] else "", "on" if p["ai"] else "off")
        elif msg.topic == "market.regime":
            parts = [f"market {p['regime'].upper()}", f"volatility {p['volatility']}"
                     + (f" ({p['vol_measure']})" if p.get("vol_measure") else "")]
            if p.get("breadth") is not None:
                parts.append(f"breadth {p['breadth']:.0%}")
            parts.append(f"risk x{p['risk_multiplier']:.2f}")
            log.info("    [regime -> all agents] %s", " | ".join(parts))
            for note in p.get("notes", []):
                log.info("    [regime] %s", note)
        elif msg.topic == "candidate.rejected":
            log.debug("      x %-12s %s", p["symbol"], p["reason"])
        elif msg.topic == "layer.done":
            log.info("L%02d %-36s %3d in -> %3d pass", p["layer"], p["title"], p["entered"], p["passed"])
        elif msg.topic == "decision.final":
            picks = ", ".join(f"#{t['rank']} {t['symbol']} {t['side']}" for t in p["trades"]) or "none"
            log.info("    [decision -> execution] final picks: %s", picks)
        elif msg.topic == "order.submitted":
            log.info("    [execution -> broker] %s %s %g: %s %s", p["symbol"], p["side"], p["qty"],
                     p["status"], p.get("reason") or "")
        elif msg.topic == "position.closed":
            log.info("    [monitor] %s %s: %s", p["symbol"], p.get("status", "closed"), p.get("reason", ""))
        elif msg.topic == "risk.alert":
            log.warning("    [monitor] %s", p["message"])
