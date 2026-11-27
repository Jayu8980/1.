"""ExecutionGateway: the ONLY path from strategies/API to a broker.

Order of checks (fail closed at every step):
 1. kill switch   2. mode / broker selection   3. risk veto (absolute)
 4. LIVE only: static-IP check   5. rate limit (inside SmartAPI client)   6. broker
Everything is written to the hash-chained audit log, including blocks.
"""
from __future__ import annotations

import asyncio
import hmac
from decimal import Decimal

from app.broker.base import Broker
from app.core.audit import AuditLog
from app.core.enums import Mode
from app.core.errors import OrderBlocked
from app.core.ipguard import StaticIPGuard
from app.core.models import Order, OrderRequest

from .risk_hook import RiskVeto


class ExecutionGateway:
    def __init__(self, paper: Broker, risk: RiskVeto, audit: AuditLog, *,
                 live: Broker | None = None, ip_guard: StaticIPGuard | None = None,
                 allow_live: bool = False, live_confirm_phrase: str = ""):
        self.paper, self.live = paper, live
        self.risk, self.audit, self.ip_guard = risk, audit, ip_guard
        self.allow_live = allow_live
        self._phrase = live_confirm_phrase
        self.mode = Mode.PAPER
        self.kill_switch = False
        self._lock = asyncio.Lock()

    @property
    def broker(self) -> Broker:
        return self.live if self.mode is Mode.LIVE else self.paper

    # ------------------------------------------------------------ controls
    async def set_mode(self, mode: Mode, *, confirm_phrase: str = "", actor: str = "system") -> Mode:
        async with self._lock:
            if mode is Mode.LIVE:
                problems = []
                if not self.allow_live:
                    problems.append("server has ENGINE_ALLOW_LIVE=false")
                if self.live is None or self.ip_guard is None:
                    problems.append("live broker / IP guard not configured")
                if not self._phrase or not hmac.compare_digest(confirm_phrase, self._phrase):
                    problems.append("confirmation phrase mismatch")
                if self.kill_switch:
                    problems.append("kill switch is engaged")
                if problems:
                    await self.audit.record("mode.live_denied", actor=actor, problems=problems)
                    raise OrderBlocked("; ".join(problems), "LIVE_DENIED")
                await self.ip_guard.assert_ok()
            self.mode = mode
            await self.audit.record("mode.changed", actor=actor, mode=mode)
            return mode

    async def engage_kill_switch(self, actor: str, reason: str = "") -> dict:
        """Block all new orders, cancel all working orders, drop to PAPER. Does NOT
        auto-flatten positions (that's a separate, explicit action)."""
        self.kill_switch = True
        await self.audit.record("kill_switch.engaged", actor=actor, reason=reason, mode=self.mode)
        cancelled, errors = [], []
        for b in {id(x): x for x in (self.paper, self.live) if x}.values():
            try:
                for o in await b.orders():
                    if o.status.value in ("PENDING", "OPEN"):
                        try:
                            await b.cancel_order(o.order_id)
                            cancelled.append(o.order_id)
                        except Exception as e:
                            errors.append(f"{o.order_id}: {e}")
            except Exception as e:
                errors.append(f"{b.name}: {e}")
        self.mode = Mode.PAPER
        await self.audit.record("kill_switch.result", cancelled=cancelled, errors=errors)
        return {"cancelled": cancelled, "errors": errors}

    async def release_kill_switch(self, actor: str) -> None:
        self.kill_switch = False
        await self.audit.record("kill_switch.released", actor=actor)

    # ------------------------------------------------------------ orders
    async def place(self, req: OrderRequest, ref_price: Decimal | None = None) -> Order:
        mode = self.mode
        ctx = dict(mode=mode, cid=req.client_order_id, strategy=req.strategy, symbol=req.contract.key,
                   side=req.side, qty=req.quantity, type=req.order_type, price=req.price)
        try:
            if self.kill_switch:
                raise OrderBlocked("kill switch engaged", "KILL_SWITCH")
            broker = self.broker
            if broker is None:
                raise OrderBlocked("no broker for mode", "NO_BROKER")
            if ref_price is None:
                ref_price = req.price if req.price is not None else await broker.ltp(req.contract)
            decision = await self.risk.check(req, Decimal(ref_price))
            if not decision.approved:
                raise OrderBlocked(decision.reason, "RISK_VETO")
            if mode is Mode.LIVE:
                ip = await self.ip_guard.assert_ok()
                ctx["egress_ip"] = ip
        except OrderBlocked as e:
            await self.audit.record("order.blocked", code=e.code, reason=e.reason, **ctx)
            raise
        await self.audit.record("order.submit", **ctx)
        try:
            order = await broker.place_order(req)
        except Exception as e:
            await self.audit.record("order.error", error=str(e), **ctx)
            raise
        await self.audit.record("order.ack", order_id=order.order_id, status=order.status,
                                fill_price=order.avg_fill_price, msg=order.message, **ctx)
        return order

    async def cancel(self, order_id: str, actor: str = "system") -> Order:
        o = await self.broker.cancel_order(order_id)  # cancels always allowed, even under kill switch
        await self.audit.record("order.cancel", order_id=order_id, actor=actor, status=o.status)
        return o
