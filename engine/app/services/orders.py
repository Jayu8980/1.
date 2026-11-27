"""One order-submission path shared by the app, the external API, MCP and approved intents."""
from __future__ import annotations

from decimal import ROUND_HALF_UP, Decimal

from pydantic import BaseModel, Field, ValidationError

from app.core.enums import Mode, OrderType, Product, Side
from app.core.errors import BrokerError, OrderBlocked
from app.core.models import OrderRequest


class Ticket(BaseModel):
    symbol: str = Field(min_length=1, max_length=40)
    exchange: str = Field(pattern="^(NSE|BSE|NFO|BFO|DELTA)$")
    side: Side
    qty: int = Field(gt=0, le=1_000_000)
    orderType: OrderType = OrderType.LIMIT
    price: float | None = Field(default=None, gt=0)
    triggerPrice: float | None = Field(default=None, gt=0)
    product: Product = Product.INTRADAY


def round_tick(px: Decimal, tick: Decimal) -> Decimal:
    return (px / tick).quantize(Decimal("1"), rounding=ROUND_HALF_UP) * tick


def build_request(s, t: Ticket) -> tuple[OrderRequest | None, Decimal | None, str | None]:
    """Returns (request, ref_price, error)."""
    c = s.watchlist.find(t.symbol, t.exchange)
    if c is None:
        return None, None, f"{t.exchange}:{t.symbol} is not in the watchlist — add it first (instrument ids)"
    tick = s.hub.latest.get(c.key)
    ltp = tick.ltp if tick else None
    ot, price = t.orderType, (Decimal(str(t.price)) if t.price is not None else None)
    if ot is OrderType.MARKET and s.gateway.mode is Mode.LIVE:
        if ltp is None:
            return None, None, "no live price yet — use a LIMIT order"
        band = Decimal(str(s.settings.live_market_protection_pct)) / 100
        price = round_tick(ltp * (1 + band if t.side is Side.BUY else 1 - band), c.tick_size)
        ot = OrderType.LIMIT
    try:
        req = OrderRequest(contract=c, side=t.side, quantity=t.qty, order_type=ot, price=price,
                           trigger_price=Decimal(str(t.triggerPrice)) if t.triggerPrice is not None else None,
                           product=t.product, strategy="manual")
    except (ValueError, ValidationError) as e:
        return None, None, str(e).splitlines()[-1]
    return req, price or ltp, None


async def submit(s, t: Ticket, actor: str, channel: str) -> dict:
    """Full gateway path (kill switch, risk veto, static IP when LIVE, rate limit, audit)."""
    req, ref, err = build_request(s, t)
    if err:
        return {"ok": False, "message": err}
    live = s.gateway.mode is Mode.LIVE
    try:
        o = await s.gateway.place(req, ref)
    except (OrderBlocked, BrokerError) as e:
        return {"ok": False, "message": getattr(e, "reason", None) or str(e)}
    await s.audit.record("order.manual", actor=actor, channel=channel, venue="live" if live else "paper",
                         order_id=o.order_id)
    where = f"REAL order at {s.brokers.active.info.name}" if live else "PAPER order"
    return {"ok": True, "orderId": o.order_id, "status": o.status.value, "venue": "live" if live else "paper",
            "message": f"{where}: {t.side.value} {t.qty} {t.symbol} — {o.status.value} (#{o.order_id})"}
