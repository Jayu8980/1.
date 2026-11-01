"""Network-facing code tested against fakes: Yahoo parsing, news, fundamentals, Alpaca, CCXT."""
import sys
import types
from datetime import date, timedelta

import numpy as np
import pandas as pd
import pytest

from trader.agents.fundamental import days_to_earnings, quality_score
from trader.agents.sentiment import fetch_headlines, lexicon_score
from trader.data import _extract


def _ohlcv(n=5, tz=None):
    index = pd.date_range("2026-01-05", periods=n, freq="B", tz=tz)
    base = np.arange(1, n + 1, dtype=float)
    return pd.DataFrame({"Open": base, "High": base + 1, "Low": base - 0.5, "Close": base + 0.5,
                         "Volume": base * 100}, index=index)


def test_yahoo_multiindex_both_orientations_and_timezones():
    tz = "America/New_York"
    by_ticker = pd.concat({"AAPL": _ohlcv(tz=tz), "SPY": _ohlcv(tz=tz)}, axis=1)
    df = _extract(by_ticker, "AAPL")
    assert list(df.columns) == ["Open", "High", "Low", "Close", "Volume"] and df.index.tz is None
    by_field = by_ticker.swaplevel(axis=1)
    assert _extract(by_field, "SPY")["Close"].iloc[-1] == 5.5
    assert _extract(by_ticker, "MSFT") is None


def _fake_yfinance(monkeypatch, ticker):
    module = types.SimpleNamespace(Ticker=lambda symbol: ticker)
    monkeypatch.setitem(sys.modules, "yfinance", module)


def test_news_parsing_handles_old_and_new_formats(monkeypatch):
    now = pd.Timestamp.now(tz="UTC")
    news = [
        {"content": {"title": "Company beats estimates, raises guidance", "summary": "Strong quarter",
                     "pubDate": now.isoformat()}},
        {"title": "Shares slump after downgrade", "providerPublishTime": int(now.timestamp())},
        {"content": {"title": "Old story", "pubDate": (now - pd.Timedelta(days=40)).isoformat()}},
    ]
    _fake_yfinance(monkeypatch, types.SimpleNamespace(news=news))
    headlines = fetch_headlines("ABC", max_items=10, max_age_days=10)
    assert [h["title"] for h in headlines] == ["Company beats estimates, raises guidance",
                                               "Shares slump after downgrade"]
    score, confidence = lexicon_score(headlines)
    assert -1 <= score <= 1 and 0 < confidence <= 0.6


def test_fundamentals_quality_and_earnings_date():
    strong = {"profitMargins": 0.25, "revenueGrowth": 0.2, "returnOnEquity": 0.3, "debtToEquity": 40,
              "forwardPE": 20, "recommendationMean": 1.8}
    weak = {"profitMargins": -0.1, "revenueGrowth": -0.05, "returnOnEquity": -0.2, "debtToEquity": 400,
            "forwardPE": -5, "recommendationMean": 3.5}
    assert quality_score(strong)[0] > 0.8 > 0.2 > quality_score(weak)[0]
    assert quality_score({"profitMargins": "n/a"}) == (None, {})
    ticker = types.SimpleNamespace(calendar={"Earnings Date": [date.today() + timedelta(days=3)]})
    assert days_to_earnings(ticker) == 3
    assert days_to_earnings(types.SimpleNamespace(calendar={})) is None


def test_alpaca_sends_bracket_orders_for_stocks_and_plain_orders_for_crypto(monkeypatch, tmp_path):
    pytest.importorskip("requests")
    monkeypatch.setenv("APCA_API_KEY_ID", "key")
    monkeypatch.setenv("APCA_API_SECRET_KEY", "secret")
    from trader.brokers.alpaca import AlpacaBroker, from_alpaca, to_alpaca

    assert to_alpaca("BRK-B") == "BRK.B" and to_alpaca("BTC-USD") == "BTC/USD"
    assert from_alpaca("BTCUSD") == "BTC-USD" and from_alpaca("BRK.B") == "BRK-B"
    sent = []

    class Response:
        status_code = 200
        content = b"{}"

        def json(self):
            return {"id": "order-1", "status": "accepted"}

    def request(method, url, timeout, **kwargs):
        sent.append((method, url, kwargs.get("json")))
        return Response()

    broker = AlpacaBroker(paper=True, state_dir=str(tmp_path))
    broker.http.request = request
    plan = {"symbol": "AAPL", "side": "long", "qty": 10.0, "stop": 190.123, "target": 230.5, "max_hold": 10,
            "entry_ref": 200.0}
    assert broker.submit(plan)["status"] == "accepted"
    method, url, body = sent[-1]
    assert url == "https://paper-api.alpaca.markets/v2/orders" and not broker.live
    assert body["order_class"] == "bracket" and body["side"] == "buy" and body["qty"] == "10"
    assert body["stop_loss"] == {"stop_price": "190.12"} and body["take_profit"] == {"limit_price": "230.50"}
    broker.submit({**plan, "symbol": "BTC-USD", "qty": 0.015})
    body = sent[-1][2]
    assert body["symbol"] == "BTC/USD" and "order_class" not in body and body["time_in_force"] == "gtc"
    assert broker.book.all()["BTC-USD"]["software_exits"] is True


def test_ccxt_buys_spot_and_exits_on_stop(monkeypatch, tmp_path):
    monkeypatch.setenv("CCXT_API_KEY", "key")
    monkeypatch.setenv("CCXT_SECRET", "secret")
    orders, prices = [], {"BTC/USDT": 100.0}

    class FakeExchange:
        def __init__(self, params):
            self.sandbox = False

        def set_sandbox_mode(self, on):
            self.sandbox = on

        def load_markets(self):
            return {}

        def amount_to_precision(self, market, amount):
            return f"{amount:.4f}"

        def create_order(self, market, kind, side, amount):
            orders.append((market, kind, side, amount))
            return {"id": str(len(orders)), "status": "closed", "average": prices[market], "filled": amount}

        def fetch_ticker(self, market):
            return {"last": prices[market]}

        def fetch_balance(self):
            return {"free": {"USDT": 500.0}, "total": {"USDT": 500.0}}

    monkeypatch.setitem(sys.modules, "ccxt", types.SimpleNamespace(binance=FakeExchange))
    from trader.brokers.ccxt_broker import CCXTBroker

    broker = CCXTBroker("binance", sandbox=True, quote="USDT", state_dir=str(tmp_path))
    assert broker.ex.sandbox and not broker.live
    broker.submit({"symbol": "BTC-USD", "side": "long", "qty": 1.23456, "stop": 95.0, "target": 110.0,
                   "max_hold": 10, "entry_ref": 100.0})
    assert orders[-1] == ("BTC/USDT", "market", "buy", 1.2346)
    assert broker.account()["equity"] == pytest.approx(500 + 1.2346 * 100)
    prices["BTC/USDT"] = 94.0
    closed = broker.manage({})
    assert closed[0]["reason"] == "stop" and orders[-1][2] == "sell" and broker.positions() == []
