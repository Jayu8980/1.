"""Alpaca (US stocks, ETFs and crypto) over its REST API.

Paper trading uses https://paper-api.alpaca.markets, live uses https://api.alpaca.markets.
Credentials come from APCA_API_KEY_ID and APCA_API_SECRET_KEY. Stock entries are bracket
orders, so the stop-loss and take-profit live on Alpaca's servers; Alpaca has no bracket orders
for crypto, so crypto exits are handled by the monitor.
"""
from __future__ import annotations

import os
import time
from datetime import date
from pathlib import Path
from urllib.parse import quote

import numpy as np

from .base import Broker, BrokerError, PositionBook


def to_alpaca(symbol: str) -> str:
    if symbol.endswith("-USD"):
        return symbol.replace("-USD", "/USD")      # BTC-USD -> BTC/USD
    return symbol.replace("-", ".")                # BRK-B -> BRK.B


def from_alpaca(symbol: str) -> str:
    if symbol.endswith("/USD"):
        return symbol.replace("/USD", "-USD")
    if symbol.endswith("USD") and len(symbol) > 5 and "/" not in symbol:  # positions list BTCUSD
        return symbol[:-3] + "-USD"
    return symbol.replace(".", "-")


def _price(x: float) -> str:
    return f"{x:.2f}" if x >= 1 else f"{x:.4f}"


class AlpacaBroker(Broker):
    name = "alpaca"

    def __init__(self, paper: bool, state_dir: str) -> None:
        import requests

        key, secret = os.environ.get("APCA_API_KEY_ID"), os.environ.get("APCA_API_SECRET_KEY")
        if not key or not secret:
            raise BrokerError("set APCA_API_KEY_ID and APCA_API_SECRET_KEY to use Alpaca")
        self.live = not paper
        self.base = "https://paper-api.alpaca.markets" if paper else "https://api.alpaca.markets"
        self.http = requests.Session()
        self.http.headers.update({"APCA-API-KEY-ID": key, "APCA-API-SECRET-KEY": secret})
        self.book = PositionBook(Path(state_dir) / f"alpaca_{'paper' if paper else 'live'}_book.json")

    def _call(self, method: str, path: str, **kwargs):
        r = self.http.request(method, self.base + path, timeout=20, **kwargs)
        if r.status_code >= 400:
            raise BrokerError(f"Alpaca {method} {path} -> {r.status_code}: {r.text[:300]}")
        return r.json() if r.content else {}

    def account(self) -> dict:
        a = self._call("GET", "/v2/account")
        return {"equity": float(a["equity"]), "cash": float(a["cash"]), "buying_power": float(a["buying_power"])}

    def positions(self) -> list[dict]:
        book = self.book.all()
        out = []
        for p in self._call("GET", "/v2/positions"):
            symbol = from_alpaca(p["symbol"])
            info = book.get(symbol, {})
            out.append({"symbol": symbol, "side": p["side"], "qty": abs(float(p["qty"])),
                        "entry": float(p["avg_entry_price"]), "last_price": float(p["current_price"]),
                        "stop": info.get("stop"), "target": info.get("target"),
                        "max_hold": info.get("max_hold"), "opened": info.get("opened"), "status": "open"})
        for symbol, info in book.items():  # orders accepted but not filled yet
            if symbol not in {p["symbol"] for p in out}:
                out.append({"symbol": symbol, "status": "pending", **info})
        return out

    def submit(self, plan: dict) -> dict:
        symbol = to_alpaca(plan["symbol"])
        crypto = "/" in symbol
        body = {"symbol": symbol, "qty": f"{plan['qty']:g}", "type": "market",
                "side": "buy" if plan["side"] == "long" else "sell",
                "time_in_force": "gtc" if crypto else "day",
                "client_order_id": f"desk-{plan['symbol']}-{int(time.time())}"}
        if not crypto:
            body.update(order_class="bracket", take_profit={"limit_price": _price(plan["target"])},
                        stop_loss={"stop_price": _price(plan["stop"])})
        order = self._call("POST", "/v2/orders", json=body)
        self.book.put(plan["symbol"], {"side": plan["side"], "qty": plan["qty"], "entry_ref": plan["entry_ref"],
                                       "stop": plan["stop"], "target": plan["target"],
                                       "max_hold": plan["max_hold"], "opened": date.today().isoformat(),
                                       "software_exits": crypto})
        return {"id": order.get("id"), "status": order.get("status", "submitted")}

    def close(self, symbol: str) -> None:
        alpaca_symbol = to_alpaca(symbol)
        for order in self._call("GET", "/v2/orders", params={"status": "open", "symbols": alpaca_symbol}):
            self._call("DELETE", f"/v2/orders/{order['id']}")  # free the qty held by bracket legs
        self._call("DELETE", f"/v2/positions/{quote(alpaca_symbol, safe='')}")
        self.book.remove(symbol)

    def manage(self, frames: dict, trail: bool = True) -> list[dict]:
        closed = []
        live = {p["symbol"]: p for p in self.positions() if p.get("status") == "open"}
        for symbol, info in self.book.all().items():
            p = live.get(symbol)
            if p is None:
                if (date.today() - date.fromisoformat(info["opened"])).days > 5:
                    self.book.remove(symbol)  # bracket exit already happened or the order expired
                continue
            d = 1 if info["side"] == "long" else -1
            price = p["last_price"]
            reason = None
            if info.get("software_exits"):
                if d * (price - info["stop"]) <= 0:
                    reason = "stop"
                elif d * (price - info["target"]) >= 0:
                    reason = "target"
            held = (date.today() - date.fromisoformat(info["opened"])).days if info.get("software_exits") \
                else int(np.busday_count(info["opened"], date.today().isoformat()))
            if reason is None and held >= info["max_hold"]:
                reason = "time"
            if reason:
                self.close(symbol)
                closed.append({"symbol": symbol, "side": info["side"], "qty": p["qty"], "entry": p["entry"],
                               "exit": price, "pnl": d * (price - p["entry"]) * p["qty"], "reason": reason,
                               "status": "closed"})
        return closed
