"""Layer 13 - Execution trader."""
from __future__ import annotations

from ..brokers.base import BrokerError
from ..safety import journal, safety_block
from .base import Agent


class ExecutionAgent(Agent):
    name = "execution"
    title = "Execution Trader"
    layer = 13
    role = ("Runs the final safety checks (kill switch, daily loss limit, order-size cap) and sends "
            "each approved plan to the broker as entry + stop-loss + take-profit, journaling every order.")

    def run(self) -> list[dict]:
        broker = self.services["broker"]
        ex = self.cfg.execution
        approved = sorted(self.board.alive(), key=lambda c: c.cache.get("rank", 99))
        results = []
        blocked = safety_block(self.cfg, broker) if approved else None
        for cand in approved:
            plan = cand.plan
            if blocked:
                result = {"status": "blocked", "reason": blocked}
            elif not plan.get("sized_by_broker") and plan["notional"] > ex.max_order_value * 1.0001:
                result = {"status": "blocked", "reason": "order value above execution.max_order_value"}
            else:
                try:
                    result = broker.submit(plan)
                except BrokerError as exc:
                    result = {"status": "error", "reason": str(exc)}
            journal(self.cfg, "order", symbol=plan["symbol"], side=plan["side"], qty=plan["qty"],
                    entry_ref=plan["entry_ref"], stop=plan["stop"], target=plan["target"],
                    setup=plan["setup"], status=result.get("status"), order_id=result.get("id", ""),
                    reason=result.get("reason", ""))
            self.bus.publish("order.submitted", self.name, symbol=plan["symbol"], side=plan["side"],
                             qty=plan["qty"], status=result.get("status"), reason=result.get("reason", ""),
                             live=broker.live)
            results.append({**plan, **result})
        return results
