from decimal import Decimal as D

import pytest

from app.core.enums import OrderStatus, OrderType, Product, Side
from app.core.models import OrderRequest


def req(c, side, qty, ot=OrderType.MARKET, **kw):
    return OrderRequest(contract=c, side=side, quantity=qty, order_type=ot, **kw)


async def test_market_fill_with_adverse_slippage(paper, sbin):
    await paper.on_tick(sbin, D("800"))
    o = await paper.place_order(req(sbin, Side.BUY, 10))
    assert o.status is OrderStatus.FILLED
    assert o.avg_fill_price == D("800.80")      # +10bps
    assert o.charges > 0
    o2 = await paper.place_order(req(sbin, Side.SELL, 10))
    assert o2.avg_fill_price == D("799.20")


async def test_rejects_without_price(paper, sbin):
    o = await paper.place_order(req(sbin, Side.BUY, 1))
    assert o.status is OrderStatus.REJECTED


async def test_resting_limit_fills_on_cross(paper, sbin):
    await paper.on_tick(sbin, D("800"))
    o = await paper.place_order(req(sbin, Side.BUY, 5, OrderType.LIMIT, price=D("795")))
    assert o.status is OrderStatus.PENDING
    await paper.on_tick(sbin, D("797"))
    assert o.status is OrderStatus.PENDING
    fills = await paper.on_tick(sbin, D("794"))
    assert o.status is OrderStatus.FILLED and o.avg_fill_price == D("794")
    assert len(fills) == 1


async def test_marketable_limit_fills_at_better_price(paper, sbin):
    await paper.on_tick(sbin, D("800"))
    o = await paper.place_order(req(sbin, Side.BUY, 1, OrderType.LIMIT, price=D("805")))
    assert o.avg_fill_price == D("800")


async def test_sell_stop_market_triggers(paper, sbin):
    await paper.on_tick(sbin, D("800"))
    await paper.place_order(req(sbin, Side.BUY, 10))
    sl = await paper.place_order(req(sbin, Side.SELL, 10, OrderType.SL_MARKET, trigger_price=D("790")))
    assert sl.status is OrderStatus.PENDING
    await paper.on_tick(sbin, D("789"))
    assert sl.status is OrderStatus.FILLED
    pos = (await paper.positions())[0]
    assert pos.net_qty == 0 and pos.realized_pnl < 0


async def test_pnl_accounting_and_funds(paper, sbin):
    paper.slippage_bps = D("0")
    await paper.on_tick(sbin, D("100"))
    await paper.place_order(req(sbin, Side.BUY, 100))
    await paper.on_tick(sbin, D("110"))
    f = await paper.funds()
    assert f.unrealized_pnl == D("1000.00")
    await paper.place_order(req(sbin, Side.SELL, 100))
    pos = (await paper.positions())[0]
    assert pos.realized_pnl == D("1000")
    f = await paper.funds()
    assert f.used_margin == 0
    assert f.available_cash == D("100000") + D("1000") - pos.charges


async def test_position_flip(paper, sbin):
    paper.slippage_bps = D("0")
    await paper.on_tick(sbin, D("100"))
    await paper.place_order(req(sbin, Side.BUY, 10))
    await paper.on_tick(sbin, D("105"))
    await paper.place_order(req(sbin, Side.SELL, 15))
    pos = (await paper.positions())[0]
    assert pos.net_qty == -5 and pos.avg_price == D("105") and pos.realized_pnl == D("50")


async def test_no_short_delivery(paper, sbin):
    await paper.on_tick(sbin, D("100"))
    o = await paper.place_order(req(sbin, Side.SELL, 1, product=Product.DELIVERY))
    assert o.status is OrderStatus.REJECTED and "short" in o.message


async def test_insufficient_funds(paper, sbin):
    await paper.on_tick(sbin, D("1000"))
    o = await paper.place_order(req(sbin, Side.BUY, 200, product=Product.DELIVERY))  # 2L > 1L
    assert o.status is OrderStatus.REJECTED and "insufficient" in o.message
    o = await paper.place_order(req(sbin, Side.BUY, 200, product=Product.INTRADAY))  # 20% margin = 40k
    assert o.status is OrderStatus.FILLED


async def test_lot_size_enforced(nifty_fut):
    with pytest.raises(ValueError):
        OrderRequest(contract=nifty_fut, side=Side.BUY, quantity=50, order_type=OrderType.MARKET)


async def test_cancel_and_modify(paper, sbin):
    await paper.on_tick(sbin, D("800"))
    o = await paper.place_order(req(sbin, Side.BUY, 1, OrderType.LIMIT, price=D("700")))
    await paper.modify_order(o.order_id, price=D("801"))
    assert o.status is OrderStatus.FILLED
    o2 = await paper.place_order(req(sbin, Side.BUY, 1, OrderType.LIMIT, price=D("700")))
    await paper.cancel_order(o2.order_id)
    assert o2.status is OrderStatus.CANCELLED
