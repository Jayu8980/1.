"""Data + control endpoints consumed by the Android app (contract: android .../EngineApi.kt)."""
from __future__ import annotations

import asyncio
import json
import time
from decimal import ROUND_HALF_UP, Decimal

from fastapi import APIRouter, Depends, HTTPException, Query, Request, WebSocket, WebSocketDisconnect
from pydantic import BaseModel, ValidationError

from app.core.enums import Exchange, Instrument, Mode, OrderType, Side
from app.core.errors import BrokerError, OrderBlocked
from app.core.models import Contract, OrderRequest
from app.services.portfolio import order_view, position_view
from app.services.orders import Ticket, submit
from app.services.risk import RiskLimits

from .security import current_user, signed_user

router = APIRouter(prefix="/api/v1")


def st(request: Request):
    return request.app.state


def _blocked(e: Exception) -> HTTPException:
    return HTTPException(409, e.reason) if isinstance(e, OrderBlocked) else HTTPException(502, str(e))


# ------------------------------------------------------------------ reads
def _venue_broker(s, venue: str):
    """venue: 'current' (what the engine trades now), 'live' (real broker account, read-only view
    even in PAPER) or 'paper'."""
    if venue == "paper":
        return s.gateway.paper
    if venue == "live":
        b = s.gateway.live
        if b is None:
            raise HTTPException(409, "no live broker configured")
        return b
    return s.gateway.broker


@router.get("/dashboard")
async def dashboard(request: Request, venue: str = Query("current", pattern="^(current|live|paper)$"),
                    _: str = Depends(current_user)):
    s = st(request)
    try:
        return await s.portfolio.dashboard(_venue_broker(s, venue))
    except BrokerError as e:
        raise _blocked(e) from e


@router.get("/positions")
async def positions(request: Request, venue: str = Query("current", pattern="^(current|live|paper)$"),
                    _: str = Depends(current_user)):
    s = st(request)
    try:
        return [position_view(p) for p in await s.portfolio.positions(_venue_broker(s, venue)) if p.net_qty or p.realized_pnl]
    except BrokerError as e:
        raise _blocked(e) from e


@router.get("/orders")
async def orders(request: Request, venue: str = Query("current", pattern="^(current|live|paper)$"),
                 _: str = Depends(current_user)):
    s = st(request)
    try:
        return sorted((order_view(o) for o in await _venue_broker(s, venue).orders()), key=lambda o: -o["ts"])
    except BrokerError as e:
        raise _blocked(e) from e


class OrderTicket(Ticket):
    expectLive: bool = False      # the app states what the user saw; mismatch => refuse


@router.post("/orders")
async def place_order(body: OrderTicket, request: Request, user: str = Depends(signed_user)):
    """Manual order from the app. Goes wherever the engine trades now: the REAL broker when LIVE."""
    s = st(request)
    if body.expectLive != (s.gateway.mode is Mode.LIVE):
        return {"ok": False, "message": "Mode changed since you opened the ticket — review and resend"}
    return await submit(s, Ticket(**body.model_dump(exclude={"expectLive"})), user, "app")


@router.post("/orders/{order_id}/cancel")
async def cancel_order(order_id: str, request: Request, venue: str = Query("current", pattern="^(current|live|paper)$"),
                       user: str = Depends(current_user)):
    """Cancelling only reduces risk: no biometric needed."""
    s = st(request)
    try:
        o = await s.gateway.cancel(order_id, user, force_paper=(venue == "paper"), force_live=(venue == "live"))
    except BrokerError as e:
        return {"ok": False, "message": str(e)}
    return {"ok": True, "message": f"Order {order_id}: {o.status.value}"}


@router.get("/risk")
async def risk(request: Request, _: str = Depends(current_user)):
    s = st(request)
    return await s.risk.view(s.gateway.kill_switch)


@router.get("/audit")
async def audit(request: Request, limit: int = Query(200, le=1000), _: str = Depends(current_user)):
    rows = st(request).audit.tail(limit)
    return [{"ts": r["ts"], "event": r["event"], "data": json.dumps(r["data"], default=str), "hash": r["hash"]}
            for r in reversed(rows)]


@router.get("/signals")
async def signals(request: Request, _: str = Depends(current_user)):
    return list(st(request).autotrader.signals)


@router.get("/strategies")
async def strategies(request: Request, _: str = Depends(current_user)):
    return st(request).autotrader.strategies_view()


class StrategyPatch(BaseModel):
    enabled: bool | None = None
    allocationPct: float | None = None


@router.patch("/strategies/{sid}")
async def patch_strategy(sid: str, body: StrategyPatch, request: Request, user: str = Depends(signed_user)):
    s = st(request)
    if sid not in s.autotrader.store.cfg:
        raise HTTPException(404, "unknown strategy")
    err = s.autotrader.store.update(sid, body.enabled, body.allocationPct)
    if err:
        return {"ok": False, "message": err}
    await s.audit.record("strategy.update", actor=user, strategy=sid, **body.model_dump(exclude_none=True))
    return {"ok": True, "message": "Updated"}


@router.post("/strategies/{sid}/promote")
async def promote(sid: str, request: Request, user: str = Depends(signed_user)):
    s = st(request)
    if sid not in s.autotrader.store.cfg:
        raise HTTPException(404, "unknown strategy")
    try:
        stage = s.autotrader.store.promote(sid)
    except ValueError as e:
        return {"ok": False, "message": f"Promotion gate: {e}"}
    await s.audit.record("strategy.promoted", actor=user, strategy=sid, stage=stage)
    return {"ok": True, "message": f"{sid} promoted to {stage}"}


@router.post("/strategies/{sid}/demote")
async def demote(sid: str, request: Request, user: str = Depends(current_user)):
    """Back to PAPER. Not biometric-gated: it only reduces risk."""
    s = st(request)
    if sid not in s.autotrader.store.cfg:
        raise HTTPException(404, "unknown strategy")
    s.autotrader.store.demote(sid)
    await s.audit.record("strategy.demoted", actor=user, strategy=sid)
    return {"ok": True, "message": f"{sid} back to PAPER"}


# ------------------------------------------------------------------ auto-trade switch
def _auto_view(s) -> dict:
    at = s.autotrader
    live = [x["id"] for x in at.strategies_view() if x["stage"] != "PAPER" and x["enabled"]]
    return {"enabled": at.enabled, "mode": s.gateway.mode.value, "killSwitch": s.gateway.kill_switch,
            "liveStrategies": live if s.gateway.mode is Mode.LIVE else [],
            "openTrades": [t.view() for t in at.open_trades()]}


@router.get("/autotrade")
async def get_autotrade(request: Request, _: str = Depends(current_user)):
    return _auto_view(st(request))


@router.post("/autotrade/on")
async def autotrade_on(request: Request, user: str = Depends(signed_user)):
    s = st(request)
    if s.gateway.kill_switch:
        return {"ok": False, "message": "Release the kill switch first"}
    s.autotrader.store.set_autotrade(True)
    await s.audit.record("autotrade.on", actor=user, mode=s.gateway.mode)
    v = _auto_view(s)
    where = f"LIVE for {', '.join(v['liveStrategies'])}; PAPER for the rest" if v["liveStrategies"] else "PAPER only"
    return {"ok": True, "message": f"Auto-trade ON — {where}"}


@router.post("/autotrade/off")
async def autotrade_off(request: Request, user: str = Depends(current_user)):
    """Instant, no biometric. Stops new entries; open trades keep their stops and are managed to exit."""
    s = st(request)
    s.autotrader.store.set_autotrade(False)
    await s.audit.record("autotrade.off", actor=user)
    return {"ok": True, "message": "Auto-trade OFF — no new entries; open trades still protected"}


@router.get("/analyst/{symbol}")
async def analyst(symbol: str, _: str = Depends(current_user)):
    raise HTTPException(503, "AI analyst is not configured on the engine yet (Phase 5)")


@router.post("/backtests")
async def backtests(_: str = Depends(current_user)):
    raise HTTPException(503, "Backtester is not deployed on the engine yet (Phase 2)")


# ------------------------------------------------------------------ controls
class KillBody(BaseModel):
    engaged: bool
    reason: str = ""


@router.post("/kill-switch")
async def kill_switch(body: KillBody, request: Request, user: str = Depends(current_user)):
    gw = st(request).gateway
    if body.engaged:
        r = await gw.engage_kill_switch(user, body.reason)
        return {"ok": True, "message": f"Kill switch ENGAGED — cancelled {len(r['cancelled'])} order(s)"}
    await gw.release_kill_switch(user)
    return {"ok": True, "message": "Kill switch released"}


class ModeBody(BaseModel):
    mode: Mode
    confirmPhrase: str = ""


@router.post("/mode")
async def set_mode(body: ModeBody, request: Request, user: str = Depends(signed_user)):
    try:
        m = await st(request).gateway.set_mode(body.mode, confirm_phrase=body.confirmPhrase, actor=user)
    except OrderBlocked as e:
        return {"ok": False, "message": e.reason}
    return {"ok": True, "message": f"Mode: {m.value}"}


@router.put("/risk")
async def put_risk(request: Request, user: str = Depends(signed_user)):
    s = st(request)
    try:
        limits = RiskLimits.model_validate_json(await request.body())
    except ValidationError as e:
        return {"ok": False, "message": "; ".join(f"{x['loc'][0]}: {x['msg']}" for x in e.errors())}
    s.risk.update(limits)
    await s.audit.record("risk.update", actor=user, **limits.model_dump())
    return {"ok": True, "message": "Risk limits saved"}


class ExitBody(BaseModel):
    symbol: str
    exchange: str
    product: str


def _round_tick(px: Decimal, tick: Decimal) -> Decimal:
    return (px / tick).quantize(Decimal("1"), rounding=ROUND_HALF_UP) * tick


@router.post("/positions/exit")
async def exit_position(body: ExitBody, request: Request, user: str = Depends(signed_user)):
    s = st(request)
    pos = next((p for p in await s.portfolio.positions() if p.contract.symbol == body.symbol
                and p.contract.exchange.value == body.exchange and p.product.value == body.product and p.net_qty), None)
    if pos is None:
        return {"ok": False, "message": f"No open position in {body.symbol}"}
    # merge watchlist ids (broker position rows may lack tick size / ids)
    known = s.watchlist.find(pos.contract.symbol, pos.contract.exchange.value)
    c = pos.contract.model_copy(update={"tick_size": known.tick_size, "ids": {**known.ids, **pos.contract.ids}}) \
        if known else pos.contract
    side = Side.SELL if pos.net_qty > 0 else Side.BUY
    qty = abs(pos.net_qty)
    ltp = pos.ltp or await s.gateway.broker.ltp(c)
    if s.gateway.mode is Mode.LIVE:
        # Marketable LIMIT with a protection band instead of a raw MARKET order.
        band = Decimal(str(s.settings.live_market_protection_pct)) / 100
        px = _round_tick(ltp * (1 - band if side is Side.SELL else 1 + band), c.tick_size)
        req = OrderRequest(contract=c, side=side, quantity=qty, order_type=OrderType.LIMIT, price=px,
                           product=pos.product, strategy="manual_exit")
    else:
        req = OrderRequest(contract=c, side=side, quantity=qty, order_type=OrderType.MARKET, product=pos.product,
                           strategy="manual_exit")
    try:
        o = await s.gateway.place(req, ltp, exit_only=True)
    except (OrderBlocked, BrokerError) as e:
        return {"ok": False, "message": getattr(e, "reason", str(e))}
    await s.audit.record("position.exit_requested", actor=user, symbol=c.key, qty=qty)
    return {"ok": True, "message": f"Exit {side.value} {qty} {c.symbol}: {o.status.value}"}


# ------------------------------------------------------------------ watchlist & search
@router.get("/watchlist")
async def get_watchlist(request: Request, _: str = Depends(current_user)):
    s = st(request)
    out = []
    for c in s.watchlist.all():
        t = s.hub.latest.get(c.key)
        out.append({"contract": c.model_dump(mode="json"), "ltp": float(t.ltp) if t else None,
                    "ts": int(t.ts.timestamp() * 1000) if t else None,
                    "source": t.source if t else s.hub.assignment.get(c.key)})
    return out


@router.post("/watchlist")
async def add_watch(request: Request, _: str = Depends(current_user)):
    s = st(request)
    try:
        c = Contract.model_validate_json(await request.body())
    except ValidationError as e:
        raise HTTPException(422, str(e)) from e
    s.watchlist.add(c); s.hub.add(c)
    return {"ok": True, "message": f"Added {c.symbol}"}


@router.delete("/watchlist/{exchange}/{symbol}")
async def del_watch(exchange: str, symbol: str, request: Request, _: str = Depends(current_user)):
    s = st(request)
    key = f"{exchange}:{symbol}"
    if not s.watchlist.remove(key):
        raise HTTPException(404, "not in watchlist")
    s.hub.remove(key)
    return {"ok": True, "message": f"Removed {symbol}"}


@router.get("/instruments/search")
async def search(request: Request, q: str = Query(min_length=2, max_length=30), exchange: str = "NSE",
                 _: str = Depends(current_user)):
    s = st(request)
    return await s.instruments.search(q, exchange)


@router.get("/feed/status")
async def feed_status(request: Request, _: str = Depends(current_user)):
    return st(request).hub.status()


# ------------------------------------------------------------------ live stream
@router.websocket("/stream")
async def stream(ws: WebSocket):
    """Ticks + a status heartbeat every 3 s. Auth: `Authorization: Bearer <jwt>` header."""
    s = ws.app.state
    auth = ws.headers.get("authorization", "")
    try:
        s.security.decode(auth.removeprefix("Bearer ").strip())
    except HTTPException:
        await ws.close(code=4401)
        return
    await ws.accept()
    q = s.hub.subscribe()
    try:
        for t in list(s.hub.latest.values()):
            await ws.send_json(t.to_json())
        last_status = 0.0
        while True:
            if time.monotonic() - last_status > 3:
                await ws.send_json({"type": "status", "mode": s.gateway.mode.value,
                                    "killSwitch": s.gateway.kill_switch, "feed": s.hub.status(),
                                    "activeBroker": s.brokers.active_id})
                last_status = time.monotonic()
            try:
                msg = await asyncio.wait_for(q.get(), 1.0)
                await ws.send_json(msg)
            except asyncio.TimeoutError:
                pass
    except (WebSocketDisconnect, RuntimeError):
        pass
    finally:
        s.hub.unsubscribe(q)


# ------------------------------------------------------------------ API keys (managed from the app)
class KeyBody(BaseModel):
    name: str
    scopes: list[str]


@router.get("/apikeys")
async def list_keys(request: Request, _: str = Depends(current_user)):
    return st(request).apikeys.list()


@router.post("/apikeys")
async def create_key(body: KeyBody, request: Request, user: str = Depends(signed_user)):
    """Biometric-signed. The raw key is returned ONCE and never stored."""
    s = st(request)
    try:
        k, raw = s.apikeys.create(body.name, body.scopes)
    except ValueError as e:
        return {"ok": False, "message": str(e)}
    await s.audit.record("apikey.created", actor=user, key=k.id, name=k.name, scopes=k.scopes)
    return {"ok": True, "message": "Copy this key now — it will not be shown again", "key": raw, "info": k.view()}


@router.delete("/apikeys/{key_id}")
async def revoke_key(key_id: str, request: Request, user: str = Depends(current_user)):
    s = st(request)
    if not s.apikeys.revoke(key_id):
        raise HTTPException(404, "no such key")
    await s.audit.record("apikey.revoked", actor=user, key=key_id)
    return {"ok": True, "message": "Key revoked"}


# ------------------------------------------------------------------ live-order approvals
@router.get("/intents")
async def list_intents(request: Request, _: str = Depends(current_user)):
    return [i.view() for i in st(request).intents.recent()]


@router.post("/intents/{intent_id}/approve")
async def approve_intent(intent_id: str, request: Request, user: str = Depends(signed_user)):
    """Biometric-signed approval: the only way an API/MCP-requested order reaches a real broker."""
    s = st(request)
    i = s.intents.get(intent_id)
    if i is None or i.status != "PENDING":
        return {"ok": False, "message": f"request is {i.status if i else 'unknown'}"}
    if s.gateway.mode is not Mode.LIVE:
        i.status, i.result = "FAILED", "engine is no longer LIVE"
        return {"ok": False, "message": i.result}
    r = await submit(s, Ticket(**i.ticket), f"{user} approving {i.requested_by}", "intent")
    i.status, i.result, i.order_id = ("APPROVED" if r["ok"] else "FAILED"), r["message"], r.get("orderId")
    await s.audit.record("intent.approved" if r["ok"] else "intent.failed", intent=i.id, actor=user, result=r["message"])
    return {"ok": r["ok"], "message": r["message"]}


@router.post("/intents/{intent_id}/reject")
async def reject_intent(intent_id: str, request: Request, user: str = Depends(current_user)):
    s = st(request)
    i = s.intents.get(intent_id)
    if i is None or i.status != "PENDING":
        return {"ok": False, "message": "nothing to reject"}
    i.status, i.result = "REJECTED", f"rejected by {user}"
    await s.audit.record("intent.rejected", intent=i.id, actor=user)
    return {"ok": True, "message": "Rejected"}
