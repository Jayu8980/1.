from tradelens import charges


def test_delivery_buy_matches_app():
    c = charges.leg("EQ_DELIVERY", "BUY", 1000, 100)
    assert c.stt == 100 and c.stamp == 15 and abs(c.exchange - 2.97) < 1e-6 and abs(c.sebi - 0.1) < 1e-9
    assert abs(c.gst - 0.18 * (2.97 + 0.1)) < 1e-6


def test_intraday_brokerage_cap_and_sell_stt():
    assert charges.leg("EQ_INTRADAY", "BUY", 1000, 1000).brokerage == 20
    assert charges.leg("EQ_INTRADAY", "SELL", 1000, 1000).stt == 250


def test_options_and_delta():
    assert charges.leg("OPTIONS", "BUY", 100, 75).stt == 0
    assert charges.leg("OPTIONS", "SELL", 100, 75).stt == 8
    d = charges.leg("CRYPTO_FUT", "BUY", 100000, 0.01)
    assert abs(d.total - 0.59) < 1e-9
    assert charges.leg("CRYPTO_OPT", "BUY", 100, 1, underlying_price=100000).trading_fee == 10


def test_slippage_grows_with_participation():
    a = charges.slippage_bps(1e9, 1e6); b = charges.slippage_bps(1e9, 1e8)
    assert b > a > 3 and charges.slippage_bps(0, 1) == 50
