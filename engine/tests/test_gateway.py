from decimal import Decimal as D

import pytest

from app.core.enums import Mode, OrderStatus, OrderType, Side
from app.core.errors import OrderBlocked
from app.core.ipguard import StaticIPGuard
from app.core.models import OrderRequest
from app.execution.gateway import ExecutionGateway
from app.execution.risk_hook import HardLimits

PHRASE = "I UNDERSTAND THIS USES REAL MONEY"


class FakeLive:
    name = "fake-live"

    def __init__(self):
        self.placed = []

    async def place_order(self, req):
        self.placed.append(req)
        raise AssertionError("should not be reached in these tests unless intended")

    async def orders(self):
        return []

    async def ltp(self, c):
        return D("800")


def ip_guard(ip="1.2.3.4"):
    async def f():
        return ip
    return StaticIPGuard("1.2.3.4", f, ttl_s=0)


def mk(paper, audit, **kw):
    return ExecutionGateway(paper, HardLimits(D("50000")), audit, live_confirm_phrase=PHRASE, **kw)


def buy(c, qty=10):
    return OrderRequest(contract=c, side=Side.BUY, quantity=qty, order_type=OrderType.MARKET)


async def test_default_is_paper_and_fills(paper, audit, sbin):
    gw = mk(paper, audit)
    assert gw.mode is Mode.PAPER
    await paper.on_tick(sbin, D("800"))
    o = await gw.place(buy(sbin))
    assert o.status is OrderStatus.FILLED
    events = [r["event"] for r in audit.tail()]
    assert events[-2:] == ["order.submit", "order.ack"]


async def test_risk_veto_blocks_and_is_audited(paper, audit, sbin):
    gw = mk(paper, audit)
    await paper.on_tick(sbin, D("800"))
    with pytest.raises(OrderBlocked) as e:
        await gw.place(buy(sbin, 100))   # 80k > 50k
    assert e.value.code == "RISK_VETO"
    assert audit.tail()[-1]["event"] == "order.blocked"
    assert await paper.orders() == []


async def test_kill_switch_blocks_and_cancels(paper, audit, sbin):
    gw = mk(paper, audit)
    await paper.on_tick(sbin, D("800"))
    resting = await gw.place(OrderRequest(contract=sbin, side=Side.BUY, quantity=1,
                                          order_type=OrderType.LIMIT, price=D("700")))
    res = await gw.engage_kill_switch("tester", "test")
    assert resting.order_id in res["cancelled"] and resting.status is OrderStatus.CANCELLED
    with pytest.raises(OrderBlocked) as e:
        await gw.place(buy(sbin))
    assert e.value.code == "KILL_SWITCH"
    await gw.release_kill_switch("tester")
    assert (await gw.place(buy(sbin))).status is OrderStatus.FILLED


@pytest.mark.parametrize("allow,phrase,live,code_part", [
    (False, PHRASE, True, "ALLOW_LIVE"),
    (True, "wrong", True, "phrase"),
    (True, PHRASE, False, "not configured"),
])
async def test_live_requires_all_locks(paper, audit, allow, phrase, live, code_part):
    gw = mk(paper, audit, allow_live=allow, live=FakeLive() if live else None,
            ip_guard=ip_guard() if live else None)
    with pytest.raises(OrderBlocked) as e:
        await gw.set_mode(Mode.LIVE, confirm_phrase=phrase)
    assert code_part in e.value.reason
    assert gw.mode is Mode.PAPER


async def test_live_blocked_on_ip_mismatch_at_order_time(paper, audit, sbin):
    ips = iter(["1.2.3.4", "5.6.7.8"])

    async def f():
        return next(ips)
    live = FakeLive()
    gw = mk(paper, audit, allow_live=True, live=live, ip_guard=StaticIPGuard("1.2.3.4", f, ttl_s=0))
    await gw.set_mode(Mode.LIVE, confirm_phrase=PHRASE)
    with pytest.raises(OrderBlocked) as e:
        await gw.place(buy(sbin))
    assert e.value.code == "IP_MISMATCH"
    assert live.placed == []
    assert audit.verify() == (True, None)


async def test_kill_switch_drops_to_paper(paper, audit):
    gw = mk(paper, audit, allow_live=True, live=FakeLive(), ip_guard=ip_guard())
    await gw.set_mode(Mode.LIVE, confirm_phrase=PHRASE)
    await gw.engage_kill_switch("tester")
    assert gw.mode is Mode.PAPER
    with pytest.raises(OrderBlocked):
        await gw.set_mode(Mode.LIVE, confirm_phrase=PHRASE)   # can't re-arm while engaged
