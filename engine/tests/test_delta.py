import hashlib
import hmac
import json
from datetime import datetime, timezone
from decimal import Decimal as D

import httpx
import pytest
import respx

from app.broker.adapters.delta import REGIONS, DeltaBroker, sign
from app.broker.costs import compute_costs
from app.broker.paper import PaperBroker
from app.broker.tokens import TokenStore
from app.core.enums import Exchange, Instrument, OrderStatus, OrderType, Product, Side
from app.core.errors import AuthError, BrokerError
from app.core.models import Contract, OrderRequest

BASE = REGIONS["india"]
BTC = Contract(symbol="BTCUSD", token="27", exchange=Exchange.DELTA, instrument=Instrument.FUTURE,
               multiplier=D("0.001"), tick_size=D("0.5"))
NOW = 1_790_000_000


@pytest.fixture
def delta(tmp_path):
    return DeltaBroker(TokenStore(tmp_path / "t.json"), "KEY", "SECRET", "india", clock=lambda: NOW)


def expect_sig(req: httpx.Request) -> str:
    q = req.url.query.decode()
    msg = req.method + str(NOW) + req.url.path + (("?" + q) if q else "") + req.content.decode()
    return hmac.new(b"SECRET", msg.encode(), hashlib.sha256).hexdigest()


def ok(result):
    return httpx.Response(200, json={"success": True, "result": result})


def test_sign_matches_reference():
    # signature_data = method + timestamp + path + query_string + payload
    assert sign("s", "GET", "1", "/v2/orders", "states=open", "") == \
        hmac.new(b"s", b"GET1/v2/orders?states=open", hashlib.sha256).hexdigest()


async def test_connect_verifies_keys_and_signs(delta):
    with respx.mock(base_url=BASE) as m:
        prof = m.get("/v2/profile").mock(return_value=ok({"id": 98765, "email": "x@y"}))
        tok = await delta.complete_login({})
        r = prof.calls.last.request
        assert r.headers["api-key"] == "KEY" and r.headers["timestamp"] == str(NOW)
        assert r.headers["signature"] == expect_sig(r)
        assert tok.account == "98765" and (await delta.status()).connected


async def test_ip_not_whitelisted_is_clear_auth_error(delta):
    with respx.mock(base_url=BASE) as m:
        m.get("/v2/profile").mock(return_value=httpx.Response(401, json={
            "success": False, "error": {"code": "ip_not_whitelisted_for_api_key"}}))
        with pytest.raises(AuthError, match="whitelisted"):
            await delta.complete_login({})
    assert not (await delta.status()).connected


async def test_orders_require_connection(delta):
    with pytest.raises(AuthError):
        await delta.orders()


async def test_place_stop_limit_cancel(delta):
    with respx.mock(base_url=BASE) as m:
        m.get("/v2/profile").mock(return_value=ok({"id": 1}))
        await delta.complete_login({})
        place = m.post("/v2/orders").mock(return_value=ok({
            "id": 555, "product_id": 27, "product_symbol": "BTCUSD", "size": 10, "unfilled_size": 10,
            "side": "buy", "order_type": "limit_order", "limit_price": "65000", "state": "pending"}))
        req = OrderRequest(contract=BTC, side=Side.BUY, quantity=10, order_type=OrderType.SL_LIMIT,
                           price=D("65000"), trigger_price=D("64900"), product=Product.CARRYFORWARD)
        o = await delta.place_order(req)
        r = place.calls.last.request
        b = json.loads(r.content)
        assert r.headers["signature"] == expect_sig(r)
        assert b["product_symbol"] == "BTCUSD" and b["size"] == 10 and b["side"] == "buy"
        assert b["order_type"] == "limit_order" and b["limit_price"] == "65000"
        assert b["stop_order_type"] == "stop_loss_order" and b["stop_price"] == "64900"
        assert o.order_id == "555" and o.status is OrderStatus.PENDING

        cancel = m.delete("/v2/orders").mock(return_value=ok({
            "id": 555, "product_id": 27, "product_symbol": "BTCUSD", "size": 10, "unfilled_size": 10,
            "side": "buy", "order_type": "limit_order", "limit_price": "65000", "state": "cancelled"}))
        c = await delta.cancel_order("555")
        assert json.loads(cancel.calls.last.request.content) == {"id": 555, "product_id": 27}
        assert c.status is OrderStatus.CANCELLED

        m.post("/v2/orders").mock(return_value=httpx.Response(400, json={
            "success": False, "error": {"code": "insufficient_margin", "context": {"required": "12.5"}}}))
        with pytest.raises(BrokerError, match="insufficient_margin") as e:
            await delta.place_order(req)
        assert e.value.errorcode == "insufficient_margin"


async def test_rejects_non_delta_contract(delta):
    with respx.mock(base_url=BASE) as m:
        m.get("/v2/profile").mock(return_value=ok({"id": 1}))
        await delta.complete_login({})
    sbin = Contract(symbol="SBIN-EQ", token="3045", exchange=Exchange.NSE)
    with pytest.raises(BrokerError, match="not a Delta"):
        await delta.place_order(OrderRequest(contract=sbin, side=Side.BUY, quantity=1, order_type=OrderType.MARKET))


async def test_positions_funds_ltp_and_filled_state(delta):
    with respx.mock(base_url=BASE) as m:
        m.get("/v2/profile").mock(return_value=ok({"id": 1}))
        await delta.complete_login({})
        m.get("/v2/positions/margined").mock(return_value=ok([{
            "product_id": 27, "product_symbol": "BTCUSD", "size": -20, "entry_price": "65000", "mark_price": "64000",
            "realized_pnl": "0", "product": {"contract_value": "0.001", "contract_type": "perpetual_futures"}}]))
        [p] = await delta.positions()
        assert p.net_qty == -20 and p.contract.multiplier == D("0.001")
        assert p.unrealized_pnl == D("20")            # (64000-65000) * -20 * 0.001
        m.get("/v2/wallet/balances").mock(return_value=ok([
            {"asset_symbol": "BTC", "available_balance": "0.1"},
            {"asset_symbol": "USD", "available_balance": "900.5", "order_margin": "50", "position_margin": "49.5"}]))
        f = await delta.funds()
        assert f.available_cash == D("900.5") and f.used_margin == D("99.5")
        tick = m.get("/v2/tickers/BTCUSD").mock(return_value=ok({"close": 64010, "mark_price": "64005.5"}))
        assert await delta.ltp(BTC) == D("64005.5")
        assert "signature" not in tick.calls.last.request.headers   # public endpoint, unsigned
        orders = m.get("/v2/orders").mock(return_value=ok([{
            "id": 9, "product_id": 27, "product_symbol": "BTCUSD", "size": 5, "unfilled_size": 0, "side": "sell",
            "order_type": "market_order", "state": "closed", "average_fill_price": "64001"}]))
        [o] = await delta.orders()
        assert o.status is OrderStatus.FILLED and o.filled_qty == 5
        assert orders.calls.last.request.headers["signature"] == expect_sig(orders.calls.last.request)


def test_crypto_fees_use_multiplier_and_gst():
    c = compute_costs(Side.BUY, 100, D("60000"), Instrument.FUTURE, Product.CARRYFORWARD,
                      exchange=Exchange.DELTA, multiplier=D("0.001"))
    assert c.turnover == D("6000.00")      # 100 contracts * 0.001 BTC * 60000
    assert c.brokerage == D("3.00")        # 0.05% taker
    assert c.gst == D("0.54") and c.stt == 0 and c.stamp == 0
    assert c.total == D("3.54")


async def test_paper_broker_handles_fractional_contracts():
    pb = PaperBroker(starting_capital=D("100000"), slippage_bps=D("0"),
                     clock=lambda: datetime(2026, 11, 27, tzinfo=timezone.utc))
    await pb.on_tick(BTC, D("60000"))
    await pb.place_order(OrderRequest(contract=BTC, side=Side.BUY, quantity=100, order_type=OrderType.MARKET,
                                      product=Product.CARRYFORWARD))
    await pb.on_tick(BTC, D("61000"))
    [p] = await pb.positions()
    assert p.unrealized_pnl == D("100.000")     # 1000 * 100 * 0.001
    await pb.place_order(OrderRequest(contract=BTC, side=Side.SELL, quantity=100, order_type=OrderType.MARKET,
                                      product=Product.CARRYFORWARD))
    [p] = await pb.positions()
    assert p.realized_pnl == D("100.000") and p.net_qty == 0
