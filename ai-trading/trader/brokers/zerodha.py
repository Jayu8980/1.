"""Zerodha via Kite Connect (pip install kiteconnect).

Create an app at https://developers.kite.trade and set KITE_API_KEY and KITE_API_SECRET.
Kite sessions end every morning at 06:00 IST, so log in once a day with
``python -m trader login --broker zerodha`` (or set KITE_ACCESS_TOKEN yourself).
Each position is protected by one GTT OCO: a stop-loss leg and a target leg on Zerodha's servers.
"""
from __future__ import annotations

import json
import logging
import os
from datetime import date
from urllib.parse import parse_qs, urlparse

from .base import BrokerError
from .india import CANCELLED, DONE, OPEN, REJECTED, IndianBroker, Instrument, add_holding, now_ist, split_symbol, to_tick

log = logging.getLogger("trader.brokers.zerodha")


class ZerodhaBroker(IndianBroker):
    name = "zerodha"

    def _kite(self):
        try:
            from kiteconnect import KiteConnect
        except ImportError as exc:
            raise BrokerError("pip install kiteconnect to trade through Zerodha") from exc
        key = os.environ.get("KITE_API_KEY")
        if not key:
            raise BrokerError("set KITE_API_KEY and KITE_API_SECRET from your Kite Connect app")
        return KiteConnect(api_key=key)

    def connect(self):
        kite = self._kite()
        token = os.environ.get("KITE_ACCESS_TOKEN") or self.load_token()
        if not token:
            raise BrokerError("no Kite session today: run `python -m trader login --broker zerodha`")
        kite.set_access_token(token)
        return kite

    def login(self, prompt=input) -> str:
        """Kite's daily browser login: open the link, log in, paste back the page address."""
        secret = os.environ.get("KITE_API_SECRET")
        if not secret:
            raise BrokerError("set KITE_API_SECRET from your Kite Connect app")
        kite = self._kite()
        print("1. Open this link and log in to Kite:\n   " + kite.login_url())
        print("2. Paste the address of the page you land on (or just its request_token):")
        raw = prompt("> ").strip()
        request_token = parse_qs(urlparse(raw).query).get("request_token", [raw])[0]
        try:
            session = kite.generate_session(request_token, api_secret=secret)
        except Exception as exc:
            raise BrokerError(f"Kite login failed: {exc}") from exc
        self.save_token(session["access_token"], user=session.get("user_id"))
        self._api = kite
        return f"logged in to Zerodha as {session.get('user_id', '?')}; session valid until 06:00 IST"

    # ---- primitives --------------------------------------------------------------------------
    def instrument(self, symbol):
        exchange, base = split_symbol(symbol)
        return Instrument(symbol, exchange, base, tick=self._ticks(exchange).get(base, 0.0))

    def _ticks(self, exchange: str) -> dict:
        path = self.state_dir / f"zerodha_ticks_{exchange}.json"
        if path.exists() and date.fromtimestamp(path.stat().st_mtime) == date.today():
            return json.loads(path.read_text())
        try:
            rows = self.api.instruments(exchange)
        except Exception as exc:  # the safe price grid still works without exact ticks
            log.debug("instrument list unavailable: %s", exc)
            return {}
        ticks = {r["tradingsymbol"]: float(r["tick_size"]) for r in rows if r.get("instrument_type") == "EQ"}
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(ticks))
        return ticks

    def funds(self):
        equity = self._call(self.api.margins, "equity")
        values = [v for v in (equity.get("net"), (equity.get("available") or {}).get("live_balance"))
                  if isinstance(v, (int, float))]
        return float(min(values)) if values else 0.0

    def holdings(self):
        out = {}
        for h in self._call(self.api.holdings) or []:
            add_holding(out, h["tradingsymbol"], int(h.get("quantity") or 0) + int(h.get("t1_quantity") or 0),
                        h.get("average_price"), h.get("last_price"))
        for p in (self._call(self.api.positions) or {}).get("net", []):
            if p.get("product") == "CNC":
                add_holding(out, p["tradingsymbol"], int(p.get("quantity") or 0), p.get("average_price"),
                            p.get("last_price"))
        return out

    def ltp(self, inst):
        key = f"{inst.exchange}:{inst.tradingsymbol}"
        data = self._call(self.api.ltp, key)
        try:
            return float(data[key]["last_price"])
        except (KeyError, TypeError, ValueError) as exc:
            raise BrokerError(f"no price for {key}") from exc

    def _order(self, inst, side, qty, price, amo=False):
        params = {"variety": "amo" if amo else "regular", "exchange": inst.exchange,
                  "tradingsymbol": inst.tradingsymbol, "transaction_type": side, "quantity": int(qty),
                  "product": "CNC", "order_type": "LIMIT", "price": price, "validity": "DAY", "tag": "aidesk"}
        if self.ex.algo_id:
            params["algo_id"] = self.ex.algo_id
        return str(self._call(self.api.place_order, **params))

    def place_buy(self, inst, qty, price, amo):
        return self._order(inst, "BUY", qty, price, amo)

    def place_sell(self, inst, qty, price):
        return self._order(inst, "SELL", qty, price)

    def order_state(self, order_id):
        try:
            history = self.api.order_history(order_id)
        except Exception as exc:
            log.debug("order %s not found: %s", order_id, exc)
            return None
        if not history:
            return None
        last = history[-1]
        status = str(last.get("status", "")).upper()
        mapped = {"COMPLETE": DONE, "REJECTED": REJECTED, "CANCELLED": CANCELLED}.get(status, OPEN)
        return {"status": mapped, "filled": int(last.get("filled_quantity") or 0),
                "avg": float(last.get("average_price") or 0), "message": last.get("status_message") or ""}

    def place_exits(self, inst, qty, stop, target, ltp):
        leg = {"transaction_type": "SELL", "quantity": int(qty), "order_type": "LIMIT", "product": "CNC"}
        target_px = to_tick(target, inst.tick)
        res = self._call(self.api.place_gtt, trigger_type="two-leg", tradingsymbol=inst.tradingsymbol,
                         exchange=inst.exchange, trigger_values=[to_tick(stop, inst.tick), target_px],
                         last_price=float(ltp),
                         orders=[{**leg, "price": self.stop_limit(stop, inst.tick)}, {**leg, "price": target_px}])
        return [str(res["trigger_id"])]

    def exit_status(self, inst, exit_ids):
        states = [str(self._call(self.api.get_gtt, int(i)).get("status", "")).lower() for i in exit_ids]
        if "triggered" in states:
            return "triggered"
        return "active" if states and all(s == "active" for s in states) else "inactive"

    def cancel_exits(self, inst, exit_ids):
        for i in exit_ids:
            try:
                self.api.delete_gtt(int(i))
            except Exception:
                pass  # already triggered or deleted

    def last_sell_price(self, inst):
        try:
            orders = self.api.orders() or []
        except Exception:
            return None
        fills = [o for o in orders if o.get("tradingsymbol") == inst.tradingsymbol
                 and o.get("transaction_type") == "SELL" and str(o.get("status")).upper() == "COMPLETE"]
        return float(fills[-1]["average_price"]) if fills else None
