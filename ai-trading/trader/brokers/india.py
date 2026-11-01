"""Shared machinery for Indian brokers: NSE/BSE cash market, delivery (CNC), long only.

Each adapter (Zerodha, Angel One, Dhan, Groww) only implements a few primitives: look up an
instrument, read funds and holdings, place/inspect orders, and place/inspect/cancel protective
exit orders. This class turns them into the full trade lifecycle:

    queued  -> entry not sent yet (market closed and the broker takes no after-market orders)
    pending -> entry order sent: a regular order during the session, an AMO after hours
    open    -> filled; a stop-loss and a target order wait on the broker's servers
    closing -> a time exit or emergency stop sent a sell order

Entries and exits are LIMIT orders placed a little beyond the last price, so they fill like market
orders but can never fill at an absurd price. Without --live an adapter runs as a dry run: it reads
the real account but sends nothing, and reports the exact orders it would have placed.
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import logging
import math
import struct
import time
from dataclasses import asdict, dataclass
from datetime import datetime, time as dtime, timedelta, timezone
from pathlib import Path

import numpy as np

from .base import Broker, BrokerError, PositionBook

log = logging.getLogger("trader.brokers.india")

IST = timezone(timedelta(hours=5, minutes=30))
SESSION_OPEN, SESSION_CLOSE = dtime(9, 15), dtime(15, 30)
DONE, OPEN, REJECTED, CANCELLED = "complete", "open", "rejected", "cancelled"


def now_ist() -> datetime:
    return datetime.now(IST)


def market_open(at: datetime | None = None) -> bool:
    """NSE/BSE regular session, 09:15-15:30 IST on weekdays (exchange holidays are not known)."""
    at = (at or now_ist()).astimezone(IST)
    return at.weekday() < 5 and SESSION_OPEN <= at.time() <= SESSION_CLOSE


def split_symbol(symbol: str) -> tuple[str, str]:
    """RELIANCE.NS -> ("NSE", "RELIANCE"); TCS.BO -> ("BSE", "TCS")."""
    s = symbol.upper()
    if s.endswith(".NS"):
        return "NSE", s[:-3]
    if s.endswith(".BO"):
        return "BSE", s[:-3]
    raise BrokerError(f"{symbol} is not an NSE (.NS) or BSE (.BO) share")


def safe_tick(price: float) -> float:
    """A price grid that is valid whatever tick size the exchange uses in this price band.

    Tick sizes form a chain where each divides the next (0.01, 0.05, 0.10, 0.50, 1, 5), so a
    price on this grid is a multiple of any tick the band can have.
    """
    for limit, tick in ((1000, 0.05), (5000, 0.10), (10000, 0.50), (20000, 1.0)):
        if price < limit:
            return tick
    return 5.0


def to_tick(price: float, tick: float = 0.0, mode: str = "nearest") -> float:
    grid = max(tick or 0.0, safe_tick(price))
    steps = price / grid
    if mode == "up":
        steps = math.ceil(steps - 1e-9)
    elif mode == "down":
        steps = math.floor(steps + 1e-9)
    else:
        steps = round(steps)
    return round(steps * grid, 2)


def totp(secret: str, at: float | None = None, digits: int = 6, step: int = 30) -> str:
    """RFC 6238 one-time password, the 6-digit code an authenticator app shows."""
    clean = secret.replace(" ", "").upper()
    key = base64.b32decode(clean + "=" * (-len(clean) % 8))
    counter = int((time.time() if at is None else at) // step)
    digest = hmac.new(key, struct.pack(">Q", counter), hashlib.sha1).digest()
    offset = digest[-1] & 0x0F
    code = (struct.unpack(">I", digest[offset:offset + 4])[0] & 0x7FFFFFFF) % 10 ** digits
    return str(code).zfill(digits)


@dataclass
class Instrument:
    symbol: str          # desk symbol, e.g. RELIANCE.NS
    exchange: str        # NSE or BSE
    tradingsymbol: str   # the broker's own name for it (RELIANCE, RELIANCE-EQ, ...)
    token: str = ""      # the broker's numeric id where it needs one
    tick: float = 0.0    # 0 = unknown; prices then use the safe grid


def add_holding(out: dict, base: str, qty: int, avg=None, ltp=None) -> None:
    """Merge a holding or a same-day delivery position into {base symbol: {qty, avg, ltp}}."""
    row = out.setdefault(base, {"qty": 0, "avg": None, "ltp": None})
    row["qty"] += int(qty)
    row["avg"] = row["avg"] or (float(avg) if avg not in (None, "") else None)
    row["ltp"] = row["ltp"] or (float(ltp) if ltp not in (None, "") else None)


class IndianBroker(Broker):
    name = "india"
    supports_amo = True  # accepts after-market orders that execute at the next open

    def __init__(self, ex, live: bool) -> None:
        self.ex = ex
        self.live = live
        self.state_dir = Path(ex.state_dir)
        self.book = PositionBook(self.state_dir / f"{self.name}_book.json")
        self._api = None
        self._frames: dict = {}

    # ---- primitives every adapter implements -------------------------------------------------
    def connect(self):
        """Log in and return the broker SDK client."""
        raise NotImplementedError

    def instrument(self, symbol: str) -> Instrument:
        raise NotImplementedError

    def funds(self) -> float:
        """Cash available for delivery buys."""
        raise NotImplementedError

    def holdings(self) -> dict[str, dict]:
        """All shares in the account (holdings plus today's delivery trades), by base symbol."""
        raise NotImplementedError

    def ltp(self, inst: Instrument) -> float:
        raise NotImplementedError

    def place_buy(self, inst: Instrument, qty: int, price: float, amo: bool) -> str:
        raise NotImplementedError

    def place_sell(self, inst: Instrument, qty: int, price: float) -> str:
        raise NotImplementedError

    def order_state(self, order_id: str) -> dict | None:
        """{"status": complete|open|rejected|cancelled, "filled", "avg", "message"}; None if unknown."""
        raise NotImplementedError

    def place_exits(self, inst: Instrument, qty: int, stop: float, target: float, ltp: float) -> list[str]:
        raise NotImplementedError

    def exit_status(self, inst: Instrument, exit_ids: list[str]) -> str:
        """"active" (all legs waiting), "triggered" (a leg fired) or "inactive" (gone or expired)."""
        raise NotImplementedError

    def cancel_exits(self, inst: Instrument, exit_ids: list[str]) -> None:
        raise NotImplementedError

    def last_sell_price(self, inst: Instrument) -> float | None:
        """Average price of today's latest completed sell, if the broker reports it."""
        return None

    # ---- helpers -----------------------------------------------------------------------------
    @property
    def api(self):
        if self._api is None:
            self._api = self.connect()
        return self._api

    def _call(self, fn, *args, **kwargs):
        """Call the SDK and turn whatever it raises into a BrokerError."""
        try:
            return fn(*args, **kwargs)
        except BrokerError:
            raise
        except Exception as exc:
            raise BrokerError(f"{self.name}: {exc}") from exc

    def stop_limit(self, stop: float, tick: float = 0.0) -> float:
        """Limit price of the stop-loss sell, far enough below the trigger to fill in a fast fall."""
        return to_tick(stop * (1 - self.ex.stop_limit_buffer_pct), tick, "down")

    def _ltp_or(self, inst: Instrument, default):
        try:
            return float(self.ltp(inst))
        except BrokerError:
            df = self._frames.get(inst.symbol)
            return float(df["Close"].iloc[-1]) if df is not None and len(df) else default

    def _token_path(self) -> Path:
        return self.state_dir / f"{self.name}_session.json"

    def load_token(self) -> str | None:
        """A saved session token, if it was created after the last 06:00 IST reset."""
        path = self._token_path()
        if not path.exists():
            return None
        saved = json.loads(path.read_text())
        created = datetime.fromisoformat(saved["created"])
        reset = created.replace(hour=6, minute=0, second=0, microsecond=0)
        if created >= reset:
            reset += timedelta(days=1)
        return saved.get("token") if now_ist() < reset else None

    def save_token(self, token: str, **extra) -> None:
        path = self._token_path()
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps({"token": token, "created": now_ist().isoformat(), **extra}))

    def login(self) -> str:
        acct = self.account()
        return f"connected: cash {acct['cash']:,.2f}, equity {acct['equity']:,.2f}"

    # ---- Broker interface -------------------------------------------------------------------
    def account(self) -> dict:
        cash = self.funds()
        value = sum(h["qty"] * (h["ltp"] or h["avg"] or 0) for h in self.holdings().values() if h["qty"] > 0)
        return {"equity": cash + value, "cash": cash}

    def positions(self) -> list[dict]:
        """Positions and orders this desk manages (not the rest of the account)."""
        return [{"symbol": s, **rec} for s, rec in self.book.all().items()]

    def held_symbols(self) -> set[str]:
        """Every share already in the account, so the desk never mixes with your own holdings."""
        return {f"{base}{suffix}" for base, h in self.holdings().items() if h["qty"] > 0
                for suffix in (".NS", ".BO")}

    def submit(self, plan: dict) -> dict:
        if plan["side"] != "long":
            raise BrokerError("delivery (CNC) trading is long only")
        if plan["symbol"] in self.book.all():
            raise BrokerError(f"{plan['symbol']}: the desk already has an order or position here")
        qty = int(plan["qty"])
        if qty < 1:
            raise BrokerError("quantity is below one share")
        inst = self.instrument(plan["symbol"])
        in_session = market_open()
        ref = self._ltp_or(inst, plan["entry_ref"]) if in_session else plan["entry_ref"]
        limit = to_tick(ref * (1 + self.ex.entry_buffer_pct), inst.tick, "up")
        if not plan["stop"] < limit < plan["target"]:
            raise BrokerError(f"limit {limit} is already outside the stop {plan['stop']} / target {plan['target']}")
        how = ("regular order" if in_session else "after-market order" if self.supports_amo
               else "queued until the market opens")
        text = (f"BUY {qty} {inst.exchange}:{inst.tradingsymbol} LIMIT {limit} CNC ({how}), then "
                f"stop-loss {plan['stop']} (sell limit {self.stop_limit(plan['stop'], inst.tick)}) "
                f"and target {plan['target']}")
        if not self.live:
            log.info("DRY RUN %s: %s", self.name, text)
            return {"id": "", "status": "dry-run", "reason": text}

        rec = {"status": "queued", "side": "long", "qty": qty, "entry_ref": plan["entry_ref"], "entry": None,
               "stop": plan["stop"], "initial_stop": plan["stop"], "target": plan["target"],
               "max_hold": plan["max_hold"], "setup": plan.get("setup"), "signal_date": plan.get("signal_date"),
               "instrument": asdict(inst), "entry_order_id": None, "exit_ids": [], "placed": None,
               "opened": None}
        if in_session or self.supports_amo:
            try:
                rec["entry_order_id"] = self.place_buy(inst, qty, limit, amo=not in_session)
                rec.update(status="pending", placed=now_ist().date().isoformat(), limit=limit)
            except BrokerError as exc:
                if in_session:
                    raise
                log.warning("%s: after-market order refused (%s); queued for the next session", plan["symbol"], exc)
        self.book.put(plan["symbol"], rec)
        if rec["status"] == "pending" and in_session:
            time.sleep(2)  # give the exchange a moment, then protect the fill straight away
            try:
                self._sync(plan["symbol"], rec, {})
            except BrokerError as exc:
                log.warning("%s: will finish on the next monitor run (%s)", plan["symbol"], exc)
        return {"id": rec["entry_order_id"] or "", "status": rec["status"], "reason": text}

    def manage(self, frames: dict, trail: bool = True) -> list[dict]:
        if not self.live or not self.book.all():
            return []
        self._frames = frames or {}
        try:
            held = self.holdings()
        except BrokerError as exc:  # never act on a partial picture of the account
            log.warning("%s: could not read holdings, skipping this run: %s", self.name, exc)
            return []
        events = []
        for symbol, rec in self.book.all().items():
            try:
                event = self._sync(symbol, rec, held, trail)
            except BrokerError as exc:
                log.warning("%s %s: %s", self.name, symbol, exc)
                continue
            if event:
                events.append(event)
        return events

    # ---- lifecycle ---------------------------------------------------------------------------
    def _sync(self, symbol: str, rec: dict, held: dict, trail: bool = True) -> dict | None:
        inst = Instrument(**rec["instrument"])
        today = now_ist().date().isoformat()
        session = market_open()

        if rec["status"] == "queued":
            if not session:
                return None
            ltp = self._ltp_or(inst, rec["entry_ref"])
            if not rec["stop"] < ltp < rec["target"]:
                self.book.remove(symbol)
                return self._event(symbol, rec, "cancelled", "price moved beyond the stop or target before entry")
            limit = to_tick(ltp * (1 + self.ex.entry_buffer_pct), inst.tick, "up")
            rec.update(status="pending", entry_order_id=self.place_buy(inst, rec["qty"], limit, amo=False),
                       placed=today, limit=limit)
            self.book.put(symbol, rec)

        if rec["status"] == "pending":
            o = self.order_state(rec["entry_order_id"])
            if o is None:  # gone from the order book: the day ended without a fill
                if rec["placed"] and rec["placed"] < today:
                    self.book.remove(symbol)
                    return self._event(symbol, rec, "cancelled", "entry order expired")
                return None
            if o["status"] == OPEN:
                return None
            if o["filled"] <= 0:
                self.book.remove(symbol)
                return self._event(symbol, rec, "cancelled", o["message"] or f"entry order {o['status']}")
            fill = float(o["avg"] or rec.get("limit") or rec["entry_ref"])
            shift = fill - rec["entry_ref"]  # re-anchor to the fill, as the backtests do
            rec.update(status="open", qty=int(o["filled"]), opened=today, entry=fill,
                       stop=to_tick(rec["stop"] + shift, inst.tick, "down"),
                       target=to_tick(rec["target"] + shift, inst.tick))
            rec["initial_stop"] = rec["stop"]
            self.book.put(symbol, rec)
            self._open_position(symbol, rec, inst, int(o["filled"]), today, session, trail=False)
            protected = ("stop-loss and target placed" if rec.get("exit_ids")
                         else "exiting: price already past a level" if rec["status"] == "closing"
                         else "exits follow on the next monitor run")
            return self._event(symbol, rec, "filled", f"bought {rec['qty']} at {rec['entry']:.2f}; {protected}")

        if rec["status"] == "open":
            base = split_symbol(symbol)[1]
            return self._open_position(symbol, rec, inst, held.get(base, {}).get("qty", 0), today, session, trail)

        if rec["status"] == "closing":
            o = self.order_state(rec["exit_order_id"])
            if o is None or o["status"] == OPEN:
                return None
            if o["filled"] > 0:
                self.book.remove(symbol)
                return self._closed(symbol, rec, float(o["avg"] or rec["stop"]), rec.get("exit_reason", "exit"), today)
            rec.update(status="open", exit_order_id=None)  # sell refused: protect again and retry
            self.book.put(symbol, rec)
            return self._event(symbol, rec, "error", f"exit order {o['status']}: {o['message']}")
        return None

    def _protect(self, symbol: str, rec: dict, inst: Instrument) -> bool:
        """Place the stop-loss and target at the broker. False if the price is already past one."""
        ltp = self._ltp_or(inst, rec["entry"])
        if not rec["stop"] < ltp < rec["target"]:
            return False
        rec["exit_ids"] = self.place_exits(inst, rec["qty"], rec["stop"], rec["target"], ltp)
        rec.pop("triggered_on", None)
        self.book.put(symbol, rec)
        return True

    def _open_position(self, symbol, rec, inst, qty_now, today, session, trail) -> dict | None:
        exit_ids = rec.get("exit_ids") or []
        status = self.exit_status(inst, exit_ids) if exit_ids else "inactive"
        if qty_now <= 0:
            if status == "active":  # never cancel live protection on a holdings glitch
                log.warning("%s: no shares in holdings but its exit orders are active; leaving them", symbol)
                return None
            self.cancel_exits(inst, exit_ids)  # the other leg must not sell anything later
            price = self.last_sell_price(inst)
            if price is None:
                ltp = self._ltp_or(inst, rec["stop"])
                price = rec["target"] if ltp >= rec["target"] else rec["stop"]
            reason = "target" if price >= rec["target"] * 0.995 else "stop" if price <= rec["stop"] * 1.005 else "sold"
            self.book.remove(symbol)
            return self._closed(symbol, rec, price, reason, today)

        ltp = self._ltp_or(inst, None)
        reason = None
        if status == "triggered":  # a protective sell is working at the exchange
            rec.setdefault("triggered_on", today)
            self.book.put(symbol, rec)
            if not (session and rec["triggered_on"] < today):
                return None
            reason = "stop (protective order did not fill)"
        elif session and int(np.busday_count(rec["opened"], today)) >= rec["max_hold"]:
            reason = "time"
        elif status == "inactive":
            if self._protect(symbol, rec, inst):
                return None
            if not (session and ltp is not None):
                return None  # wait for the session to exit at a real price
            reason = "target" if ltp >= rec["target"] else "stop"
        elif trail and not rec.get("trailed") and ltp is not None \
                and ltp - rec["entry"] >= rec["entry"] - rec["initial_stop"]:
            self.cancel_exits(inst, exit_ids)
            rec.update(stop=max(rec["stop"], to_tick(rec["entry"], inst.tick, "down")), trailed=True, exit_ids=[])
            self.book.put(symbol, rec)
            self._protect(symbol, rec, inst)
            return None
        if reason is None:
            return None
        self.cancel_exits(inst, exit_ids)
        price = to_tick((ltp or rec["entry"]) * (1 - self.ex.entry_buffer_pct), inst.tick, "down")
        rec.update(status="closing", exit_ids=[], exit_reason=reason,
                   exit_order_id=self.place_sell(inst, min(int(qty_now), int(rec["qty"])), price))
        self.book.put(symbol, rec)
        return None

    def _event(self, symbol: str, rec: dict, status: str, reason: str) -> dict:
        return {"symbol": symbol, "side": "long", "qty": rec["qty"], "status": status, "reason": reason}

    def _closed(self, symbol: str, rec: dict, price: float, reason: str, today: str) -> dict:
        qty, entry = rec["qty"], rec["entry"]
        pnl = (price - entry) * qty
        risk = (entry - rec["initial_stop"]) * qty
        return {"symbol": symbol, "side": "long", "qty": qty, "entry": entry, "exit": price, "pnl": pnl,
                "r_multiple": pnl / risk if risk > 0 else 0.0, "opened": rec["opened"], "closed": today,
                "reason": reason, "setup": rec.get("setup"), "status": "closed"}
