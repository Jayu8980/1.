"""Live quotes: broker price first, Yahoo as the fallback, cached and rate limited."""
from trader.quotes import QuoteService


class FakeBroker:
    name = "zerodha"

    def __init__(self):
        self.calls = []

    def underlying_ltp(self, symbol):  # marks it as an Indian broker adapter
        raise AssertionError("not used")

    def instrument(self, symbol):
        if symbol == "UNKNOWN.NS":
            raise ValueError("no such instrument")
        return symbol

    def ltp(self, inst):
        self.calls.append(inst)
        return 250.25

    def index_ltp(self, name):
        self.calls.append(name)
        return 24000.0


def yahoo(symbol, timeout=8.0):
    return {"price": 1.0, "prev_close": 0.5, "time": 1, "source": "yahoo"}


def test_broker_prices_first_then_yahoo_and_cache():
    b = FakeBroker()
    svc = QuoteService(b, ttl=60, min_gap=0, yahoo=yahoo)
    out = svc.quotes(["TCS.NS", "^NSEI", "UNKNOWN.NS"])
    assert out["TCS.NS"]["price"] == 250.25 and out["TCS.NS"]["source"] == "zerodha"
    assert out["TCS.NS"]["prev_close"] == 0.5
    assert out["^NSEI"]["price"] == 24000.0 and b.calls == ["TCS.NS", "NIFTY"]
    assert out["UNKNOWN.NS"]["source"] == "yahoo"
    svc.quotes(["TCS.NS"])
    assert b.calls == ["TCS.NS", "NIFTY"], "a fresh cached quote is not fetched again"


def test_no_broker_means_yahoo():
    out = QuoteService(None, yahoo=yahoo).quotes(["AAPL"])
    assert out["AAPL"]["source"] == "yahoo"
