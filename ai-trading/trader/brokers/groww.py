"""Groww via the Groww Trade API (pip install growwapi).

Set GROWW_API_KEY plus GROWW_TOTP_SECRET (TOTP key, fully automatic login), or GROWW_API_SECRET
(key-and-secret flow, which Groww asks you to approve in the app each day), or set
GROWW_ACCESS_TOKEN directly. The API takes no after-market orders, so after-hours entries are
queued and the monitor sends them once the session opens. Groww's OCO smart orders are intraday-
only in the cash segment, so each delivery position gets two GTT smart orders instead: a stop-loss
(trigger down) and a target (trigger up); the monitor cancels the leftover one.
"""
from __future__ import annotations

import logging
import os
import random
import time

from .base import BrokerError
from .india import CANCELLED, DONE, OPEN, REJECTED, IndianBroker, Instrument, add_holding, split_symbol, to_tick, totp

log = logging.getLogger("trader.brokers.groww")
FILLED = ("EXECUTED", "COMPLETED", "DELIVERY_AWAITED")


def _reference() -> str:
    """Groww wants an 8-20 character alphanumeric reference per order."""
    return f"desk{int(time.time())}{random.randint(10, 99)}"


class GrowwBroker(IndianBroker):
    name = "groww"
    supports_amo = False

    def connect(self):
        try:
            from growwapi import GrowwAPI
        except ImportError as exc:
            raise BrokerError("pip install growwapi to trade through Groww") from exc
        token = os.environ.get("GROWW_ACCESS_TOKEN") or self.load_token() or self._new_token(GrowwAPI)
        return GrowwAPI(token)

    def _new_token(self, GrowwAPI) -> str:
        key = os.environ.get("GROWW_API_KEY")
        totp_secret, secret = os.environ.get("GROWW_TOTP_SECRET"), os.environ.get("GROWW_API_SECRET")
        if not key or not (totp_secret or secret):
            raise BrokerError("set GROWW_API_KEY with GROWW_TOTP_SECRET (or GROWW_API_SECRET), "
                              "or set GROWW_ACCESS_TOKEN")
        try:
            if totp_secret:
                token = GrowwAPI.get_access_token(api_key=key, totp=totp(totp_secret))
            else:
                token = GrowwAPI.get_access_token(api_key=key, secret=secret)
        except Exception as exc:
            raise BrokerError(f"Groww login failed: {exc}") from exc
        token = token.get("token") if isinstance(token, dict) else token
        if not token:
            raise BrokerError("Groww login returned no access token")
        self.save_token(token)
        return token

    # ---- primitives --------------------------------------------------------------------------
    def instrument(self, symbol):
        exchange, base = split_symbol(symbol)
        return Instrument(symbol, exchange, base)

    def funds(self):
        m = self._call(self.api.get_available_margin_details) or {}
        equity = m.get("equity_margin_details") or {}
        return float(equity.get("cnc_balance_available") or m.get("clear_cash") or 0)

    def holdings(self):
        out = {}
        for h in (self._call(self.api.get_holdings_for_user) or {}).get("holdings") or []:
            add_holding(out, h["trading_symbol"], int(float(h.get("quantity") or 0)), h.get("average_price"))
        for p in (self._call(self.api.get_positions_for_user, segment="CASH") or {}).get("positions") or []:
            if str(p.get("product", "")).upper() == "CNC":
                add_holding(out, p["trading_symbol"], int(float(p.get("quantity") or 0)), p.get("net_price"))
        return out

    def ltp(self, inst):
        key = f"{inst.exchange}_{inst.tradingsymbol}"
        data = self._call(self.api.get_ltp, exchange_trading_symbols=(key,), segment="CASH") or {}
        try:
            return float(data[key])
        except (KeyError, TypeError, ValueError) as exc:
            raise BrokerError(f"no price for {key} (live-data access may be needed)") from exc

    def _order(self, inst, side, qty, price):
        res = self._call(self.api.place_order, validity="DAY", exchange=inst.exchange, order_type="LIMIT",
                         product="CNC", quantity=int(qty), segment="CASH", trading_symbol=inst.tradingsymbol,
                         transaction_type=side, price=float(price), order_reference_id=_reference())
        order_id = (res or {}).get("groww_order_id")
        if not order_id:
            raise BrokerError("Groww returned no order id")
        return str(order_id)

    def place_buy(self, inst, qty, price, amo):
        if amo:
            raise BrokerError("Groww's API takes no after-market orders")
        return self._order(inst, "BUY", qty, price)

    def place_sell(self, inst, qty, price):
        return self._order(inst, "SELL", qty, price)

    def order_state(self, order_id):
        try:
            d = self.api.get_order_detail(segment="CASH", groww_order_id=order_id) or {}
        except Exception as exc:
            log.debug("order %s not found: %s", order_id, exc)
            return None
        status = str(d.get("order_status", "")).upper()
        mapped = (DONE if status in FILLED else REJECTED if status in ("REJECTED", "FAILED")
                  else CANCELLED if status == "CANCELLED" else OPEN)
        return {"status": mapped, "filled": int(float(d.get("filled_quantity") or 0)),
                "avg": float(d.get("average_fill_price") or d.get("price") or 0), "message": d.get("remark") or ""}

    def place_exits(self, inst, qty, stop, target, ltp):
        legs = [("DOWN", to_tick(stop, inst.tick), self.stop_limit(stop, inst.tick)),
                ("UP", to_tick(target, inst.tick), to_tick(target, inst.tick))]
        ids = []
        try:
            for direction, trigger, price in legs:
                res = self._call(self.api.create_smart_order, smart_order_type="GTT", segment="CASH",
                                 trading_symbol=inst.tradingsymbol, quantity=int(qty), product_type="CNC",
                                 exchange=inst.exchange, duration="DAY", reference_id=_reference(),
                                 trigger_price=f"{trigger:.2f}", trigger_direction=direction,
                                 order={"order_type": "LIMIT", "price": f"{price:.2f}", "transaction_type": "SELL"})
                smart_id = (res or {}).get("smart_order_id")
                if not smart_id:
                    raise BrokerError("Groww returned no smart order id")
                ids.append(str(smart_id))
        except BrokerError:
            self.cancel_exits(inst, ids)  # never leave half a pair behind
            raise
        return ids

    def exit_status(self, inst, exit_ids):
        states = [str((self._call(self.api.get_smart_order, segment="CASH", smart_order_type="GTT",
                                  smart_order_id=i) or {}).get("status", "")).upper() for i in exit_ids]
        if any(s in ("TRIGGERED", "COMPLETED") for s in states):
            return "triggered"
        return "active" if states and all(s == "ACTIVE" for s in states) else "inactive"

    def cancel_exits(self, inst, exit_ids):
        for i in exit_ids:
            try:
                self.api.cancel_smart_order(segment="CASH", smart_order_type="GTT", smart_order_id=i)
            except Exception:
                pass

    def last_sell_price(self, inst):
        try:
            orders = (self.api.get_order_list(segment="CASH") or {}).get("order_list") or []
        except Exception:
            return None
        fills = [o for o in orders if o.get("trading_symbol") == inst.tradingsymbol
                 and o.get("transaction_type") == "SELL" and str(o.get("order_status")).upper() in FILLED]
        return float(fills[0].get("average_fill_price") or 0) or None if fills else None
