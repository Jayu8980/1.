from decimal import Decimal as D

from app.broker.costs import compute_costs, round_trip_cost
from app.core.enums import Instrument, Product, Side


def test_intraday_buy_and_sell():
    b = compute_costs(Side.BUY, 100, D("800"), Instrument.EQUITY, Product.INTRADAY)
    assert b.turnover == D("80000.00")
    assert b.brokerage == D("20.00")          # min(0.03% = 24, 20)
    assert b.stt == D("0")                     # intraday STT only on sell
    assert b.stamp == D("2.40")                # 0.003%
    assert b.exchange == D("2.38")
    s = compute_costs(Side.SELL, 100, D("800"), Instrument.EQUITY, Product.INTRADAY)
    assert s.stt == D("20")                    # 0.025% of 80k
    assert s.stamp == D("0")
    assert s.gst == ((s.brokerage + s.exchange + s.sebi) * D("0.18")).quantize(D("0.01"))


def test_small_intraday_uses_pct_brokerage():
    b = compute_costs(Side.BUY, 10, D("100"), Instrument.EQUITY, Product.INTRADAY)
    assert b.brokerage == D("0.30")


def test_delivery_stt_both_sides():
    b = compute_costs(Side.BUY, 10, D("1000"), Instrument.EQUITY, Product.DELIVERY)
    s = compute_costs(Side.SELL, 10, D("1000"), Instrument.EQUITY, Product.DELIVERY)
    assert b.stt == s.stt == D("10")
    assert b.stamp == D("1.50")


def test_options_flat_brokerage_and_premium_stt():
    s = compute_costs(Side.SELL, 75, D("200"), Instrument.OPTION, Product.CARRYFORWARD)
    assert s.brokerage == D("20")
    assert s.stt == D("15")                    # 0.1% of 15,000 premium


def test_round_trip_includes_slippage():
    no_slip = round_trip_cost(100, D("500"), D("500"), Instrument.EQUITY, Product.INTRADAY)
    slip = round_trip_cost(100, D("500"), D("500"), Instrument.EQUITY, Product.INTRADAY, slippage_bps=D("5"))
    assert slip - no_slip == D("50.00")        # 5bps of 100k two-way turnover
