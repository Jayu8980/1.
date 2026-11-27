"""Operations available to API keys and MCP clients. Every method takes the verified ApiKey.

LIVE money rule: `place_order` never sends a real order. In LIVE it creates an approval
request that only the phone (biometric) can approve.
"""
from __future__ import annotations

from app.core.enums import Mode
from app.core.errors import BrokerError, OrderBlocked
from app.core.models import Contract
from app.services.apikeys import ApiKey
from app.services.orders import Ticket, submit
from app.services.portfolio import order_view, position_view

VENUES = ("engine", "live", "paper")


class External:
    def __init__(self, state):
        self.s = state

    def _actor(self, k: ApiKey) -> str:
        return f"key:{k.id} ({k.name})"

    def _broker(self, venue: str):
        if venue not in VENUES:
            raise ValueError(f"account must be one of {VENUES}")
        g = self.s.gateway
        if venue == "paper":
            return g.paper
        if venue == "live":
            if g.live is None:
                raise ValueError("no live broker configured")
            return g.live
        return g.broker

    # ------------------------------------------------------------------ read
    async def status(self, k: ApiKey) -> dict:
        g = self.s.gateway
        return {"mode": g.mode.value, "killSwitch": g.kill_switch, "activeBroker": self.s.brokers.active_id,
                "autoTrade": self.s.autotrader.enabled, "feed": self.s.hub.status(), "keyScopes": k.scopes}

    async def brokers(self, k: ApiKey) -> list[dict]:
        return await self.s.brokers.list()

    async def quotes(self, k: ApiKey, symbols: list[str] | None = None) -> list[dict]:
        want = {x.upper() for x in symbols} if symbols else None
        out = []
        for c in self.s.watchlist.all():
            if want and c.symbol.upper() not in want and c.key.upper() not in want:
                continue
            t = self.s.hub.latest.get(c.key)
            out.append({"symbol": c.symbol, "exchange": c.exchange.value, "ltp": float(t.ltp) if t else None,
                        "ts": int(t.ts.timestamp() * 1000) if t else None, "source": t.source if t else None})
        return out

    async def search(self, k: ApiKey, q: str, exchange: str) -> list[dict]:
        return await self.s.instruments.search(q, exchange)

    async def account(self, k: ApiKey, venue: str = "engine") -> dict:
        b = self._broker(venue)
        snap = await self.s.portfolio.snapshot(b)
        return {"account": "paper" if b is self.s.gateway.paper else "live", "broker": getattr(b, "name", ""),
                "equity": snap["equity"], "dayPnl": snap["dayPnl"], "dayPnlPct": snap["dayPnlPct"],
                "openPositions": snap["openPositions"], "mode": snap["mode"], "killSwitch": snap["killSwitch"]}

    async def positions(self, k: ApiKey, venue: str = "engine") -> list[dict]:
        return [position_view(p) for p in await self.s.portfolio.positions(self._broker(venue)) if p.net_qty]

    async def orders(self, k: ApiKey, venue: str = "engine", open_only: bool = False) -> list[dict]:
        rows = sorted((order_view(o) for o in await self._broker(venue).orders()), key=lambda o: -o["ts"])
        return [r for r in rows if r["status"] in ("OPEN", "PENDING")] if open_only else rows

    async def risk(self, k: ApiKey) -> dict:
        return await self.s.risk.view(self.s.gateway.kill_switch)

    async def signals(self, k: ApiKey, limit: int = 20) -> list[dict]:
        return list(self.s.autotrader.signals)[:limit]

    async def intent(self, k: ApiKey, intent_id: str) -> dict:
        i = self.s.intents.get(intent_id)
        if i is None:
            raise ValueError(f"no approval request {intent_id}")
        return i.view()

    # ------------------------------------------------------------------ trade
    async def place_order(self, k: ApiKey, ticket: dict, reason: str = "") -> dict:
        t = Ticket(**ticket)
        if self.s.gateway.mode is not Mode.LIVE:
            return await submit(self.s, t, self._actor(k), "api")
        # LIVE: validate now (watchlist, lot size, price) so the phone only sees sendable orders.
        from app.services.orders import build_request
        _, _, err = build_request(self.s, t)
        if err:
            return {"ok": False, "message": err}
        i = self.s.intents.create(t.model_dump(mode="json"), self._actor(k), reason or "no reason given")
        await self.s.audit.record("intent.created", intent=i.id, by=i.requested_by, ticket=i.ticket, reason=i.reason)
        return {"ok": True, "status": "PENDING_APPROVAL", "intentId": i.id, "expiresInSec": i.view()["expiresInSec"],
                "message": "Engine is LIVE: real orders need your approval in the AI Trader app (fingerprint/face). "
                           f"Check status with intent id {i.id}."}

    # ------------------------------------------------------------------ control
    async def cancel(self, k: ApiKey, order_id: str, venue: str = "engine") -> dict:
        try:
            o = await self.s.gateway.cancel(order_id, self._actor(k), force_paper=venue == "paper",
                                            force_live=venue == "live")
        except BrokerError as e:
            return {"ok": False, "message": str(e)}
        return {"ok": True, "message": f"order {order_id}: {o.status.value}"}

    async def kill_switch(self, k: ApiKey, reason: str) -> dict:
        r = await self.s.gateway.engage_kill_switch(self._actor(k), reason or "via API")
        return {"ok": True, "message": f"Kill switch ENGAGED, cancelled {len(r['cancelled'])} entry order(s). "
                                       "Only the app can release it."}

    async def autotrade_off(self, k: ApiKey) -> dict:
        self.s.autotrader.store.set_autotrade(False)
        await self.s.audit.record("autotrade.off", actor=self._actor(k))
        return {"ok": True, "message": "Auto-trade OFF. Only the app can turn it back on."}

    async def connect_broker(self, k: ApiKey, broker_id: str) -> dict:
        try:
            return await self.s.brokers.start_connect(broker_id, self._actor(k))
        except (BrokerError, OrderBlocked) as e:
            return {"ok": False, "message": getattr(e, "reason", None) or str(e)}

    async def watch(self, k: ApiKey, contract: dict) -> dict:
        c = Contract.model_validate(contract)
        self.s.watchlist.add(c); self.s.hub.add(c)
        return {"ok": True, "message": f"watching {c.key}"}
