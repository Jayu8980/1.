"""Indian broker layer: the shared trade lifecycle and each adapter's calls, against fake SDKs.

The fakes mirror the method signatures of the official SDKs (kiteconnect 5.2, smartapi-python
1.5, dhanhq 2.3, growwapi 1.5); they record what the adapters send.
"""
import sys
import types
from datetime import datetime

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


def test_market_hours():
    monday = datetime(2026, 11, 2, 10, 0, tzinfo=IST)
    assert india.market_open(monday)
    assert not india.market_open(monday.replace(hour=16))
    assert not india.market_open(monday.replace(day=7))  # Saturday


# ---- the shared lifecycle against an in-memory exchange --------------------------------------

class FakeExchange(IndianBroker):
    name = "fake"

    def __init__(self, ex, live=True, amo=True):
        super().__init__(ex, live)
        self.supports_amo = amo
        self.orders, self.exits, self.hold, self.prices, self.calls = {}, {}, {}, {}, []
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
        if inst.symbol not in self.prices:
            raise BrokerError("no price")
        return self.prices[inst.symbol]

    def place_buy(self, inst, qty, price, amo):
        oid = self._id()
        self.orders[oid] = {"qty": qty, "status": "open", "filled": 0, "avg": 0}
        self.calls.append(("buy", qty, price, amo))
        return oid

    def place_sell(self, inst, qty, price):
        oid = self._id()
        self.orders[oid] = {"qty": qty, "status": "open", "filled": 0, "avg": 0}
        self.calls.append(("sell", qty, price))
        return oid

    def order_state(self, oid):
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
        self.calls.append(("cancel", tuple(ids)))

    def last_sell_price(self, inst):
        return self.sold_at

    def fill(self, oid, avg):
        o = self.orders[oid]
        o.update(status="complete", filled=o["qty"], avg=avg)


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


def test_after_hours_amo_fill_protect_and_target_exit(tmp_path, clock):
    broker = FakeExchange(ex_cfg(tmp_path))
    assert broker.submit(plan())["status"] == "pending"
    assert broker.calls == [("buy", 10, 1010.0, True)]  # after-market order, 1% above the close
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
    assert broker.calls[-1] == ("sell", 10, 1009.8) and broker.positions()[0]["status"] == "closing"
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
    assert broker.calls == [("buy", 10, 1013.1, False)] and broker.positions()[0]["status"] == "pending"


def test_queued_entry_is_dropped_when_price_runs_away(tmp_path, clock):
    broker = FakeExchange(ex_cfg(tmp_path), amo=False)
    broker.submit(plan())
    clock.set(2026, 11, 3, 9, 20)
    broker.prices["RELIANCE.NS"] = 940.0  # gapped below the stop overnight
    assert broker.manage({})[0]["status"] == "cancelled" and broker.positions() == []


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
    assert broker.calls[-1] == ("sell", 10, 891.0)


# ---- adapters against fake SDKs ----------------------------------------------------------------

def adapter(monkeypatch, tmp_path, name, live=True):
    from trader.brokers import make_broker

    cfg = Config()
    cfg.execution.broker = name
    cfg.execution.mode = "live" if live else "paper"
    cfg.execution.state_dir = str(tmp_path)
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

    def instruments(self, exchange):
        return [{"tradingsymbol": "RELIANCE", "tick_size": 0.1, "instrument_type": "EQ"}]

    def margins(self, segment=None):
        return {"net": 50000.0, "available": {"live_balance": 40000.0}}

    def holdings(self):
        return [{"tradingsymbol": "INFY", "quantity": 5, "t1_quantity": 0, "average_price": 1500, "last_price": 1550}]

    def positions(self):
        return {"net": [{"tradingsymbol": "RELIANCE", "product": "CNC", "quantity": 10, "average_price": 1005,
                         "last_price": 1010}, {"tradingsymbol": "SBIN", "product": "MIS", "quantity": 3}]}

    def ltp(self, *keys):
        return {k: {"last_price": 1010.0} for k in keys}

    def place_order(self, **kw):
        self.calls.append(("order", kw))
        return "ORD1"

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


def test_zerodha_login_orders_and_gtt_oco(monkeypatch, tmp_path, clock, capsys):
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
    broker.place_buy(inst, 10, 1010.0, amo=True)
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

    def gttCreateRule(self, params):
        self.calls.append(("gtt", dict(params)))
        if self.fail_rule == len([c for c in self.calls if c[0] == "gtt"]):
            raise Exception("rule rejected")
        return f"R{len(self.calls)}"

    def gttCancelRule(self, params):
        self.calls.append(("cancel", dict(params)))

    def rmsLimit(self):
        return {"status": True, "data": {"availablecash": "25000.50", "net": "30000"}}

    def holding(self):
        return {"status": True, "data": None}

    def position(self):
        return {"status": True, "data": [{"tradingsymbol": "SBIN-EQ", "producttype": "DELIVERY", "netqty": "4",
                                          "avgnetprice": "800", "ltp": "810"}]}

    def orderBook(self):
        return {"status": True, "data": [{"orderid": "A1", "status": "complete", "filledshares": "10",
                                          "averageprice": 1003.0, "text": ""}]}


def test_angel_one_totp_login_orders_and_gtt_pair(monkeypatch, tmp_path):
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
    assert broker.place_buy(inst, 10, 1010.0, amo=True) == "A1"
    order = smart.calls[-1][1]
    assert order["variety"] == "AMO" and order["producttype"] == "DELIVERY" and order["ordertype"] == "LIMIT"
    assert (order["price"], order["quantity"], order["transactiontype"]) == ("1010.00", "10", "BUY")
    assert broker.order_state("A1")["status"] == "complete"
    assert len(broker.place_exits(inst, 10, 950.0, 1100.0, 1010.0)) == 2
    stop_rule, target_rule = [c[1] for c in smart.calls if c[0] == "gtt"]
    assert (stop_rule["triggerprice"], stop_rule["price"]) == (950.0, 921.5)
    assert (target_rule["triggerprice"], target_rule["price"]) == (1100.0, 1100.0)
    assert stop_rule["transactiontype"] == "SELL" and stop_rule["producttype"] == "DELIVERY"

    smart.calls.clear()
    smart.fail_rule = 2  # the target rule fails -> the stop rule must not be left behind alone
    with pytest.raises(BrokerError):
        broker.place_exits(inst, 10, 950.0, 1100.0, 1010.0)
    assert [c[0] for c in smart.calls] == ["gtt", "gtt", "cancel"]


class FakeDhan:
    instances = []

    def __init__(self, context):
        self.context, self.calls = context, []
        FakeDhan.instances.append(self)

    def place_order(self, **kw):
        self.calls.append(("order", kw))
        return {"status": "success", "remarks": "", "data": {"orderId": "D1", "orderStatus": "PENDING"}}

    def place_forever(self, **kw):
        self.calls.append(("forever", kw))
        return {"status": "success", "remarks": "", "data": {"orderId": f"F{len(self.calls)}"}}

    def get_fund_limits(self):
        return {"status": "success", "data": {"availabelBalance": 12345.6}}

    def get_holdings(self):
        return {"status": "failure", "data": "",
                "remarks": {"error_code": "DH-1111", "error_message": "No holdings available"}}

    def get_positions(self):
        return {"status": "success", "data": [{"tradingSymbol": "TCS", "productType": "CNC", "netQty": 2,
                                               "buyAvg": 3000}]}

    def get_order_by_id(self, order_id):
        return {"status": "success", "data": {"orderStatus": "TRADED", "filledQty": 10, "averageTradedPrice": 1002.5}}

    def get_forever(self):
        return {"status": "success", "data": [{"orderId": "F1", "orderStatus": "PENDING"},
                                              {"orderId": "F2", "orderStatus": "TRIGGERED"}]}

    def ticker_data(self, securities):
        return {"status": "success", "data": {"data": {"NSE_EQ": {"2885": {"last_price": 1001.5}}}}}


class FakeDhanLogin:
    def __init__(self, client_id):
        self.client_id = client_id

    def generate_token(self, pin, code):
        assert pin == "4321" and len(code) == 6
        return {"accessToken": "AUTO-TOKEN"}


def test_dhan_auto_token_scrip_lookup_orders_and_forever_pair(monkeypatch, tmp_path):
    monkeypatch.setitem(sys.modules, "dhanhq", types.SimpleNamespace(
        DhanContext=lambda client_id, token: (client_id, token), dhanhq=FakeDhan, DhanLogin=FakeDhanLogin))
    monkeypatch.delenv("DHAN_ACCESS_TOKEN", raising=False)
    monkeypatch.setenv("DHAN_CLIENT_ID", "100")
    monkeypatch.setenv("DHAN_PIN", "4321")
    monkeypatch.setenv("DHAN_TOTP_SECRET", "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")
    (tmp_path / "dhan_scrip_master.csv").write_text(
        "SEM_EXM_EXCH_ID,SEM_SEGMENT,SEM_SMST_SECURITY_ID,SEM_INSTRUMENT_NAME,SEM_TRADING_SYMBOL,SEM_SERIES,SEM_TICK_SIZE\n"
        "NSE,E,9999,EQUITY,RELIANCE,BE,5.0000\nNSE,E,2885,EQUITY,RELIANCE,EQ,5.0000\n"
        "BSE,E,500325,EQUITY,RELIANCE,A,5.0000\n")
    broker = adapter(monkeypatch, tmp_path, "dhan")
    assert broker.funds() == 12345.6
    assert FakeDhan.instances[-1].context == ("100", "AUTO-TOKEN") and broker.load_token() == "AUTO-TOKEN"
    assert broker.holdings() == {"TCS": {"qty": 2, "avg": 3000.0, "ltp": None}}  # "no holdings" is not an error
    inst = broker.instrument("RELIANCE.NS")
    assert (inst.token, inst.tick) == ("2885", 0.05)  # EQ series; tick listed in paise
    assert broker.instrument("RELIANCE.BO").token == "500325"
    assert broker.ltp(inst) == 1001.5
    dhan = FakeDhan.instances[-1]
    broker.place_buy(inst, 10, 1010.0, amo=True)
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

    dhan.place_order = lambda **kw: {"status": "failure", "remarks": {"error_message": "Insufficient funds"}, "data": ""}
    with pytest.raises(BrokerError, match="Insufficient funds"):
        broker.place_buy(inst, 10, 1010.0, amo=False)


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

    def create_smart_order(self, **kw):
        FakeGroww.calls.append(("smart", kw))
        return {"smart_order_id": f"gtt_{len(FakeGroww.calls)}", "status": "ACTIVE"}

    def get_order_detail(self, segment, groww_order_id):
        return {"order_status": "EXECUTED", "filled_quantity": 10, "average_fill_price": 1003.2}

    def get_available_margin_details(self):
        return {"clear_cash": 9000, "equity_margin_details": {"cnc_balance_available": 8500}}

    def get_holdings_for_user(self):
        return {"holdings": [{"trading_symbol": "ITC", "quantity": 20, "average_price": 400}]}

    def get_positions_for_user(self, segment=None):
        return {"positions": []}


def test_groww_totp_login_orders_and_gtt_pair(monkeypatch, tmp_path):
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
        broker.place_buy(inst, 10, 1010.0, amo=True)
    assert broker.place_buy(inst, 10, 1010.0, amo=False) == "G1"
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
    assert stop_leg["order"]["transaction_type"] == "SELL"


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
