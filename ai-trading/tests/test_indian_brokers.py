"""Indian broker layer: the shared trade lifecycle for every segment, and each adapter's calls.

The fakes mirror the method signatures of the official SDKs (kiteconnect 5.2, smartapi-python
1.5, dhanhq 2.3, growwapi 1.5); they record what the adapters send.
"""
import json
import sys
import types
from datetime import date, datetime

import pytest

from trader.brokers import india
from trader.brokers.base import BrokerError
from trader.brokers.india import IST, IndianBroker, Instrument, split_symbol, to_tick, totp
from trader.config import Config


# ---- helpers ---------------------------------------------------------------------------------

def test_totp_matches_rfc6238_vectors():
    secret = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"  # base32 of the RFC's "12345678901234567890"
    assert [totp(secret, at=t) for t in (59, 1111111109, 1234567890, 2000000000)] == \
        ["287082", "081804", "005924", "279037"]


def test_price_grid_and_symbols():
    assert to_tick(101.23, 0.05, "up") == 101.25 and to_tick(101.23, 0, "down") == 101.2
    assert to_tick(1234.56) == 1234.6          # 0.10 grid between 1,000 and 5,000
    assert to_tick(25012.0) == 25010.0         # 5 rupee grid above 20,000
    assert split_symbol("RELIANCE.NS") == ("NSE", "RELIANCE") and split_symbol("tcs.bo") == ("BSE", "TCS")
    with pytest.raises(BrokerError):
        split_symbol("AAPL")
    with pytest.raises(BrokerError, match="index"):
        split_symbol("^NSEI")


def test_market_hours():
    monday = datetime(2026, 11, 2, 10, 0, tzinfo=IST)
    assert india.market_open(monday)
    assert not india.market_open(monday.replace(hour=16))
    assert not india.market_open(monday.replace(day=7))  # Saturday


# ---- the shared lifecycle against an in-memory exchange --------------------------------------

class FakeExchange(IndianBroker):
    name = "fake"

    def __init__(self, ex, live=True, amo=True, ic=None):
        super().__init__(ex, live, ic)
        self.supports_amo = amo
        self.orders, self.exits, self.hold, self.prices, self.calls = {}, {}, {}, {}, []
        self.pos, self.index_prices, self.rows = {}, {}, {}
        self.sold_at = None
        self._n = 0

    def _id(self):
        self._n += 1
        return str(self._n)

    def connect(self):
        return object()

    def instrument(self, symbol):
        exchange, base = split_symbol(symbol)
        return Instrument(symbol, exchange, base, "1", 0.05)

    def funds(self):
        return 100_000.0

    def holdings(self):
        return {b: {"qty": q, "avg": None, "ltp": None} for b, q in self.hold.items()}

    def ltp(self, inst):
        key = inst.symbol if inst.kind == "equity" else inst.tradingsymbol
        if key not in self.prices:
            raise BrokerError("no price")
        return self.prices[key]

    def index_ltp(self, name):
        if name not in self.index_prices:
            raise BrokerError("no index price")
        return self.index_prices[name]

    def contracts(self, name, exchange):
        return self.rows.get((name, exchange), [])

    def net_positions(self):
        return [{"exchange": e, "tradingsymbol": t, "token": "", "product": p, "qty": q}
                for (e, t, p), q in self.pos.items()]

    def place_order(self, inst, side, qty, price, product, trigger=None, amo=False):
        oid = self._id()
        self.orders[oid] = {"qty": qty, "status": "open", "filled": 0, "avg": 0, "side": side, "inst": inst,
                            "product": product}
        self.calls.append(("order", side, qty, price, product, trigger, amo))
        return oid

    def cancel_order(self, oid, inst=None):
        if self.orders.get(oid, {}).get("status") == "open":
            self.orders[oid]["status"] = "cancelled"
        self.calls.append(("cancel", oid))

    def order_state(self, oid, inst=None):
        o = self.orders.get(oid)
        return None if o is None else {"status": o["status"], "filled": o["filled"], "avg": o["avg"], "message": ""}

    def place_exits(self, inst, qty, stop, target, ltp):
        ids = [self._id(), self._id()]
        self.exits.update({ids[0]: "active", ids[1]: "active"})
        self.calls.append(("exits", qty, stop, target))
        return ids

    def exit_status(self, inst, ids):
        states = [self.exits.get(i, "gone") for i in ids]
        if "triggered" in states:
            return "triggered"
        return "active" if states and all(s == "active" for s in states) else "inactive"

    def cancel_exits(self, inst, ids):
        for i in ids:
            if self.exits.get(i) == "active":
                self.exits[i] = "cancelled"
        self.calls.append(("cancel_exits", tuple(ids)))

    def last_sell_price(self, inst):
        return self.sold_at

    def fill(self, oid, avg):
        o = self.orders[oid]
        o.update(status="complete", filled=o["qty"], avg=avg)
        if o["product"] != "CNC":  # session products show up in positions
            key = (o["inst"].exchange, o["inst"].tradingsymbol, o["product"])
            self.pos[key] = self.pos.get(key, 0) + (o["qty"] if o["side"] == "BUY" else -o["qty"])


@pytest.fixture
def clock(monkeypatch):
    real_market_open = india.market_open

    class Clock:
        now = datetime(2026, 11, 2, 16, 30, tzinfo=IST)  # Monday, after the close

        def set(self, *args):
            self.now = datetime(*args, tzinfo=IST)

    c = Clock()
    monkeypatch.setattr(india, "now_ist", lambda: c.now)
    monkeypatch.setattr(india, "market_open", lambda at=None: real_market_open(at or c.now))
    monkeypatch.setattr(india.time, "sleep", lambda s: None)
    return c


def ex_cfg(tmp_path):
    ex = Config().execution
    ex.state_dir = str(tmp_path)
    return ex


def india_cfg(**settings):
    ic = Config().india
    for key, value in settings.items():
        setattr(ic, key, value)
    return ic


def plan(**overrides):
    p = {"symbol": "RELIANCE.NS", "side": "long", "qty": 10.0, "entry_ref": 1000.0, "stop": 950.0,
         "target": 1100.0, "max_hold": 5, "setup": "test", "signal_date": "2026-11-02"}
    p.update(overrides)
    return p


def open_position(broker, clock):
    """AMO after Monday's close, filled at Tuesday's open, protected by the 09:20 monitor run."""
    if not broker.positions():
        broker.submit(plan())
    clock.set(2026, 11, 3, 9, 20)
    broker.fill("1", 1005.0)
    broker.prices["RELIANCE.NS"] = 1006.0
    broker.hold["RELIANCE"] = 10
    return broker.manage({})


def test_dry_run_sends_nothing(tmp_path, clock):
    broker = FakeExchange(ex_cfg(tmp_path), live=False)
    result = broker.submit(plan())
    assert result["status"] == "dry-run" and "BUY 10 NSE:RELIANCE LIMIT 1010.0 CNC" in result["reason"]
    assert broker.calls == [] and broker.positions() == []


def test_delivery_is_long_only(tmp_path, clock):
    with pytest.raises(BrokerError, match="long only"):
        FakeExchange(ex_cfg(tmp_path)).submit(plan(side="short"))


def test_after_hours_amo_fill_protect_and_target_exit(tmp_path, clock):
    broker = FakeExchange(ex_cfg(tmp_path))
    assert broker.submit(plan())["status"] == "pending"
    assert broker.calls == [("order", "BUY", 10, 1010.0, "CNC", None, True)]  # AMO, 1% above the close
    events = open_position(broker, clock)
    assert events[0]["status"] == "filled" and "stop-loss and target placed" in events[0]["reason"]
    # filled 5 above the plan's 1000: stop and target move with it, keeping 50 risk and 2R
    assert ("exits", 10, 955.0, 1105.0) in broker.calls
    rec = broker.positions()[0]
    assert rec["status"] == "open" and rec["entry"] == 1005.0 and rec["exit_ids"] == ["2", "3"]
    assert (rec["stop"], rec["initial_stop"], rec["target"]) == (955.0, 955.0, 1105.0)

    clock.set(2026, 11, 4, 11, 0)  # the target leg fires and sells the shares
    broker.exits["3"], broker.hold["RELIANCE"], broker.sold_at = "triggered", 0, 1105.0
    closed = broker.manage({})[0]
    assert closed["reason"] == "target" and closed["pnl"] == pytest.approx(1000.0)
    assert closed["r_multiple"] == pytest.approx(2.0)
    assert broker.exits["2"] == "cancelled" and broker.positions() == []  # leftover stop leg removed


def test_time_exit_sells_and_records_the_fill(tmp_path, clock):
    broker = FakeExchange(ex_cfg(tmp_path))
    open_position(broker, clock)
    clock.set(2026, 11, 10, 10, 0)  # five business days later
    broker.prices["RELIANCE.NS"] = 1020.0
    assert broker.manage({}) == []
    assert broker.calls[-1] == ("order", "SELL", 10, 1009.8, "CNC", None, False)
    assert broker.positions()[0]["status"] == "closing"
    assert broker.exits["2"] == broker.exits["3"] == "cancelled"
    broker.fill(str(broker._n), 1019.0)
    closed = broker.manage({})[0]
    assert closed["reason"] == "time" and closed["exit"] == 1019.0 and broker.positions() == []


def test_queued_entry_waits_for_the_session(tmp_path, clock):
    broker = FakeExchange(ex_cfg(tmp_path), amo=False)
    assert broker.submit(plan())["status"] == "queued" and broker.calls == []
    assert broker.manage({}) == []  # still closed
    clock.set(2026, 11, 3, 9, 20)
    broker.prices["RELIANCE.NS"] = 1003.0
    broker.manage({})
    assert broker.calls == [("order", "BUY", 10, 1013.1, "CNC", None, False)]
    assert broker.positions()[0]["status"] == "pending"


def test_queued_entry_is_dropped_when_price_runs_away_or_the_signal_is_old(tmp_path, clock):
    broker = FakeExchange(ex_cfg(tmp_path), amo=False)
    broker.submit(plan())
    clock.set(2026, 11, 3, 9, 20)
    broker.prices["RELIANCE.NS"] = 940.0  # gapped below the stop overnight
    assert broker.manage({})[0]["status"] == "cancelled" and broker.positions() == []
    clock.set(2026, 11, 3, 16, 30)  # a new signal after Tuesday's close...
    broker.submit(plan(signal_date="2026-11-03"))
    clock.set(2026, 11, 5, 9, 20)  # ...but the monitor did not run on Wednesday
    assert broker.manage({})[0]["reason"].startswith("signal expired")


def test_rejected_entry_is_cleaned_up(tmp_path, clock):
    broker = FakeExchange(ex_cfg(tmp_path))
    broker.submit(plan())
    broker.orders["1"]["status"] = "rejected"
    assert broker.manage({})[0]["status"] == "cancelled" and broker.positions() == []


def test_entry_outside_the_plan_or_duplicate_is_refused(tmp_path, clock):
    broker = FakeExchange(ex_cfg(tmp_path))
    with pytest.raises(BrokerError, match="outside the stop"):
        broker.submit(plan(entry_ref=1095.0))
    broker.submit(plan())
    with pytest.raises(BrokerError, match="already has an order"):
        broker.submit(plan())
    assert len(broker.orders) == 1


def test_active_exits_are_never_cancelled_on_a_holdings_glitch(tmp_path, clock):
    broker = FakeExchange(ex_cfg(tmp_path))
    open_position(broker, clock)
    broker.hold["RELIANCE"] = 0  # holdings say zero but both exit legs are still waiting
    assert broker.manage({}) == []
    assert broker.positions()[0]["status"] == "open" and broker.exits["2"] == "active"


def test_expired_exits_are_replaced(tmp_path, clock):
    broker = FakeExchange(ex_cfg(tmp_path))
    open_position(broker, clock)
    broker.exits["2"] = broker.exits["3"] = "expired"
    broker.manage({})
    assert broker.positions()[0]["exit_ids"] == ["4", "5"]


def test_stop_moves_to_breakeven_after_one_r(tmp_path, clock):
    broker = FakeExchange(ex_cfg(tmp_path))
    open_position(broker, clock)
    broker.prices["RELIANCE.NS"] = 1056.0  # entry 1005, risk 50 -> +1R
    broker.manage({})
    rec = broker.positions()[0]
    assert rec["stop"] == 1005.0 and rec["trailed"] and broker.calls[-1] == ("exits", 10, 1005.0, 1105.0)


def test_triggered_stop_that_did_not_fill_is_exited_next_session(tmp_path, clock):
    broker = FakeExchange(ex_cfg(tmp_path))
    open_position(broker, clock)
    broker.exits["2"], broker.prices["RELIANCE.NS"] = "triggered", 900.0
    assert broker.manage({}) == [] and broker.positions()[0]["status"] == "open"  # its sell may still fill
    clock.set(2026, 11, 4, 9, 30)
    broker.manage({})
    rec = broker.positions()[0]
    assert rec["status"] == "closing" and rec["exit_reason"].startswith("stop")
    assert broker.calls[-1] == ("order", "SELL", 10, 891.0, "CNC", None, False)


# ---- intraday ------------------------------------------------------------------------------------

def intraday_open(tmp_path, clock):
    broker = FakeExchange(ex_cfg(tmp_path), ic=india_cfg(segment="intraday"))
    assert broker.submit(plan(stop=980.0, target=1040.0))["status"] == "queued"
    clock.set(2026, 11, 3, 9, 20)
    broker.prices["RELIANCE.NS"] = 1002.0
    broker.manage({})
    broker.fill("1", 1003.0)
    event = broker.manage({})[0]
    return broker, event


def test_intraday_long_enters_protects_and_exits_at_target(tmp_path, clock):
    broker, event = intraday_open(tmp_path, clock)
    assert broker.calls[0] == ("order", "BUY", 10, 1012.1, "MIS", None, False)  # sent in the session
    assert event["status"] == "filled"
    rec = broker.positions()[0]
    assert (rec["stop"], rec["target"]) == (983.0, 1043.0)  # moved 3 with the fill
    assert broker.calls[-1] == ("order", "SELL", 10, 953.5, "MIS", 983.0, False)  # stop-loss order
    clock.set(2026, 11, 3, 11, 0)
    broker.prices["RELIANCE.NS"] = 1044.0
    broker.manage({})
    assert broker.orders["2"]["status"] == "cancelled"  # the stop order goes first
    assert broker.calls[-1] == ("order", "SELL", 10, 1033.5, "MIS", None, False)
    broker.fill("3", 1043.5)
    closed = broker.manage({})[0]
    assert closed["reason"] == "target" and closed["pnl"] == pytest.approx(405.0)
    assert broker.positions() == []


def test_intraday_short_is_squared_off_before_the_close(tmp_path, clock):
    broker = FakeExchange(ex_cfg(tmp_path), ic=india_cfg(segment="intraday"))
    broker.submit(plan(side="short", stop=1020.0, target=960.0))
    clock.set(2026, 11, 3, 9, 20)
    broker.prices["RELIANCE.NS"] = 998.0
    broker.manage({})
    assert broker.calls[-1] == ("order", "SELL", 10, 988.0, "MIS", None, False)
    broker.fill("1", 997.0)
    broker.manage({})
    rec = broker.positions()[0]
    assert (rec["stop"], rec["target"]) == (1017.0, 957.0)
    assert broker.calls[-1] == ("order", "BUY", 10, 1047.6, "MIS", 1017.0, False)  # stop above a short
    clock.set(2026, 11, 3, 15, 12)
    broker.prices["RELIANCE.NS"] = 990.0
    broker.manage({})
    assert broker.positions()[0]["exit_reason"] == "intraday square-off"
    assert broker.calls[-1] == ("order", "BUY", 10, 999.9, "MIS", None, False)


def test_intraday_entry_window(tmp_path, clock):
    broker = FakeExchange(ex_cfg(tmp_path), ic=india_cfg(segment="intraday"))
    broker.submit(plan())
    clock.set(2026, 11, 3, 10, 45)  # the monitor first ran after the 10:30 entry cut-off
    broker.prices["RELIANCE.NS"] = 1002.0
    assert broker.manage({})[0]["reason"] == "missed the morning entry window" and broker.calls == []


def test_a_filled_stop_order_closes_the_trade(tmp_path, clock):
    broker, _ = intraday_open(tmp_path, clock)
    broker.fill("2", 983.0)  # the stop-loss order filled at the exchange
    closed = broker.manage({})[0]
    assert closed["reason"] == "stop" and closed["exit"] == 983.0


def test_a_flat_position_cancels_its_orphaned_stop_order(tmp_path, clock):
    broker, _ = intraday_open(tmp_path, clock)
    broker.pos.clear()  # closed by hand in the broker's app
    closed = broker.manage({})[0]
    assert closed["reason"].startswith("closed outside") and broker.orders["2"]["status"] == "cancelled"


def test_no_exit_order_while_the_stop_already_filled(tmp_path, clock):
    broker, _ = intraday_open(tmp_path, clock)
    broker.orders["2"].update(status="complete", filled=10, avg=983.0)  # positions not refreshed yet
    broker.prices["RELIANCE.NS"] = 1044.0  # and the target is reached at the same moment
    before = len(broker.calls)
    assert broker.manage({}) == []
    assert not [c for c in broker.calls[before:] if c[0] == "order"]  # a sell now would open a short


# ---- futures and options ---------------------------------------------------------------------------

def fut_rows():
    return [{"kind": "FUT", "expiry": exp, "strike": 0.0, "lot": 75, "tick": 0.05,
             "tradingsymbol": f"NIFTY{exp:%y%b}FUT".upper(), "token": f"F{exp.day}", "exchange": "NFO"}
            for exp in (date(2026, 11, 5), date(2026, 11, 26))]


def opt_rows(expiry=date(2026, 11, 10)):
    return [{"kind": kind, "expiry": expiry, "strike": strike, "lot": 75, "tick": 0.05,
             "tradingsymbol": f"NIFTY{expiry:%y%m%d}{int(strike)}{kind}", "token": f"{kind}{int(strike)}",
             "exchange": "NFO"}
            for strike in (24900.0, 25000.0, 25100.0) for kind in ("CE", "PE")]


def index_plan(**overrides):
    return plan(**{"symbol": "^NSEI", "entry_ref": 25000.0, "stop": 24700.0, "target": 25600.0, "qty": 166.0,
                   **overrides})


def test_index_futures_lots_protection_and_expiry_exit(tmp_path, clock):
    ex = ex_cfg(tmp_path)
    ex.max_order_value = 5_000_000
    broker = FakeExchange(ex, ic=india_cfg(segment="futures"))
    broker.rows[("NIFTY", "NFO")] = fut_rows()
    broker.index_prices["NIFTY"], broker.prices["NIFTY26NOVFUT"] = 25010.0, 25100.0
    clock.set(2026, 11, 3, 9, 20)
    assert broker.submit(index_plan(risk_amount=50_000.0))["status"] == "pending"
    # 300 points x 75 = 22,500 risk per lot -> 2 lots of the Nov 26 contract (Nov 5 is too close)
    assert broker.calls[-1] == ("order", "BUY", 150, 25355.0, "NRML", None, False)
    broker.fill("1", 25105.0)
    broker.manage({})
    rec = broker.positions()[0]
    assert (rec["qty"], rec["stop"], rec["target"], rec["expiry"]) == (150, 24805.0, 25705.0, "2026-11-26")
    assert broker.calls[-1] == ("order", "SELL", 150, 24060.0, "NRML", 24805.0, False)

    clock.set(2026, 11, 4, 9, 20)  # next session: yesterday's DAY stop order lapsed, so place a new one
    broker.orders["2"]["status"] = "cancelled"
    broker.prices["NIFTY26NOVFUT"] = 25200.0
    broker.manage({})
    assert broker.calls[-1] == ("order", "SELL", 150, 24060.0, "NRML", 24805.0, False)
    assert broker.positions()[0]["stop_order_id"] == "3"

    clock.set(2026, 11, 25, 10, 0)  # a day before expiry
    broker.manage({})
    rec = broker.positions()[0]
    assert rec["status"] == "closing" and rec["exit_reason"] == "expiry"
    assert broker.orders["3"]["status"] == "cancelled" and broker.calls[-1][:3] == ("order", "SELL", 150)


def test_futures_refuse_when_one_lot_exceeds_the_budget(tmp_path, clock):
    ex = ex_cfg(tmp_path)
    ex.max_order_value = 5_000_000
    broker = FakeExchange(ex, live=False, ic=india_cfg(segment="futures"))
    broker.rows[("NIFTY", "NFO")] = fut_rows()
    broker.index_prices["NIFTY"], broker.prices["NIFTY26NOVFUT"] = 25010.0, 25100.0
    with pytest.raises(BrokerError, match="one lot risks 22,500"):
        broker.submit(index_plan(risk_amount=10_000.0))
    ex.max_order_value = 1_000_000
    with pytest.raises(BrokerError, match="above max_order_value"):
        broker.submit(index_plan(risk_amount=50_000.0))


def test_options_buy_a_call_sized_by_premium_and_exit_on_the_index(tmp_path, clock):
    ex = ex_cfg(tmp_path)
    ex.max_order_value = 1_000_000
    broker = FakeExchange(ex, ic=india_cfg(segment="options"))
    broker.rows[("NIFTY", "NFO")] = opt_rows()
    broker.index_prices["NIFTY"], broker.prices["NIFTY26111025000CE"] = 25010.0, 120.0
    clock.set(2026, 11, 2, 9, 30)  # Monday session; the Nov 10 expiry is 8 days out
    broker.submit(index_plan(risk_amount=20_000.0, signal_date="2026-10-30"))
    # at-the-money call; premium 120 x 75 = 9,000 per lot -> 2 lots for a 20,000 budget
    assert broker.calls[-1] == ("order", "BUY", 150, 121.2, "NRML", None, False)
    broker.fill("1", 121.0)
    broker.manage({})
    rec = broker.positions()[0]
    assert (rec["stop"], rec["target"]) == (24710.0, 25610.0)  # index levels, moved 10 with the index
    assert broker.calls[-1] == ("order", "SELL", 150, 58.65, "NRML", 60.5, False)  # premium stop at -50%
    broker.index_prices["NIFTY"], broker.prices["NIFTY26111025000CE"] = 25620.0, 410.0
    broker.manage({})
    assert broker.positions()[0]["exit_reason"] == "target"
    assert broker.calls[-1] == ("order", "SELL", 150, 405.9, "NRML", None, False)


def test_options_short_signal_buys_a_put_and_strike_choice(tmp_path, clock):
    ex = ex_cfg(tmp_path)
    ex.max_order_value = 1_000_000
    broker = FakeExchange(ex, live=False, ic=india_cfg(segment="options", option_strike="otm1"))
    broker.rows[("NIFTY", "NFO")] = opt_rows()
    broker.index_prices["NIFTY"], broker.prices["NIFTY26111024900PE"] = 25010.0, 95.0
    result = broker.submit(index_plan(side="short", stop=25300.0, target=24400.0, risk_amount=20_000.0))
    assert result["status"] == "dry-run"
    assert "BUY 2 lot(s) = 150 NFO:NIFTY26111024900PE" in result["reason"]  # one strike out of the money


def test_a_wrong_index_price_is_not_trusted(tmp_path, clock):
    broker = FakeExchange(ex_cfg(tmp_path), ic=india_cfg(segment="options"))
    broker._refs["^NSEI"] = 25000.0
    broker.index_prices["NIFTY"] = 51000.0  # e.g. a mapping that returned Bank Nifty
    assert broker.underlying_ltp("^NSEI") == 25000.0


# ---- adapters against fake SDKs ----------------------------------------------------------------

def adapter(monkeypatch, tmp_path, name, live=True, segment="delivery"):
    from trader.brokers import make_broker

    cfg = Config()
    cfg.execution.broker = name
    cfg.execution.mode = "live" if live else "paper"
    cfg.execution.state_dir = str(tmp_path)
    cfg.india.segment = segment
    return make_broker(cfg.finalize())


class FakeKite:
    instances = []

    def __init__(self, api_key):
        self.calls, self.token = [], None
        FakeKite.instances.append(self)

    def set_access_token(self, token):
        self.token = token

    def login_url(self):
        return "https://kite.zerodha.com/connect/login?api_key=k&v=3"

    def generate_session(self, request_token, api_secret):
        self.calls.append(("session", request_token, api_secret))
        return {"access_token": "tok-" + request_token, "user_id": "AB1234"}

    def instruments(self, exchange=None):
        if exchange == "NFO":
            base = {"name": "NIFTY", "lot_size": 75, "tick_size": 0.05, "exchange": "NFO"}
            return [{**base, "tradingsymbol": "NIFTY26NOVFUT", "instrument_type": "FUT", "expiry": date(2026, 11, 26),
                     "strike": 0.0, "instrument_token": 111},
                    {**base, "tradingsymbol": "NIFTY26N1025000CE", "instrument_type": "CE",
                     "expiry": date(2026, 11, 10), "strike": 25000.0, "instrument_token": 222},
                    {**base, "name": "BANKNIFTY", "tradingsymbol": "BANKNIFTY26NOVFUT", "instrument_type": "FUT",
                     "expiry": date(2026, 11, 26), "strike": 0.0, "instrument_token": 333}]
        return [{"tradingsymbol": "RELIANCE", "tick_size": 0.1, "instrument_type": "EQ"}]

    def margins(self, segment=None):
        return {"net": 50000.0, "available": {"live_balance": 40000.0}}

    def holdings(self):
        return [{"tradingsymbol": "INFY", "quantity": 5, "t1_quantity": 0, "average_price": 1500, "last_price": 1550}]

    def positions(self):
        return {"net": [{"tradingsymbol": "RELIANCE", "exchange": "NSE", "product": "CNC", "quantity": 10,
                         "average_price": 1005, "last_price": 1010, "instrument_token": 1},
                        {"tradingsymbol": "SBIN", "exchange": "NSE", "product": "MIS", "quantity": -3,
                         "instrument_token": 2}]}

    def ltp(self, *keys):
        return {k: {"last_price": 25010.0 if k == "NSE:NIFTY 50" else 1010.0} for k in keys}

    def place_order(self, **kw):
        self.calls.append(("order", kw))
        return "ORD1"

    def cancel_order(self, variety, order_id, parent_order_id=None):
        self.calls.append(("cancel", variety, order_id))

    def order_history(self, order_id):
        return [{"status": "OPEN"}, {"status": "COMPLETE", "filled_quantity": 10, "average_price": 1004.5}]

    def place_gtt(self, **kw):
        self.calls.append(("gtt", kw))
        return {"trigger_id": 77}

    def get_gtt(self, trigger_id):
        return {"status": "active"}

    def delete_gtt(self, trigger_id):
        self.calls.append(("delete_gtt", trigger_id))

    def orders(self):
        return []


def test_zerodha_login_orders_gtt_and_contracts(monkeypatch, tmp_path, clock, capsys):
    monkeypatch.setitem(sys.modules, "kiteconnect", types.SimpleNamespace(KiteConnect=FakeKite))
    monkeypatch.setenv("KITE_API_KEY", "key")
    monkeypatch.setenv("KITE_API_SECRET", "secret")
    monkeypatch.delenv("KITE_ACCESS_TOKEN", raising=False)
    broker = adapter(monkeypatch, tmp_path, "zerodha")
    with pytest.raises(BrokerError, match="login"):
        broker.funds()  # no session yet
    message = broker.login(prompt=lambda _: "https://myapp/cb?action=login&request_token=RT9&status=success")
    assert "AB1234" in message and FakeKite.instances[-1].calls[0] == ("session", "RT9", "secret")

    broker = adapter(monkeypatch, tmp_path, "zerodha")  # a later run reuses today's session
    kite = broker.api
    assert kite.token == "tok-RT9"
    assert broker.funds() == 40000.0
    assert broker.held_symbols() == {"INFY.NS", "INFY.BO", "RELIANCE.NS", "RELIANCE.BO"}  # MIS ignored
    assert broker.account()["equity"] == pytest.approx(40000 + 5 * 1550 + 10 * 1010)
    inst = broker.instrument("RELIANCE.NS")
    assert inst.tick == 0.1
    broker.place_order(inst, "BUY", 10, 1010.0, "CNC", amo=True)
    assert kite.calls[-1][1] == {"variety": "amo", "exchange": "NSE", "tradingsymbol": "RELIANCE",
                                 "transaction_type": "BUY", "quantity": 10, "product": "CNC",
                                 "order_type": "LIMIT", "price": 1010.0, "validity": "DAY", "tag": "aidesk"}
    assert broker.order_state("ORD1") == {"status": "complete", "filled": 10, "avg": 1004.5, "message": ""}
    assert broker.place_exits(inst, 10, 950.0, 1100.0, 1010.0) == ["77"]
    gtt = kite.calls[-1][1]
    assert gtt["trigger_type"] == "two-leg" and gtt["trigger_values"] == [950.0, 1100.0]
    assert [o["price"] for o in gtt["orders"]] == [921.5, 1100.0]  # stop-loss limit 3% under its trigger
    assert all(o["transaction_type"] == "SELL" and o["product"] == "CNC" for o in gtt["orders"])
    assert broker.exit_status(inst, ["77"]) == "active"

    # futures and options
    rows = broker.contracts("NIFTY", "NFO")
    assert {(r["kind"], r["tradingsymbol"], r["lot"]) for r in rows} == \
        {("FUT", "NIFTY26NOVFUT", 75), ("CE", "NIFTY26N1025000CE", 75)}
    assert broker.index_ltp("NIFTY") == 25010.0
    fut = broker.contract("^NSEI", "FUT")
    assert (fut.exchange, fut.tradingsymbol, fut.lot, fut.expiry) == ("NFO", "NIFTY26NOVFUT", 75, "2026-11-26")
    broker.place_order(fut, "SELL", 75, 24060.0, "NRML", trigger=24805.0)
    order = kite.calls[-1][1]
    assert (order["exchange"], order["product"], order["order_type"], order["trigger_price"]) == \
        ("NFO", "NRML", "SL", 24805.0)
    broker.cancel_order("ORD1")
    assert kite.calls[-1] == ("cancel", "regular", "ORD1")
    assert {"exchange": "NSE", "tradingsymbol": "SBIN", "token": "2", "product": "MIS", "qty": -3} \
        in broker.net_positions()


def test_zerodha_session_expires_at_six_am(monkeypatch, tmp_path, clock):
    monkeypatch.setitem(sys.modules, "kiteconnect", types.SimpleNamespace(KiteConnect=FakeKite))
    broker = adapter(monkeypatch, tmp_path, "zerodha")
    clock.set(2026, 11, 2, 10, 0)
    broker.save_token("T")
    clock.set(2026, 11, 3, 5, 59)
    assert broker.load_token() == "T"
    clock.set(2026, 11, 3, 6, 1)
    assert broker.load_token() is None


class FakeSmart:
    instances = []

    def __init__(self, api_key):
        self.calls, self.fail_rule = [], None
        FakeSmart.instances.append(self)

    def generateSession(self, client_code, pin, code):
        self.calls.append(("session", client_code, pin, code))
        return {"status": True, "data": {"jwtToken": "j"}}

    def searchScrip(self, exchange, query):
        return {"status": True, "data": [{"exchange": "NSE", "tradingsymbol": "RELIANCE-BE", "symboltoken": "9"},
                                         {"exchange": "NSE", "tradingsymbol": "RELIANCE-EQ", "symboltoken": "2885"}]}

    def placeOrderFullResponse(self, params):
        self.calls.append(("order", dict(params)))
        return {"status": True, "data": {"orderid": "A1"}}

    def cancelOrder(self, order_id, variety):
        self.calls.append(("cancel", order_id, variety))
        return {"status": True, "data": {"orderid": order_id}}

    def gttCreateRule(self, params):
        self.calls.append(("gtt", dict(params)))
        if self.fail_rule == len([c for c in self.calls if c[0] == "gtt"]):
            raise Exception("rule rejected")
        return f"R{len(self.calls)}"

    def gttCancelRule(self, params):
        self.calls.append(("gtt_cancel", dict(params)))

    def rmsLimit(self):
        return {"status": True, "data": {"availablecash": "25000.50", "net": "30000"}}

    def holding(self):
        return {"status": True, "data": None}

    def position(self):
        return {"status": True, "data": [{"tradingsymbol": "SBIN-EQ", "exchange": "NSE", "symboltoken": "3045",
                                          "producttype": "DELIVERY", "netqty": "4", "avgnetprice": "800", "ltp": "810"},
                                         {"tradingsymbol": "NIFTY26NOV26FUT", "exchange": "NFO", "symboltoken": "35001",
                                          "producttype": "CARRYFORWARD", "netqty": "-75"}]}

    def orderBook(self):
        return {"status": True, "data": [{"orderid": "A1", "variety": "STOPLOSS", "status": "complete",
                                          "filledshares": "10", "averageprice": 1003.0, "text": ""}]}

    def ltpData(self, exchange, tradingsymbol, symboltoken):
        return {"status": True, "data": {"ltp": 25010.0 if symboltoken == "99926000" else 1001.0}}


def test_angel_one_login_orders_gtt_pair_and_contracts(monkeypatch, tmp_path):
    monkeypatch.setitem(sys.modules, "SmartApi", types.SimpleNamespace(SmartConnect=FakeSmart))
    for key, value in {"ANGEL_API_KEY": "k", "ANGEL_CLIENT_CODE": "C1", "ANGEL_PIN": "1234",
                       "ANGEL_TOTP_SECRET": "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"}.items():
        monkeypatch.setenv(key, value)
    broker = adapter(monkeypatch, tmp_path, "angelone")
    assert broker.funds() == 25000.5
    smart = FakeSmart.instances[-1]
    session = smart.calls[0]
    assert session[:3] == ("session", "C1", "1234") and len(session[3]) == 6 and session[3].isdigit()
    assert broker.holdings() == {"SBIN": {"qty": 4, "avg": 800.0, "ltp": 810.0}}
    inst = broker.instrument("RELIANCE.NS")
    assert (inst.tradingsymbol, inst.token) == ("RELIANCE-EQ", "2885")
    assert broker.place_order(inst, "BUY", 10, 1010.0, "CNC", amo=True) == "A1"
    order = smart.calls[-1][1]
    assert order["variety"] == "AMO" and order["producttype"] == "DELIVERY" and order["ordertype"] == "LIMIT"
    assert (order["price"], order["quantity"], order["transactiontype"]) == ("1010.00", "10", "BUY")
    broker.place_order(inst, "SELL", 10, 953.5, "MIS", trigger=983.0)
    order = smart.calls[-1][1]
    assert (order["variety"], order["ordertype"], order["producttype"], order["triggerprice"]) == \
        ("STOPLOSS", "STOPLOSS_LIMIT", "INTRADAY", "983.00")
    broker.cancel_order("A1")
    assert smart.calls[-1] == ("cancel", "A1", "STOPLOSS")  # the variety comes from the order book
    assert broker.order_state("A1")["status"] == "complete"
    assert len(broker.place_exits(inst, 10, 950.0, 1100.0, 1010.0)) == 2
    stop_rule, target_rule = [c[1] for c in smart.calls if c[0] == "gtt"]
    assert (stop_rule["triggerprice"], stop_rule["price"]) == (950.0, 921.5)
    assert (target_rule["triggerprice"], target_rule["price"]) == (1100.0, 1100.0)
    assert stop_rule["transactiontype"] == "SELL" and stop_rule["producttype"] == "DELIVERY"
    assert broker.index_ltp("NIFTY") == 25010.0
    assert {"exchange": "NFO", "tradingsymbol": "NIFTY26NOV26FUT", "token": "35001", "product": "NRML",
            "qty": -75} in broker.net_positions()

    (tmp_path / "angelone_scrip_master.json").write_text(json.dumps([
        {"token": "35001", "symbol": "NIFTY26NOV26FUT", "name": "NIFTY", "expiry": "26NOV2026", "strike": "-1.000000",
         "lotsize": "75", "instrumenttype": "FUTIDX", "exch_seg": "NFO", "tick_size": "5.000000"},
        {"token": "35002", "symbol": "NIFTY10NOV2625000CE", "name": "NIFTY", "expiry": "10NOV2026",
         "strike": "2500000.000000", "lotsize": "75", "instrumenttype": "OPTIDX", "exch_seg": "NFO",
         "tick_size": "5.000000"},
        {"token": "40001", "symbol": "RELIANCE26NOV26FUT", "name": "RELIANCE", "expiry": "26NOV2026",
         "strike": "-1.000000", "lotsize": "500", "instrumenttype": "FUTSTK", "exch_seg": "NFO", "tick_size": "10.0"}]))
    rows = {r["kind"]: r for r in broker.contracts("NIFTY", "NFO")}
    assert set(rows) == {"FUT", "CE"} and rows["CE"]["strike"] == 25000.0 and rows["CE"]["tick"] == 0.05
    assert rows["FUT"]["expiry"] == date(2026, 11, 26) and rows["FUT"]["lot"] == 75 and rows["FUT"]["strike"] == 0.0

    smart.calls.clear()
    smart.fail_rule = 2  # the target rule fails -> the stop rule must not be left behind alone
    with pytest.raises(BrokerError):
        broker.place_exits(inst, 10, 950.0, 1100.0, 1010.0)
    assert [c[0] for c in smart.calls] == ["gtt", "gtt", "gtt_cancel"]


class FakeDhan:
    instances = []

    def __init__(self, context):
        self.context, self.calls = context, []
        FakeDhan.instances.append(self)

    def place_order(self, **kw):
        self.calls.append(("order", kw))
        return {"status": "success", "remarks": "", "data": {"orderId": "D1", "orderStatus": "PENDING"}}

    def cancel_order(self, order_id):
        self.calls.append(("cancel", order_id))
        return {"status": "success", "data": {"orderId": order_id, "orderStatus": "CANCELLED"}}

    def place_forever(self, **kw):
        self.calls.append(("forever", kw))
        return {"status": "success", "remarks": "", "data": {"orderId": f"F{len(self.calls)}"}}

    def get_fund_limits(self):
        return {"status": "success", "data": {"availabelBalance": 12345.6}}

    def get_holdings(self):
        return {"status": "failure", "data": "",
                "remarks": {"error_code": "DH-1111", "error_message": "No holdings available"}}

    def get_positions(self):
        return {"status": "success", "data": [
            {"tradingSymbol": "TCS", "securityId": "11536", "exchangeSegment": "NSE_EQ", "productType": "CNC",
             "netQty": 2, "buyAvg": 3000},
            {"tradingSymbol": "NIFTY-Nov2026-FUT", "securityId": "35001", "exchangeSegment": "NSE_FNO",
             "productType": "MARGIN", "netQty": 75}]}

    def get_order_by_id(self, order_id):
        return {"status": "success", "data": {"orderStatus": "TRADED", "filledQty": 10, "averageTradedPrice": 1002.5}}

    def get_forever(self):
        return {"status": "success", "data": [{"orderId": "F1", "orderStatus": "PENDING"},
                                              {"orderId": "F2", "orderStatus": "TRIGGERED"}]}

    def ticker_data(self, securities):
        segment, ids = next(iter(securities.items()))
        price = 25010.0 if segment == "IDX_I" else 1001.5
        return {"status": "success", "data": {"data": {segment: {str(ids[0]): {"last_price": price}}}}}


class FakeDhanLogin:
    def __init__(self, client_id):
        self.client_id = client_id

    def generate_token(self, pin, code):
        assert pin == "4321" and len(code) == 6
        return {"accessToken": "AUTO-TOKEN"}


DHAN_SCRIPS = (
    "SEM_EXM_EXCH_ID,SEM_SEGMENT,SEM_SMST_SECURITY_ID,SEM_INSTRUMENT_NAME,SEM_TRADING_SYMBOL,SEM_SERIES,"
    "SEM_TICK_SIZE,SEM_LOT_UNITS,SEM_EXPIRY_DATE,SEM_STRIKE_PRICE,SEM_OPTION_TYPE\n"
    "NSE,E,9999,EQUITY,RELIANCE,BE,5.0000,1.0,,,\n"
    "NSE,E,2885,EQUITY,RELIANCE,EQ,5.0000,1.0,,,\n"
    "BSE,E,500325,EQUITY,RELIANCE,A,5.0000,1.0,,,\n"
    "NSE,D,35001,FUTIDX,NIFTY-Nov2026-FUT,NA,5.0,75.0,2026-11-26 14:30:00,-0.01,XX\n"
    "NSE,D,35002,OPTIDX,NIFTY-Nov2026-25000-CE,NA,5.0,75.0,2026-11-10 14:30:00,25000.0,CE\n"
    "NSE,D,36001,FUTSTK,BAJAJ-AUTO-Nov2026-FUT,NA,10.0,75.0,2026-11-26 14:30:00,-0.01,XX\n")


def test_dhan_token_scrips_orders_forever_pair_and_contracts(monkeypatch, tmp_path):
    monkeypatch.setitem(sys.modules, "dhanhq", types.SimpleNamespace(
        DhanContext=lambda client_id, token: (client_id, token), dhanhq=FakeDhan, DhanLogin=FakeDhanLogin))
    monkeypatch.delenv("DHAN_ACCESS_TOKEN", raising=False)
    monkeypatch.setenv("DHAN_CLIENT_ID", "100")
    monkeypatch.setenv("DHAN_PIN", "4321")
    monkeypatch.setenv("DHAN_TOTP_SECRET", "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")
    (tmp_path / "dhan_scrip_master.csv").write_text(DHAN_SCRIPS)
    broker = adapter(monkeypatch, tmp_path, "dhan")
    assert broker.funds() == 12345.6
    assert FakeDhan.instances[-1].context == ("100", "AUTO-TOKEN") and broker.load_token() == "AUTO-TOKEN"
    assert broker.holdings() == {"TCS": {"qty": 2, "avg": 3000.0, "ltp": None}}  # "no holdings" is not an error
    inst = broker.instrument("RELIANCE.NS")
    assert (inst.token, inst.tick) == ("2885", 0.05)  # EQ series; tick listed in paise
    assert broker.instrument("RELIANCE.BO").token == "500325"
    assert broker.ltp(inst) == 1001.5 and broker.index_ltp("NIFTY") == 25010.0
    dhan = FakeDhan.instances[-1]
    broker.place_order(inst, "BUY", 10, 1010.0, "CNC", amo=True)
    order = dhan.calls[-1][1]
    assert order["security_id"] == "2885" and order["exchange_segment"] == "NSE_EQ" and order["product_type"] == "CNC"
    assert order["order_type"] == "LIMIT" and order["after_market_order"] is True and order["price"] == 1010.0
    assert broker.order_state("D1") == {"status": "complete", "filled": 10, "avg": 1002.5, "message": ""}
    broker.place_exits(inst, 10, 950.0, 1100.0, 1010.0)
    stop_leg, target_leg = [c[1] for c in dhan.calls if c[0] == "forever"]
    assert stop_leg["order_flag"] == target_leg["order_flag"] == "SINGLE"
    assert (stop_leg["trigger_Price"], stop_leg["price"]) == (950.0, 921.5)
    assert (target_leg["trigger_Price"], target_leg["price"]) == (1100.0, 1100.0)
    assert broker.exit_status(inst, ["F1", "F2"]) == "triggered"

    rows = {r["kind"]: r for r in broker.contracts("NIFTY", "NFO")}
    assert set(rows) == {"FUT", "CE"} and rows["CE"]["strike"] == 25000.0 and rows["FUT"]["lot"] == 75
    assert [r["token"] for r in broker.contracts("BAJAJ-AUTO", "NFO")] == ["36001"]  # hyphenated name
    fut = broker.contract("^NSEI", "FUT")
    broker.place_order(fut, "SELL", 75, 24060.0, "NRML", trigger=24805.0)
    order = dhan.calls[-1][1]
    assert (order["exchange_segment"], order["product_type"], order["order_type"], order["trigger_price"]) == \
        ("NSE_FNO", "MARGIN", "STOP_LOSS", 24805.0)
    assert {"exchange": "NFO", "tradingsymbol": "NIFTY-Nov2026-FUT", "token": "35001", "product": "NRML",
            "qty": 75} in broker.net_positions()

    dhan.place_order = lambda **kw: {"status": "failure", "remarks": {"error_message": "Insufficient funds"}, "data": ""}
    with pytest.raises(BrokerError, match="Insufficient funds"):
        broker.place_order(inst, "BUY", 10, 1010.0, "CNC")


class FakeGroww:
    calls = []

    def __init__(self, token):
        self.token = token

    @staticmethod
    def get_access_token(api_key, totp=None, secret=None):
        FakeGroww.calls.append(("token", api_key, totp, secret))
        return "GTOKEN"

    def place_order(self, **kw):
        FakeGroww.calls.append(("order", kw))
        return {"groww_order_id": "G1", "order_status": "OPEN"}

    def cancel_order(self, groww_order_id, segment, timeout=None):
        FakeGroww.calls.append(("cancel", groww_order_id, segment))
        return {}

    def create_smart_order(self, **kw):
        FakeGroww.calls.append(("smart", kw))
        return {"smart_order_id": f"gtt_{len(FakeGroww.calls)}", "status": "ACTIVE"}

    def get_order_detail(self, segment, groww_order_id):
        FakeGroww.calls.append(("detail", segment, groww_order_id))
        return {"order_status": "EXECUTED", "filled_quantity": 10, "average_fill_price": 1003.2}

    def get_available_margin_details(self):
        return {"clear_cash": 9000, "equity_margin_details": {"cnc_balance_available": 8500}}

    def get_holdings_for_user(self):
        return {"holdings": [{"trading_symbol": "ITC", "quantity": 20, "average_price": 400}]}

    def get_positions_for_user(self, segment=None):
        if segment == "FNO":
            return {"positions": [{"trading_symbol": "NIFTY26NOVFUT", "exchange": "NSE", "product": "NRML",
                                   "quantity": 75}]}
        return {"positions": []}

    def get_ltp(self, exchange_trading_symbols, segment):
        return {k: 25010.0 if k == "NSE_NIFTY" else 1001.0 for k in exchange_trading_symbols}


GROWW_INSTRUMENTS = (
    "exchange,exchange_token,trading_symbol,groww_symbol,name,instrument_type,segment,series,isin,"
    "underlying_symbol,underlying_exchange_token,expiry_date,strike_price,lot_size,tick_size\n"
    "NSE,2885,RELIANCE,NSE-RELIANCE,Reliance,EQ,CASH,EQ,INE002A01018,,,,,1,0.05\n"
    "NSE,35001,NIFTY26NOVFUT,NSE-NIFTY-26Nov26-FUT,NIFTY,FUT,FNO,,,NIFTY,26000,2026-11-26,,75,0.05\n"
    "NSE,35002,NIFTY2611025000CE,NSE-NIFTY-10Nov26-25000-CE,NIFTY,CE,FNO,,,NIFTY,26000,2026-11-10,25000,75,0.05\n")


def test_groww_login_orders_gtt_pair_and_contracts(monkeypatch, tmp_path):
    FakeGroww.calls.clear()
    monkeypatch.setitem(sys.modules, "growwapi", types.SimpleNamespace(GrowwAPI=FakeGroww))
    monkeypatch.delenv("GROWW_ACCESS_TOKEN", raising=False)
    monkeypatch.setenv("GROWW_API_KEY", "gk")
    monkeypatch.setenv("GROWW_TOTP_SECRET", "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")
    broker = adapter(monkeypatch, tmp_path, "groww")
    assert broker.funds() == 8500.0 and broker.api.token == "GTOKEN"
    _, key, code, secret = FakeGroww.calls[0]
    assert key == "gk" and len(code) == 6 and secret is None
    assert broker.held_symbols() == {"ITC.NS", "ITC.BO"}
    inst = broker.instrument("RELIANCE.NS")
    with pytest.raises(BrokerError, match="after-market"):
        broker.place_order(inst, "BUY", 10, 1010.0, "CNC", amo=True)
    assert broker.place_order(inst, "BUY", 10, 1010.0, "CNC") == "G1"
    order = FakeGroww.calls[-1][1]
    assert (order["product"], order["segment"], order["order_type"], order["transaction_type"]) == \
        ("CNC", "CASH", "LIMIT", "BUY")
    assert 8 <= len(order["order_reference_id"]) <= 20 and order["order_reference_id"].isalnum()
    assert broker.order_state("G1") == {"status": "complete", "filled": 10, "avg": 1003.2, "message": ""}
    broker.place_exits(inst, 10, 950.0, 1100.0, 1010.0)
    stop_leg, target_leg = [c[1] for c in FakeGroww.calls if c[0] == "smart"]
    assert stop_leg["smart_order_type"] == "GTT" and stop_leg["product_type"] == "CNC"
    assert (stop_leg["trigger_direction"], stop_leg["trigger_price"], stop_leg["order"]["price"]) == \
        ("DOWN", "950.00", "921.50")
    assert (target_leg["trigger_direction"], target_leg["trigger_price"]) == ("UP", "1100.00")

    (tmp_path / "groww_instruments.csv").write_text(GROWW_INSTRUMENTS)
    rows = {r["kind"]: r for r in broker.contracts("NIFTY", "NFO")}
    assert set(rows) == {"FUT", "CE"} and rows["CE"]["strike"] == 25000.0 and rows["FUT"]["lot"] == 75
    assert broker.index_ltp("NIFTY") == 25010.0
    fut = broker.contract("^NSEI", "FUT")
    broker.place_order(fut, "SELL", 75, 24060.0, "NRML", trigger=24805.0)
    order = FakeGroww.calls[-1][1]
    assert (order["segment"], order["exchange"], order["order_type"], order["trigger_price"]) == \
        ("FNO", "NSE", "SL", 24805.0)
    broker.order_state("G1", fut)
    assert FakeGroww.calls[-1] == ("detail", "FNO", "G1")  # F&O orders are looked up in the FNO segment
    assert {"exchange": "NFO", "tradingsymbol": "NIFTY26NOVFUT", "token": "", "product": "NRML", "qty": 75} \
        in broker.net_positions()


def test_groww_instrument_file_format_is_checked(monkeypatch, tmp_path):
    monkeypatch.setitem(sys.modules, "growwapi", types.SimpleNamespace(GrowwAPI=FakeGroww))
    monkeypatch.setenv("GROWW_ACCESS_TOKEN", "t")
    (tmp_path / "groww_instruments.csv").write_text("exchange,trading_symbol\nNSE,RELIANCE\n")
    broker = adapter(monkeypatch, tmp_path, "groww", segment="futures")
    with pytest.raises(BrokerError, match="underlying_symbol"):
        broker.contracts("NIFTY", "NFO")


def test_brokers_default_to_dry_run(monkeypatch, tmp_path):
    for name in ("zerodha", "angelone", "dhan", "groww"):
        broker = adapter(monkeypatch, tmp_path, name, live=False)
        assert broker.name == name and broker.live is False
        assert adapter(monkeypatch, tmp_path, name, live=True).live is True


def test_risk_manager_skips_shares_you_already_own(tmp_path):
    from trader.agents.risk import RiskAgent
    from trader.bus import Blackboard, Candidate, MessageBus
    from trader.data import synthetic_ohlcv

    cfg = Config()
    cfg.universe.symbols = ["INFY.NS", "TCS.NS"]
    cfg.finalize()
    board = Blackboard()
    board.context.update(account={"equity": 1_000_000, "cash": 1_000_000}, positions=[],
                         held_symbols={"INFY.NS", "INFY.BO"})
    setup = {"stop_atr": 2.0, "reward_risk": 2.0, "max_hold": 10, "label": "test"}
    for symbol in cfg.universe.symbols:
        cand = Candidate(symbol, synthetic_ohlcv(symbol, bars=300), side="long")
        cand.cache["setup"] = setup
        board.candidates[symbol] = cand
    RiskAgent(cfg, MessageBus(), board, {}).run()
    assert board.candidates["INFY.NS"].rejection.startswith("you already hold this share")
    assert board.candidates["TCS.NS"].alive and board.candidates["TCS.NS"].plan["qty"] > 0
