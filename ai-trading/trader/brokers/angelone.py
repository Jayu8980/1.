"""Angel One via SmartAPI (pip install smartapi-python).

Create an app at https://smartapi.angelone.in and set ANGEL_API_KEY, ANGEL_CLIENT_CODE,
ANGEL_PIN (your login MPIN) and ANGEL_TOTP_SECRET (the key shown when you enable TOTP). The desk
logs in by itself each run. Angel One's GTT rules are single-leg, so each position gets two: a
stop-loss rule and a target rule; the monitor cancels the leftover rule once one has fired.
"""
from __future__ import annotations

import logging
import os

from .base import BrokerError
from .india import CANCELLED, DONE, OPEN, REJECTED, IndianBroker, Instrument, add_holding, split_symbol, to_tick, totp

log = logging.getLogger("trader.brokers.angelone")
_ENV = ("ANGEL_API_KEY", "ANGEL_CLIENT_CODE", "ANGEL_PIN", "ANGEL_TOTP_SECRET")


def _base(tradingsymbol: str) -> str:
    """RELIANCE-EQ -> RELIANCE."""
    for series in ("-EQ", "-BE", "-BZ"):
        if tradingsymbol.endswith(series):
            return tradingsymbol[: -len(series)]
    return tradingsymbol


class AngelOneBroker(IndianBroker):
    name = "angelone"

    def connect(self):
        try:
            from SmartApi import SmartConnect
        except ImportError as exc:
            raise BrokerError("pip install smartapi-python to trade through Angel One") from exc
        env = {k: os.environ.get(k) for k in _ENV}
        missing = [k for k, v in env.items() if not v]
        if missing:
            raise BrokerError("set " + ", ".join(missing) + " to trade through Angel One")
        api = SmartConnect(api_key=env["ANGEL_API_KEY"])
        try:
            res = api.generateSession(env["ANGEL_CLIENT_CODE"], env["ANGEL_PIN"], totp(env["ANGEL_TOTP_SECRET"]))
        except Exception as exc:
            raise BrokerError(f"Angel One login failed: {exc}") from exc
        if not res or not res.get("status"):
            raise BrokerError(f"Angel One login failed: {(res or {}).get('message', 'no response')}")
        return api

    def _data(self, res):
        if not isinstance(res, dict) or res.get("status") is False:
            message = res.get("message") if isinstance(res, dict) else res
            raise BrokerError(f"Angel One: {message or 'request failed'}")
        return res.get("data")

    # ---- primitives --------------------------------------------------------------------------
    def instrument(self, symbol):
        cache: dict[str, Instrument] = self.__dict__.setdefault("_instruments", {})
        if symbol in cache:
            return cache[symbol]
        exchange, base = split_symbol(symbol)
        rows = self._data(self._call(self.api.searchScrip, exchange, base)) or []
        wanted = [f"{base}-EQ", base] if exchange == "NSE" else [base, f"{base}-EQ"]
        for name in wanted:
            for row in rows:
                if row.get("tradingsymbol") == name:
                    cache[symbol] = Instrument(symbol, exchange, name, str(row["symboltoken"]))
                    return cache[symbol]
        raise BrokerError(f"{base} not found on {exchange} at Angel One")

    def funds(self):
        data = self._data(self._call(self.api.rmsLimit)) or {}
        return float(data.get("availablecash") or data.get("net") or 0)

    def holdings(self):
        out = {}
        for h in self._data(self._call(self.api.holding)) or []:
            add_holding(out, _base(h["tradingsymbol"]),
                        int(float(h.get("quantity") or 0)) + int(float(h.get("t1quantity") or 0)),
                        h.get("averageprice"), h.get("ltp"))
        for p in self._data(self._call(self.api.position)) or []:
            if str(p.get("producttype", "")).upper() == "DELIVERY":
                add_holding(out, _base(p["tradingsymbol"]), int(float(p.get("netqty") or 0)),
                            p.get("avgnetprice"), p.get("ltp"))
        return out

    def ltp(self, inst):
        data = self._data(self._call(self.api.ltpData, inst.exchange, inst.tradingsymbol, inst.token)) or {}
        if data.get("ltp") is None:
            raise BrokerError(f"no price for {inst.tradingsymbol}")
        return float(data["ltp"])

    def _order(self, inst, side, qty, price, amo=False):
        params = {"variety": "AMO" if amo else "NORMAL", "tradingsymbol": inst.tradingsymbol,
                  "symboltoken": inst.token, "transactiontype": side, "exchange": inst.exchange,
                  "ordertype": "LIMIT", "producttype": "DELIVERY", "duration": "DAY", "price": f"{price:.2f}",
                  "squareoff": "0", "stoploss": "0", "quantity": str(int(qty)), "ordertag": "aidesk"}
        data = self._data(self._call(self.api.placeOrderFullResponse, params)) or {}
        if not data.get("orderid"):
            raise BrokerError("Angel One returned no order id")
        return str(data["orderid"])

    def place_buy(self, inst, qty, price, amo):
        return self._order(inst, "BUY", qty, price, amo)

    def place_sell(self, inst, qty, price):
        return self._order(inst, "SELL", qty, price)

    def _orders(self) -> list[dict]:
        return self._data(self._call(self.api.orderBook)) or []

    def order_state(self, order_id):
        for o in self._orders():
            if str(o.get("orderid")) == str(order_id):
                status = str(o.get("status") or o.get("orderstatus") or "").lower()
                mapped = {"complete": DONE, "rejected": REJECTED, "cancelled": CANCELLED}.get(status, OPEN)
                return {"status": mapped, "filled": int(float(o.get("filledshares") or 0)),
                        "avg": float(o.get("averageprice") or 0), "message": o.get("text") or ""}
        return None

    def place_exits(self, inst, qty, stop, target, ltp):
        rule = {"tradingsymbol": inst.tradingsymbol, "symboltoken": inst.token, "exchange": inst.exchange,
                "producttype": "DELIVERY", "transactiontype": "SELL", "qty": int(qty), "disclosedqty": int(qty),
                "timeperiod": 365}
        legs = [(to_tick(stop, inst.tick), self.stop_limit(stop, inst.tick)),
                (to_tick(target, inst.tick), to_tick(target, inst.tick))]
        ids = []
        try:
            for trigger, price in legs:
                ids.append(str(self._call(self.api.gttCreateRule, {**rule, "triggerprice": trigger, "price": price})))
        except BrokerError:
            self.cancel_exits(inst, ids)  # never leave half a pair behind
            raise
        return ids

    def exit_status(self, inst, exit_ids):
        states = [str((self._data(self._call(self.api.gttDetails, i)) or {}).get("status", "")).upper()
                  for i in exit_ids]
        if any(s in ("SENTTOEXCHANGE", "TRIGGERED") for s in states):
            return "triggered"
        return "active" if states and all(s in ("NEW", "ACTIVE") for s in states) else "inactive"

    def cancel_exits(self, inst, exit_ids):
        for i in exit_ids:
            try:
                self.api.gttCancelRule({"id": i, "symboltoken": inst.token, "exchange": inst.exchange})
            except Exception:
                pass  # already fired or cancelled

    def last_sell_price(self, inst):
        try:
            orders = self._orders()
        except BrokerError:
            return None
        fills = [o for o in orders if o.get("tradingsymbol") == inst.tradingsymbol
                 and o.get("transactiontype") == "SELL" and str(o.get("status")).lower() == "complete"]
        return float(fills[-1]["averageprice"]) if fills else None
