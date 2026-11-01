"""Angel One via SmartAPI (pip install smartapi-python).

Create an app at https://smartapi.angelone.in and set ANGEL_API_KEY, ANGEL_CLIENT_CODE,
ANGEL_PIN (your login MPIN) and ANGEL_TOTP_SECRET (the key shown when you enable TOTP). The desk
logs in by itself each run. Delivery positions get two single-leg GTT rules (stop-loss and
target; the monitor cancels the leftover one); intraday, futures and options positions get a
stop-loss order each session. Futures and options come from Angel One's published scrip master.
"""
from __future__ import annotations

import json
import logging
import os
from datetime import date, datetime

from .base import BrokerError
from .india import CANCELLED, DONE, OPEN, REJECTED, IndianBroker, Instrument, add_holding, split_symbol, to_tick, totp

log = logging.getLogger("trader.brokers.angelone")
_ENV = ("ANGEL_API_KEY", "ANGEL_CLIENT_CODE", "ANGEL_PIN", "ANGEL_TOTP_SECRET")
PRODUCTS = {"CNC": "DELIVERY", "MIS": "INTRADAY", "NRML": "CARRYFORWARD"}
FROM_PRODUCT = {v: k for k, v in PRODUCTS.items()}
INDEX_TOKENS = {"NIFTY": ("NSE", "Nifty 50", "99926000"), "BANKNIFTY": ("NSE", "Nifty Bank", "99926009"),
                "FINNIFTY": ("NSE", "Nifty Fin Service", "99926037"),
                "MIDCPNIFTY": ("NSE", "NIFTY MID SELECT", "99926074"), "SENSEX": ("BSE", "SENSEX", "99919000")}
SCRIP_MASTER = "https://margincalculator.angelbroking.com/OpenAPI_File/files/OpenAPIScripMaster.json"


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

    def net_positions(self):
        return [{"exchange": p.get("exchange"), "tradingsymbol": p.get("tradingsymbol"),
                 "token": str(p.get("symboltoken") or ""),
                 "product": FROM_PRODUCT.get(str(p.get("producttype", "")).upper(), p.get("producttype")),
                 "qty": int(float(p.get("netqty") or 0))}
                for p in self._data(self._call(self.api.position)) or []]

    def _ltp(self, exchange, tradingsymbol, token) -> float:
        data = self._data(self._call(self.api.ltpData, exchange, tradingsymbol, token)) or {}
        if data.get("ltp") is None:
            raise BrokerError(f"no price for {tradingsymbol}")
        return float(data["ltp"])

    def ltp(self, inst):
        return self._ltp(inst.exchange, inst.tradingsymbol, inst.token)

    def index_ltp(self, name):
        if name not in INDEX_TOKENS:
            raise BrokerError(f"no Angel One index token for {name}")
        return self._ltp(*INDEX_TOKENS[name])

    def _master(self) -> list[dict]:
        if getattr(self, "_master_rows", None) is None:
            path = self.state_dir / "angelone_scrip_master.json"
            if not (path.exists() and date.fromtimestamp(path.stat().st_mtime) == date.today()):
                import requests

                try:
                    r = requests.get(SCRIP_MASTER, timeout=120)
                    r.raise_for_status()
                except Exception as exc:
                    if not path.exists():
                        raise BrokerError(f"could not download Angel One's scrip master: {exc}") from exc
                else:
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_bytes(r.content)
            self._master_rows = json.loads(path.read_text())
        return self._master_rows

    def contracts(self, name, exchange):
        out = []
        for r in self._master():
            if r.get("exch_seg") != exchange or r.get("name") != name:
                continue
            itype, symbol = str(r.get("instrumenttype", "")), str(r.get("symbol", ""))
            kind = "FUT" if itype.startswith("FUT") else symbol[-2:] if itype.startswith("OPT") else ""
            if kind not in ("FUT", "CE", "PE"):
                continue
            try:
                expiry = datetime.strptime(r["expiry"], "%d%b%Y").date()
            except (KeyError, ValueError):
                continue
            strike = float(r.get("strike") or 0) / 100 if kind != "FUT" else 0.0  # listed in paise
            out.append({"kind": kind, "expiry": expiry, "strike": strike,
                        "lot": int(float(r.get("lotsize") or 1)), "tick": float(r.get("tick_size") or 0) / 100,
                        "tradingsymbol": symbol, "token": str(r.get("token")), "exchange": exchange})
        return out

    def place_order(self, inst, side, qty, price, product, trigger=None, amo=False):
        params = {"variety": "STOPLOSS" if trigger else "AMO" if amo else "NORMAL",
                  "tradingsymbol": inst.tradingsymbol, "symboltoken": inst.token, "transactiontype": side,
                  "exchange": inst.exchange, "ordertype": "STOPLOSS_LIMIT" if trigger else "LIMIT",
                  "producttype": PRODUCTS[product], "duration": "DAY", "price": f"{price:.2f}",
                  "squareoff": "0", "stoploss": "0", "quantity": str(int(qty)), "ordertag": "aidesk"}
        if trigger:
            params["triggerprice"] = f"{trigger:.2f}"
        data = self._data(self._call(self.api.placeOrderFullResponse, params)) or {}
        if not data.get("orderid"):
            raise BrokerError("Angel One returned no order id")
        return str(data["orderid"])

    def _orders(self) -> list[dict]:
        return self._data(self._call(self.api.orderBook)) or []

    def cancel_order(self, order_id, inst=None):
        variety = next((o.get("variety") for o in self._orders() if str(o.get("orderid")) == str(order_id)), "NORMAL")
        self._data(self._call(self.api.cancelOrder, order_id, variety))

    def order_state(self, order_id, inst=None):
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
