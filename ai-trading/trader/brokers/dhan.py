"""Dhan via DhanHQ v2 (pip install dhanhq).

Set DHAN_CLIENT_ID plus either DHAN_ACCESS_TOKEN (generated on web.dhan.co) or DHAN_PIN and
DHAN_TOTP_SECRET, which let the desk create a fresh token by itself every day. Each position is
protected by two Forever Orders (a stop-loss and a target, each a single trigger); the monitor
cancels the leftover one once the other has fired.
"""
from __future__ import annotations

import io
import logging
import os
from datetime import date

import pandas as pd

from .base import BrokerError
from .india import CANCELLED, DONE, OPEN, REJECTED, IndianBroker, Instrument, add_holding, split_symbol, to_tick, totp

log = logging.getLogger("trader.brokers.dhan")
SEGMENT = {"NSE": "NSE_EQ", "BSE": "BSE_EQ"}
SCRIP_MASTER = "https://images.dhan.co/api-data/api-scrip-master.csv"
_EMPTY_HINTS = ("no holding", "no position", "no data", "no record", "dh-1111")


class DhanBroker(IndianBroker):
    name = "dhan"

    def connect(self):
        try:
            from dhanhq import DhanContext, dhanhq
        except ImportError as exc:
            raise BrokerError("pip install dhanhq to trade through Dhan") from exc
        client_id = os.environ.get("DHAN_CLIENT_ID")
        if not client_id:
            raise BrokerError("set DHAN_CLIENT_ID to trade through Dhan")
        token = os.environ.get("DHAN_ACCESS_TOKEN") or self.load_token() or self._new_token(client_id)
        return dhanhq(DhanContext(client_id, token))

    def _new_token(self, client_id: str) -> str:
        pin, secret = os.environ.get("DHAN_PIN"), os.environ.get("DHAN_TOTP_SECRET")
        if not (pin and secret):
            raise BrokerError("set DHAN_ACCESS_TOKEN, or DHAN_PIN and DHAN_TOTP_SECRET for automatic login")
        from dhanhq import DhanLogin

        try:
            res = DhanLogin(client_id).generate_token(pin, totp(secret))
        except Exception as exc:
            raise BrokerError(f"Dhan login failed: {exc}") from exc
        token = res.get("accessToken") or res.get("access_token") or (res.get("data") or {}).get("accessToken")
        if not token:
            raise BrokerError("Dhan login returned no access token")
        self.save_token(token)
        return token

    def _res(self, res, empty_ok: bool = False):
        """Dhan answers {"status": "success"|"failure", "remarks": ..., "data": ...} instead of raising."""
        if isinstance(res, dict) and res.get("status") == "success":
            return res.get("data")
        remarks = res.get("remarks") if isinstance(res, dict) else res
        if empty_ok and any(h in str(remarks).lower() for h in _EMPTY_HINTS):
            return []
        raise BrokerError(f"Dhan: {remarks or 'request failed'}")

    # ---- primitives --------------------------------------------------------------------------
    def _scrips(self) -> pd.DataFrame:
        path = self.state_dir / "dhan_scrip_master.csv"
        if not (path.exists() and date.fromtimestamp(path.stat().st_mtime) == date.today()):
            import requests

            try:
                r = requests.get(SCRIP_MASTER, timeout=60)
                r.raise_for_status()
            except Exception as exc:
                if not path.exists():
                    raise BrokerError(f"could not download Dhan's instrument list: {exc}") from exc
            else:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(r.content)
        if getattr(self, "_scrip_df", None) is None:
            self._scrip_df = pd.read_csv(path, dtype=str, low_memory=False)
        return self._scrip_df

    def instrument(self, symbol):
        exchange, base = split_symbol(symbol)
        df = self._scrips()
        rows = df[(df["SEM_EXM_EXCH_ID"] == exchange) & (df["SEM_TRADING_SYMBOL"] == base)]
        if "SEM_INSTRUMENT_NAME" in rows:
            rows = rows[rows["SEM_INSTRUMENT_NAME"] == "EQUITY"]
        if exchange == "NSE" and "SEM_SERIES" in rows and (rows["SEM_SERIES"] == "EQ").any():
            rows = rows[rows["SEM_SERIES"] == "EQ"]
        if rows.empty:
            raise BrokerError(f"{base} not found on {exchange} in Dhan's instrument list")
        row = rows.iloc[0]
        tick = pd.to_numeric(row.get("SEM_TICK_SIZE"), errors="coerce")
        tick = 0.0 if pd.isna(tick) else float(tick) / 100 if tick >= 1 else float(tick)  # listed in paise
        return Instrument(symbol, exchange, base, str(row["SEM_SMST_SECURITY_ID"]), tick)

    def funds(self):
        data = self._res(self._call(self.api.get_fund_limits)) or {}
        return float(data.get("availabelBalance", data.get("availableBalance", 0)) or 0)  # sic: API spelling

    def holdings(self):
        out = {}
        for h in self._res(self._call(self.api.get_holdings), empty_ok=True) or []:
            add_holding(out, h["tradingSymbol"], int(h.get("totalQty") or 0), h.get("avgCostPrice"))
        for p in self._res(self._call(self.api.get_positions), empty_ok=True) or []:
            if str(p.get("productType", "")).upper() == "CNC":
                add_holding(out, p["tradingSymbol"], int(p.get("netQty") or 0), p.get("buyAvg"))
        return out

    def ltp(self, inst):
        segment = SEGMENT[inst.exchange]
        data = self._res(self._call(self.api.ticker_data, {segment: [int(inst.token)]})) or {}
        node = (data.get("data") if isinstance(data.get("data"), dict) else data).get(segment, {})
        try:
            return float(node[str(inst.token)]["last_price"])
        except (KeyError, TypeError, ValueError) as exc:
            raise BrokerError(f"no price for {inst.tradingsymbol} (market-data access may be needed)") from exc

    def _order(self, inst, side, qty, price, amo=False):
        res = self._call(self.api.place_order, security_id=str(inst.token), exchange_segment=SEGMENT[inst.exchange],
                         transaction_type=side, quantity=int(qty), order_type="LIMIT", product_type="CNC",
                         price=float(price), after_market_order=amo, validity="DAY", amo_time="OPEN", tag="aidesk")
        return str(self._res(res)["orderId"])

    def place_buy(self, inst, qty, price, amo):
        return self._order(inst, "BUY", qty, price, amo)

    def place_sell(self, inst, qty, price):
        return self._order(inst, "SELL", qty, price)

    def order_state(self, order_id):
        res = self._call(self.api.get_order_by_id, order_id)
        if not (isinstance(res, dict) and res.get("status") == "success"):
            return None
        data = res.get("data")
        o = (data[0] if data else {}) if isinstance(data, list) else (data or {})
        status = str(o.get("orderStatus", "")).upper()
        mapped = {"TRADED": DONE, "REJECTED": REJECTED, "CANCELLED": CANCELLED, "EXPIRED": CANCELLED}.get(status, OPEN)
        filled = int(o.get("filledQty") or (o.get("quantity") if status == "TRADED" else 0) or 0)
        return {"status": mapped, "filled": filled, "avg": float(o.get("averageTradedPrice") or o.get("price") or 0),
                "message": o.get("omsErrorDescription") or ""}

    def place_exits(self, inst, qty, stop, target, ltp):
        legs = [(to_tick(stop, inst.tick), self.stop_limit(stop, inst.tick)),
                (to_tick(target, inst.tick), to_tick(target, inst.tick))]
        ids = []
        try:
            for trigger, price in legs:
                res = self._call(self.api.place_forever, security_id=str(inst.token),
                                 exchange_segment=SEGMENT[inst.exchange], transaction_type="SELL", product_type="CNC",
                                 order_type="LIMIT", quantity=int(qty), price=float(price),
                                 trigger_Price=float(trigger), order_flag="SINGLE", validity="DAY")
                ids.append(str(self._res(res)["orderId"]))
        except BrokerError:
            self.cancel_exits(inst, ids)  # never leave half a pair behind
            raise
        return ids

    def exit_status(self, inst, exit_ids):
        rows = self._res(self._call(self.api.get_forever), empty_ok=True) or []
        by_id = {str(r.get("orderId")): str(r.get("orderStatus", "")).upper() for r in rows}
        states = [by_id.get(str(i), "") for i in exit_ids]
        if any(s in ("TRIGGERED", "TRADED") for s in states):
            return "triggered"
        return "active" if states and all(s in ("PENDING", "CONFIRM", "TRANSIT") for s in states) else "inactive"

    def cancel_exits(self, inst, exit_ids):
        for i in exit_ids:
            try:
                self.api.cancel_forever(i)
            except Exception:
                pass

    def last_sell_price(self, inst):
        try:
            orders = self._res(self._call(self.api.get_order_list), empty_ok=True) or []
        except BrokerError:
            return None
        fills = [o for o in orders if str(o.get("securityId")) == str(inst.token)
                 and o.get("transactionType") == "SELL" and str(o.get("orderStatus")).upper() == "TRADED"]
        return float(fills[-1].get("averageTradedPrice") or fills[-1].get("price")) if fills else None
