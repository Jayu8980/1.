"""Crypto exchanges through CCXT (Binance, Bybit, Kraken, Coinbase, ...).

Spot, long only. Credentials come from CCXT_API_KEY and CCXT_SECRET. Paper mode uses the
exchange's sandbox/testnet. Spot markets have no bracket orders, so the stop, target and time exit
are enforced by the monitor: schedule ``python -m trader monitor`` every few minutes.
"""
from __future__ import annotations

import os
from datetime import date
from pathlib import Path

from .base import Broker, BrokerError, PositionBook


class CCXTBroker(Broker):
    name = "ccxt"

    def __init__(self, exchange: str, sandbox: bool, quote: str, state_dir: str) -> None:
        try:
            import ccxt
        except ImportError as exc:
            raise BrokerError("pip install ccxt to trade on crypto exchanges") from exc
        key, secret = os.environ.get("CCXT_API_KEY"), os.environ.get("CCXT_SECRET")
        if not key or not secret:
            raise BrokerError("set CCXT_API_KEY and CCXT_SECRET to use a crypto exchange")
        self.ex = getattr(ccxt, exchange)({"apiKey": key, "secret": secret, "enableRateLimit": True})
        if sandbox:
            self.ex.set_sandbox_mode(True)
        self.live = not sandbox
        self.quote = quote
        self.book = PositionBook(Path(state_dir) / f"ccxt_{exchange}_{'live' if self.live else 'sandbox'}_book.json")

    def market(self, symbol: str) -> str:
        return f"{symbol.split('-')[0].split('/')[0]}/{self.quote}"  # BTC-USD -> BTC/USDT

    def price(self, symbol: str) -> float:
        return float(self.ex.fetch_ticker(self.market(symbol))["last"])

    def account(self) -> dict:
        balance = self.ex.fetch_balance()
        cash = float((balance.get("free") or {}).get(self.quote) or 0)
        equity = float((balance.get("total") or {}).get(self.quote) or 0)
        for symbol, info in self.book.all().items():  # only positions this desk opened
            try:
                equity += info["qty"] * self.price(symbol)
            except Exception:
                equity += info["qty"] * info["entry"]
        return {"equity": equity, "cash": cash}

    def positions(self) -> list[dict]:
        return [{"symbol": s, "status": "open", **info} for s, info in self.book.all().items()]

    def submit(self, plan: dict) -> dict:
        if plan["side"] != "long":
            raise BrokerError("the CCXT adapter trades spot markets, long only")
        market = self.market(plan["symbol"])
        self.ex.load_markets()
        amount = float(self.ex.amount_to_precision(market, plan["qty"]))
        if amount <= 0:
            raise BrokerError("quantity is below the exchange's minimum")
        order = self.ex.create_order(market, "market", "buy", amount)
        fill = float(order.get("average") or order.get("price") or plan["entry_ref"])
        filled = float(order.get("filled") or amount)
        self.book.put(plan["symbol"], {"side": "long", "qty": filled, "entry": fill, "stop": plan["stop"],
                                       "initial_stop": plan["stop"], "target": plan["target"],
                                       "max_hold": plan["max_hold"], "opened": date.today().isoformat()})
        return {"id": order.get("id"), "status": order.get("status") or "submitted", "fill_price": fill}

    def manage(self, frames: dict, trail: bool = True) -> list[dict]:
        closed = []
        for symbol, info in self.book.all().items():
            price = self.price(symbol)
            reason = "stop" if price <= info["stop"] else "target" if price >= info["target"] else None
            if reason is None and (date.today() - date.fromisoformat(info["opened"])).days >= info["max_hold"]:
                reason = "time"
            if reason is None:
                if trail and price - info["entry"] >= info["entry"] - info["initial_stop"] and info["stop"] < info["entry"]:
                    self.book.put(symbol, {**info, "stop": info["entry"]})
                continue
            market = self.market(symbol)
            amount = float(self.ex.amount_to_precision(market, info["qty"]))
            order = self.ex.create_order(market, "market", "sell", amount)
            exit_px = float(order.get("average") or price)
            self.book.remove(symbol)
            risk = (info["entry"] - info["initial_stop"]) * info["qty"]
            pnl = (exit_px - info["entry"]) * info["qty"]
            closed.append({"symbol": symbol, "side": "long", "qty": info["qty"], "entry": info["entry"],
                           "exit": exit_px, "pnl": pnl, "r_multiple": pnl / risk if risk else 0.0,
                           "reason": reason, "status": "closed"})
        return closed
