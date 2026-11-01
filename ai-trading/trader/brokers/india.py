"""Shared machinery for Indian brokers: shares, ETFs, and stock and index futures and options.

``[india] segment`` decides how a signal is traded:

    delivery  CNC   long only, held for days; stop-loss and target wait at the broker (GTT style)
    intraday  MIS   long or short; entered in the morning and squared off before the close
    futures   NRML  long or short stock/index futures, sized in lots, closed before expiry
    options   NRML  buys a call for a long signal or a put for a short one; the premium is the risk

Each adapter (Zerodha, Angel One, Dhan, Groww) implements a few primitives; this class runs the
lifecycle on top of them:

    queued  -> waiting for the session (no after-market orders, or a live price is needed first)
    pending -> entry order sent
    open    -> filled and protected: GTT-style exits for delivery; for the other segments a
               stop-loss order (re-placed every session) plus target, time, expiry and
               square-off checks by the monitor
    closing -> an exit order was sent

Orders are LIMIT orders a little beyond the last price (stop-loss orders carry a trigger), so they
fill like market orders without ever filling at an absurd price. Without --live an adapter is a
dry run: it reads the real account and reports the exact orders it would send, sending none.
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

from ..markets import INDEX_UNDERLYINGS, is_index
from .base import Broker, BrokerError, PositionBook

log = logging.getLogger("trader.brokers.india")

IST = timezone(timedelta(hours=5, minutes=30))
SESSION_OPEN, SESSION_CLOSE = dtime(9, 15), dtime(15, 30)
DONE, OPEN, REJECTED, CANCELLED = "complete", "open", "rejected", "cancelled"
PRODUCT = {"delivery": "CNC", "intraday": "MIS", "futures": "NRML", "options": "NRML"}
DERIVATIVES = {"NSE": "NFO", "BSE": "BFO"}


def now_ist() -> datetime:
    return datetime.now(IST)


def market_open(at: datetime | None = None) -> bool:
    """NSE/BSE regular session, 09:15-15:30 IST on weekdays (exchange holidays are not known)."""
    at = (at or now_ist()).astimezone(IST)
    return at.weekday() < 5 and SESSION_OPEN <= at.time() <= SESSION_CLOSE


def split_symbol(symbol: str) -> tuple[str, str]:
    """RELIANCE.NS -> ("NSE", "RELIANCE"); TCS.BO -> ("BSE", "TCS")."""
    if is_index(symbol):
        raise BrokerError(f"{symbol} is an index, not a share: trade it with the futures or options "
                          "segment, or buy an index ETF (india_etfs preset)")
    s = symbol.upper()
    if s.endswith(".NS"):
        return "NSE", s[:-3]
    if s.endswith(".BO"):
        return "BSE", s[:-3]
    raise BrokerError(f"{symbol} is not an NSE (.NS) or BSE (.BO) share")


def underlying_of(symbol: str) -> tuple[str, str, bool]:
    """Signal symbol -> (F&O underlying name, cash exchange, is an index)."""
    if symbol in INDEX_UNDERLYINGS:
        name, exchange = INDEX_UNDERLYINGS[symbol]
        return name, exchange, True
    exchange, base = split_symbol(symbol)
    return base, exchange, False


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
    symbol: str             # desk symbol, e.g. RELIANCE.NS or ^NSEI
    exchange: str           # NSE / BSE for shares, NFO / BFO for derivatives
    tradingsymbol: str      # the broker's own name for it
    token: str = ""         # the broker's numeric id where it needs one
    tick: float = 0.0       # 0 = unknown; prices then use the safe grid
    kind: str = "equity"    # equity | future | option
    lot: int = 1
    expiry: str = ""        # ISO date for derivatives
    strike: float = 0.0
    option_type: str = ""   # CE / PE


def add_holding(out: dict, base: str, qty: int, avg=None, ltp=None) -> None:
    """Merge a holding or a same-day delivery position into {base symbol: {qty, avg, ltp}}."""
    row = out.setdefault(base, {"qty": 0, "avg": None, "ltp": None})
    row["qty"] += int(qty)
    row["avg"] = row["avg"] or (float(avg) if avg not in (None, "") else None)
    row["ltp"] = row["ltp"] or (float(ltp) if ltp not in (None, "") else None)


class IndianBroker(Broker):
    name = "india"
    supports_amo = True  # accepts after-market orders that execute at the next open

    def __init__(self, ex, live: bool, ic=None) -> None:
        from ..config import IndiaConfig

        self.ex = ex
        self.live = live
        self.ic = ic or IndiaConfig()
        self.segment = self.ic.segment
        self.state_dir = Path(ex.state_dir)
        self.book = PositionBook(self.state_dir / f"{self.name}_book.json")
        self._api = None
        self._frames: dict = {}
        self._refs: dict[str, float] = {}
        self._contracts: dict = {}

    # ---- primitives every adapter implements -------------------------------------------------
    def connect(self):
        """Log in and return the broker SDK client."""
        raise NotImplementedError

    def instrument(self, symbol: str) -> Instrument:
        """The cash-market share or ETF for a .NS / .BO symbol."""
        raise NotImplementedError

    def funds(self) -> float:
        """Cash available for buying."""
        raise NotImplementedError

    def holdings(self) -> dict[str, dict]:
        """All delivery shares in the account (holdings plus today's CNC trades), by base symbol."""
        raise NotImplementedError

    def ltp(self, inst: Instrument) -> float:
        raise NotImplementedError

    def place_order(self, inst: Instrument, side: str, qty: int, price: float, product: str,
                    trigger: float | None = None, amo: bool = False) -> str:
        """A LIMIT order, or a stop-loss-limit order when ``trigger`` is given. Returns its id."""
        raise NotImplementedError

    def cancel_order(self, order_id: str, inst: Instrument | None = None) -> None:
        raise NotImplementedError

    def order_state(self, order_id: str, inst: Instrument | None = None) -> dict | None:
        """{"status": complete|open|rejected|cancelled, "filled", "avg", "message"}; None if unknown."""
        raise NotImplementedError

    def net_positions(self) -> list[dict]:
        """Today's positions: [{"exchange", "tradingsymbol", "token", "product", "qty" (+long/-short)}]."""
        raise NotImplementedError

    def contracts(self, name: str, exchange: str) -> list[dict]:
        """Futures and options of an underlying:
        [{"kind": FUT|CE|PE, "expiry": date, "strike", "lot", "tick", "tradingsymbol", "token", "exchange"}]."""
        raise NotImplementedError

    def index_ltp(self, name: str) -> float:
        """Last value of an index by F&O name (NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX)."""
        raise NotImplementedError

    def place_exits(self, inst: Instrument, qty: int, stop: float, target: float, ltp: float) -> list[str]:
        """Delivery: stop-loss and target that wait at the broker (GTT style)."""
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
        """Limit price of a protective sell, far enough below its trigger to fill in a fast fall."""
        return to_tick(stop * (1 - self.ex.stop_limit_buffer_pct), tick, "down")

    def _ref_price(self, symbol: str):
        df = self._frames.get(symbol)
        if df is not None and len(df):
            return float(df["Close"].iloc[-1])
        return self._refs.get(symbol)

    def _ltp_or(self, inst: Instrument, default):
        try:
            return float(self.ltp(inst))
        except BrokerError:
            if inst.kind != "equity":
                return default  # a share's close is no price for its derivatives
            ref = self._ref_price(inst.symbol)
            return ref if ref is not None else default

    def underlying_ltp(self, symbol: str):
        """Current price of the share or index behind a signal, sanity-checked against Yahoo."""
        name, _, index = underlying_of(symbol)
        try:
            price = self.index_ltp(name) if index else self.ltp(self.instrument(symbol))
        except BrokerError:
            price = None
        ref = self._ref_price(symbol)
        if price and ref and abs(price / ref - 1) > 0.15:  # a wrong index mapping must not drive exits
            log.warning("%s: broker price %.2f is far from the reference %.2f; using the reference", symbol, price, ref)
            price = None
        return float(price) if price else ref

    def position_qty(self, inst: Instrument, product: str) -> int:
        """Signed quantity of this instrument under this product (+ long, - short)."""
        total = 0
        for p in self.net_positions():
            if p.get("product") != product:
                continue
            same_token = inst.token and str(p.get("token") or "") == str(inst.token)
            same_name = p.get("tradingsymbol") == inst.tradingsymbol and p.get("exchange") == inst.exchange
            if same_token or same_name:
                total += int(p.get("qty") or 0)
        return total

    def contract(self, symbol: str, kind: str) -> Instrument:
        """The futures contract (kind FUT) or option (CE/PE) to trade for a signal symbol."""
        name, cash_exchange, _ = underlying_of(symbol)
        exchange = DERIVATIVES[cash_exchange]
        if (name, exchange) not in self._contracts:
            self._contracts[(name, exchange)] = self.contracts(name, exchange)
        rows = [r for r in self._contracts[(name, exchange)] if r["kind"] == kind]
        label = "futures" if kind == "FUT" else "options"
        if not rows:
            raise BrokerError(f"{name} has no {label} on {exchange}")
        today = now_ist().date()
        rows = [r for r in rows if (r["expiry"] - today).days >= self.ic.min_days_to_expiry]
        if not rows:
            raise BrokerError(f"no {name} {label} expire at least {self.ic.min_days_to_expiry} days out")
        expiry = min(r["expiry"] for r in rows)
        rows = [r for r in rows if r["expiry"] == expiry]
        if kind == "FUT":
            row = rows[0]
        else:
            spot = self.underlying_ltp(symbol)
            if not spot:
                raise BrokerError(f"no price for {name} to choose a strike")
            strikes = sorted({float(r["strike"]) for r in rows})
            atm = min(range(len(strikes)), key=lambda i: abs(strikes[i] - spot))
            step = {"atm": 0, "otm1": 1, "itm1": -1}[self.ic.option_strike] * (1 if kind == "CE" else -1)
            strike = strikes[max(0, min(len(strikes) - 1, atm + step))]
            row = next(r for r in rows if float(r["strike"]) == strike)
        return Instrument(symbol, row["exchange"], row["tradingsymbol"], str(row["token"]), float(row.get("tick") or 0),
                          kind="future" if kind == "FUT" else "option", lot=int(row["lot"]),
                          expiry=row["expiry"].isoformat(), strike=float(row.get("strike") or 0),
                          option_type="" if kind == "FUT" else kind)

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
        symbol = plan["symbol"]
        if symbol in self.book.all():
            raise BrokerError(f"{symbol}: the desk already has an order or position here")
        d = 1 if plan["side"] == "long" else -1
        if self.segment == "delivery" and d < 0:
            raise BrokerError("delivery (CNC) is long only; use the intraday, futures or options segment")
        self._refs[symbol] = float(plan["entry_ref"])
        rec = {"symbol": symbol, "segment": self.segment, "product": PRODUCT[self.segment], "d": d,
               "status": "queued", "side": plan["side"], "qty": int(plan["qty"]), "entry_ref": plan["entry_ref"],
               "entry": None, "stop": plan["stop"], "initial_stop": plan["stop"], "target": plan["target"],
               "max_hold": plan["max_hold"], "setup": plan.get("setup"), "signal_date": plan.get("signal_date"),
               "risk_amount": plan.get("risk_amount"), "entry_order_id": None, "exit_ids": [], "placed": None,
               "opened": None}
        if self.segment == "delivery":
            return self._submit_delivery(rec)
        if not self.live:
            text = self._entry_order(rec)[4]
            log.info("DRY RUN %s: %s", self.name, text)
            return {"id": "", "status": "dry-run", "reason": text}
        self.book.put(symbol, rec)
        if market_open():
            try:
                self._sync(symbol, rec, {})
            except BrokerError as exc:
                log.warning("%s: will retry on the next monitor run (%s)", symbol, exc)
        rec = self.book.all().get(symbol)
        if rec is None:
            return {"id": "", "status": "cancelled", "reason": "dropped before entry (see the monitor log)"}
        return {"id": rec.get("entry_order_id") or "", "status": rec["status"],
                "reason": rec.get("entry_text", "entry is sent once the session is open")}

    def _submit_delivery(self, rec: dict) -> dict:
        symbol = rec["symbol"]
        qty = rec["qty"]
        if qty < 1:
            raise BrokerError("quantity is below one share")
        inst = self.instrument(symbol)
        in_session = market_open()
        ref = self._ltp_or(inst, rec["entry_ref"]) if in_session else rec["entry_ref"]
        limit = to_tick(ref * (1 + self.ex.entry_buffer_pct), inst.tick, "up")
        if not rec["stop"] < limit < rec["target"]:
            raise BrokerError(f"limit {limit} is already outside the stop {rec['stop']} / target {rec['target']}")
        how = ("regular order" if in_session else "after-market order" if self.supports_amo
               else "queued until the market opens")
        text = (f"BUY {qty} {inst.exchange}:{inst.tradingsymbol} LIMIT {limit} CNC ({how}), then "
                f"stop-loss {rec['stop']} (sell limit {self.stop_limit(rec['stop'], inst.tick)}) "
                f"and target {rec['target']}")
        if not self.live:
            log.info("DRY RUN %s: %s", self.name, text)
            return {"id": "", "status": "dry-run", "reason": text}
        rec["instrument"] = asdict(inst)
        if in_session or self.supports_amo:
            try:
                rec["entry_order_id"] = self.place_order(inst, "BUY", qty, limit, "CNC", amo=not in_session)
                rec.update(status="pending", placed=now_ist().date().isoformat(), limit=limit)
            except BrokerError as exc:
                if in_session:
                    raise
                log.warning("%s: after-market order refused (%s); queued for the next session", symbol, exc)
        self.book.put(symbol, rec)
        if rec["status"] == "pending" and in_session:
            time.sleep(2)  # give the exchange a moment, then protect the fill straight away
            try:
                self._sync(symbol, rec, {})
            except BrokerError as exc:
                log.warning("%s: will finish on the next monitor run (%s)", symbol, exc)
        return {"id": rec["entry_order_id"] or "", "status": rec["status"], "reason": text}

    def manage(self, frames: dict, trail: bool = True) -> list[dict]:
        records = self.book.all()
        if not self.live or not records:
            return []
        self._frames = frames or {}
        held = {}
        if any(r.get("segment", "delivery") == "delivery" for r in records.values()):
            try:
                held = self.holdings()
            except BrokerError as exc:  # never act on a partial picture of the account
                log.warning("%s: could not read holdings, skipping this run: %s", self.name, exc)
                return []
        events = []
        for symbol, rec in records.items():
            try:
                event = self._sync(symbol, rec, held, trail)
            except BrokerError as exc:
                log.warning("%s %s: %s", self.name, symbol, exc)
                continue
            if event:
                events.append(event)
        return events

    # ---- lifecycle ---------------------------------------------------------------------------
    def _stale(self, rec: dict, today: str) -> bool:
        """A queued signal is only good for the next session."""
        if not rec.get("signal_date"):
            return False
        last = np.busday_offset(np.datetime64(rec["signal_date"]), 1, roll="forward")
        return np.datetime64(today) > last

    def _sync(self, symbol: str, rec: dict, held: dict, trail: bool = True) -> dict | None:
        if rec.get("segment", "delivery") != "delivery":
            return self._sync_session(symbol, rec, trail)
        today = now_ist().date().isoformat()
        session = market_open()

        if rec["status"] == "queued":
            if not session:
                return None
            if self._stale(rec, today):
                self.book.remove(symbol)
                return self._event(symbol, rec, "cancelled", "signal expired before it could be placed")
            inst = Instrument(**rec["instrument"])
            ltp = self._ltp_or(inst, rec["entry_ref"])
            if not rec["stop"] < ltp < rec["target"]:
                self.book.remove(symbol)
                return self._event(symbol, rec, "cancelled", "price moved beyond the stop or target before entry")
            limit = to_tick(ltp * (1 + self.ex.entry_buffer_pct), inst.tick, "up")
            rec.update(status="pending", entry_order_id=self.place_order(inst, "BUY", rec["qty"], limit, "CNC"),
                       placed=today, limit=limit)
            self.book.put(symbol, rec)

        inst = Instrument(**rec["instrument"])
        if rec["status"] == "pending":
            o = self.order_state(rec["entry_order_id"], inst)
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
            return self._closing(symbol, rec, inst, today)
        return None

    def _closing(self, symbol: str, rec: dict, inst: Instrument, today: str) -> dict | None:
        o = self.order_state(rec["exit_order_id"], inst)
        if o is None or o["status"] == OPEN:
            return None
        if o["filled"] > 0:
            self.book.remove(symbol)
            return self._closed(symbol, rec, float(o["avg"] or rec["stop"]), rec.get("exit_reason", "exit"), today)
        rec.update(status="open", exit_order_id=None)  # exit refused: protect again and retry
        self.book.put(symbol, rec)
        return self._event(symbol, rec, "error", f"exit order {o['status']}: {o['message']}")

    # ---- delivery: GTT-style exits that wait at the broker ----------------------------------
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
                   exit_order_id=self.place_order(inst, "SELL", min(int(qty_now), int(rec["qty"])), price, "CNC"))
        self.book.put(symbol, rec)
        return None

    # ---- intraday, futures and options --------------------------------------------------------
    def _entry_order(self, rec: dict) -> tuple[Instrument, str, int, float, str]:
        """Instrument, side, quantity, limit price and a description of this record's entry."""
        segment, d = rec["segment"], rec["d"]
        buf = self.ex.entry_buffer_pct

        def limit_for(side: str, ref: float, inst: Instrument) -> float:
            return (to_tick(ref * (1 + buf), inst.tick, "up") if side == "BUY"
                    else to_tick(ref * (1 - buf), inst.tick, "down"))

        if segment in ("delivery", "intraday"):
            inst = self.instrument(rec["symbol"])
            ref = self._ltp_or(inst, rec["entry_ref"]) if market_open() else rec["entry_ref"]
            side, qty, size = ("BUY" if d > 0 else "SELL"), int(rec["qty"]), f"{int(rec['qty'])}"
            if qty < 1:
                raise BrokerError("quantity is below one share")
            limit = limit_for(side, ref, inst)
        else:
            kind = "FUT" if segment == "futures" else ("CE" if d > 0 else "PE")
            inst = self.contract(rec["symbol"], kind)
            ref = self._ltp_or(inst, None)
            if not ref:
                raise BrokerError(f"no live price for {inst.tradingsymbol}")
            side = "BUY" if (segment == "options" or d > 0) else "SELL"
            limit = limit_for(side, ref, inst)
            # risk per unit: the points to the stop for futures, the whole premium paid for options
            unit_risk = abs(rec["entry_ref"] - rec["stop"]) if segment == "futures" else limit
            budget = float(rec.get("risk_amount") or 0)
            by_risk = math.floor(budget / (unit_risk * inst.lot)) if unit_risk > 0 else 0
            by_value = math.floor(self.ex.max_order_value / (limit * inst.lot))
            lots = min(by_risk, by_value)
            if lots < 1:
                why = (f"one lot risks {unit_risk * inst.lot:,.0f}, above the {budget:,.0f} risk budget"
                       if by_risk < 1 else f"one lot is worth {limit * inst.lot:,.0f}, above max_order_value "
                                            f"{self.ex.max_order_value:,.0f}")
                raise BrokerError(f"{inst.tradingsymbol}: {why}")
            qty, size = lots * inst.lot, f"{lots} lot(s) = {lots * inst.lot}"
        text = f"{side} {size} {inst.exchange}:{inst.tradingsymbol} LIMIT {limit} {rec['product']}"
        if segment == "intraday":
            text += (f"; stop-loss order at {rec['stop']}, target {rec['target']}, "
                     f"square-off at {self.ic.intraday_square_off}")
        elif segment == "futures":
            text += (f" (expiry {inst.expiry}); stop {abs(rec['entry_ref'] - rec['stop']):.2f} and target "
                     f"{abs(rec['target'] - rec['entry_ref']):.2f} points from the fill")
        elif segment == "options":
            text += (f" ({inst.strike:g} {inst.option_type}, expiry {inst.expiry}); premium at risk about "
                     f"{qty * limit:,.0f}; exits when {rec['symbol']} reaches {rec['stop']} or {rec['target']}, "
                     f"or the premium falls {self.ic.option_stop_pct:.0%}")
        return inst, side, qty, limit, text

    def _still_valid(self, rec: dict) -> bool:
        price = self.underlying_ltp(rec["symbol"])
        if price is None:
            return True
        return rec["d"] * (price - rec["stop"]) > 0 and rec["d"] * (rec["target"] - price) > 0

    def _sync_session(self, symbol: str, rec: dict, trail: bool) -> dict | None:
        now = now_ist()
        today, session = now.date().isoformat(), market_open(now)
        entry_until = dtime.fromisoformat(self.ic.intraday_entry_until)

        if rec["status"] == "queued":
            if not session:
                return None
            reason = ("missed the morning entry window" if rec["segment"] == "intraday" and now.time() > entry_until
                      else "signal expired before it could be placed" if self._stale(rec, today)
                      else "price moved beyond the stop or target before entry" if not self._still_valid(rec)
                      else None)
            if reason:
                self.book.remove(symbol)
                return self._event(symbol, rec, "cancelled", reason)
            inst, side, qty, limit, text = self._entry_order(rec)
            rec.update(status="pending", instrument=asdict(inst), qty=qty, entry_side=side, limit=limit,
                       placed=today, entry_text=text, expiry=inst.expiry or None,
                       u_at_entry=self.underlying_ltp(symbol) if rec["segment"] == "options" else None)
            rec["entry_order_id"] = self.place_order(inst, side, qty, limit, rec["product"])
            self.book.put(symbol, rec)

        inst = Instrument(**rec["instrument"])
        if rec["status"] == "pending":
            o = self.order_state(rec["entry_order_id"], inst)
            if o is None:
                if rec["placed"] < today:
                    self.book.remove(symbol)
                    return self._event(symbol, rec, "cancelled", "entry order expired")
                return None
            if o["status"] == OPEN and rec["segment"] == "intraday" and session and now.time() > entry_until:
                self.cancel_order(rec["entry_order_id"], inst)  # too late in the day to start an intraday trade
                o = self.order_state(rec["entry_order_id"], inst) or o
            if o["status"] == OPEN:
                return None  # still working, or the cancellation is in progress
            if o["filled"] <= 0:
                self.book.remove(symbol)
                return self._event(symbol, rec, "cancelled", o["message"] or f"entry order {o['status']}")
            fill = float(o["avg"] or rec["limit"])
            self._anchor(rec, inst, fill)
            rec.update(status="open", qty=int(o["filled"]), entry=fill, opened=today)
            self._place_stop(rec, inst, today)
            return self._event(symbol, rec, "filled",
                               f"{rec['entry_side'].lower()} {rec['qty']} {inst.tradingsymbol} at {fill:.2f}; "
                               f"stop-loss order {'placed' if rec.get('stop_order_id') else 'pending'}")

        if rec["status"] == "open":
            return self._manage_session(symbol, rec, inst, today, now, session, trail)
        if rec["status"] == "closing":
            return self._closing(symbol, rec, inst, today)
        return None

    def _anchor(self, rec: dict, inst: Instrument, fill: float) -> None:
        """Keep the planned distances to the stop and target, measured from the real entry."""
        if rec["segment"] == "options":  # levels stay on the underlying; shift by its move since the signal
            shift = (rec.get("u_at_entry") or rec["entry_ref"]) - rec["entry_ref"]
            rec["stop"], rec["target"] = round(rec["stop"] + shift, 2), round(rec["target"] + shift, 2)
        else:
            shift = fill - rec["entry_ref"]
            rec["stop"] = to_tick(rec["stop"] + shift, inst.tick, "down" if rec["d"] > 0 else "up")
            rec["target"] = to_tick(rec["target"] + shift, inst.tick)
        rec["initial_stop"] = rec["stop"]

    def _place_stop(self, rec: dict, inst: Instrument, today: str) -> None:
        """Protective stop-loss order for the session (DAY validity, so it is re-placed each day)."""
        exit_side = "SELL" if rec["entry_side"] == "BUY" else "BUY"
        if rec["segment"] == "options":  # backstop on the premium; the main exit follows the underlying
            trigger = to_tick(rec["entry"] * (1 - self.ic.option_stop_pct), inst.tick, "down")
        else:
            trigger = to_tick(rec["stop"], inst.tick, "down" if exit_side == "SELL" else "up")
        buf = self.ex.stop_limit_buffer_pct
        limit = (to_tick(trigger * (1 - buf), inst.tick, "down") if exit_side == "SELL"
                 else to_tick(trigger * (1 + buf), inst.tick, "up"))
        try:
            rec["stop_order_id"] = self.place_order(inst, exit_side, rec["qty"], limit, rec["product"], trigger=trigger)
            rec["stop_day"] = today
        except BrokerError as exc:  # e.g. the price is already through the trigger: the next check exits
            log.warning("%s: stop-loss order not placed: %s", rec["symbol"], exc)
            rec["stop_order_id"] = None
        self.book.put(rec["symbol"], rec)

    def _cancel_stop(self, rec: dict, inst: Instrument) -> bool:
        """Cancel the stop-loss order. False when it already filled or the cancel is not confirmed."""
        order_id = rec.get("stop_order_id")
        if not order_id:
            return True
        state = self.order_state(order_id, inst)
        if state is None or state["status"] in (CANCELLED, REJECTED):
            rec["stop_order_id"] = None
            return True
        if state["status"] == DONE:
            return False
        try:
            self.cancel_order(order_id, inst)
        except BrokerError as exc:
            log.warning("%s: could not cancel the stop-loss order: %s", rec["symbol"], exc)
            return False
        state = self.order_state(order_id, inst)
        if state is not None and state["status"] in (DONE, OPEN):
            return False  # filled meanwhile, or the cancel is still pending: decide on the next run
        rec["stop_order_id"] = None
        return True

    def _manage_session(self, symbol, rec, inst, today, now, session, trail) -> dict | None:
        d = rec["d"]
        sign = 1 if rec["entry_side"] == "BUY" else -1  # +1 when the desk holds the instrument long
        held = self.position_qty(inst, rec["product"]) * sign
        stop_state = self.order_state(rec["stop_order_id"], inst) if rec.get("stop_order_id") else None
        if held <= 0:  # stopped out, squared off by the broker, or closed by hand
            if stop_state and stop_state["status"] == OPEN:
                if self.position_qty(inst, rec["product"]) * sign > 0:
                    return None  # the position is there after all
                self.cancel_order(rec["stop_order_id"], inst)  # never leave it to open a new position
            stopped = bool(stop_state and stop_state["status"] == DONE and stop_state["filled"] > 0)
            price = stop_state["avg"] if stopped else self._ltp_or(inst, rec["entry"])
            self.book.remove(symbol)
            return self._closed(symbol, rec, float(price or rec["entry"]), "stop" if stopped
                                else "closed outside the desk (broker square-off or manual)", today)
        if not session:
            return None

        level = self.underlying_ltp(symbol) if rec["segment"] == "options" else self._ltp_or(inst, None)
        reason = None
        if rec["segment"] == "intraday" and now.time() >= dtime.fromisoformat(self.ic.intraday_square_off):
            reason = "intraday square-off"
        elif rec.get("expiry") and (datetime.fromisoformat(rec["expiry"]).date() - now.date()).days \
                <= self.ic.exit_days_before_expiry:
            reason = "expiry"
        elif rec["segment"] != "intraday" and int(np.busday_count(rec["opened"], today)) >= rec["max_hold"]:
            reason = "time"
        elif level is not None and d * (level - rec["target"]) >= 0:
            reason = "target"
        elif level is not None and d * (level - rec["stop"]) <= 0:
            reason = "stop"
        if reason:
            if not self._cancel_stop(rec, inst):
                self.book.put(symbol, rec)
                return None  # the stop order filled or is mid-cancel: settle on the next run
            held = self.position_qty(inst, rec["product"]) * sign
            if held <= 0:
                self.book.put(symbol, rec)
                return None
            exit_side = "SELL" if sign > 0 else "BUY"
            px = self._ltp_or(inst, rec["entry"])
            buf = self.ex.entry_buffer_pct
            limit = (to_tick(px * (1 - buf), inst.tick, "down") if exit_side == "SELL"
                     else to_tick(px * (1 + buf), inst.tick, "up"))
            rec.update(status="closing", exit_reason=reason, stop_order_id=None,
                       exit_order_id=self.place_order(inst, exit_side, min(held, int(rec["qty"])), limit,
                                                      rec["product"]))
            self.book.put(symbol, rec)
            return None

        if trail and not rec.get("trailed") and rec["segment"] != "options" and level is not None \
                and d * (level - rec["entry"]) >= abs(rec["entry"] - rec["initial_stop"]):
            if self._cancel_stop(rec, inst):
                rec.update(stop=to_tick(rec["entry"], inst.tick, "down" if d > 0 else "up"), trailed=True)
                self._place_stop(rec, inst, today)
            self.book.put(symbol, rec)
            return None
        needs_stop = (stop_state is None or stop_state["status"] in (CANCELLED, REJECTED)
                      or rec.get("stop_day") != today)
        if needs_stop and not (stop_state and stop_state["status"] == DONE):
            # an old order still working must be confirmed cancelled first: two live stop orders
            # could both fill and leave a position in the opposite direction
            if stop_state and stop_state["status"] == OPEN and not self._cancel_stop(rec, inst):
                self.book.put(symbol, rec)
                return None
            self._place_stop(rec, inst, today)
        return None

    # ---- events --------------------------------------------------------------------------------
    def _event(self, symbol: str, rec: dict, status: str, reason: str) -> dict:
        return {"symbol": symbol, "side": rec.get("side", "long"), "qty": rec["qty"], "status": status, "reason": reason}

    def _closed(self, symbol: str, rec: dict, price: float, reason: str, today: str) -> dict:
        sign = 1 if rec.get("entry_side", "BUY") == "BUY" else -1
        qty, entry = rec["qty"], rec["entry"]
        pnl = sign * (price - entry) * qty
        risk = rec.get("risk_amount") or abs(entry - rec["initial_stop"]) * qty
        return {"symbol": symbol, "side": rec.get("side", "long"), "qty": qty, "entry": entry, "exit": price,
                "pnl": pnl, "r_multiple": pnl / risk if risk > 0 else 0.0, "opened": rec["opened"],
                "closed": today, "reason": reason, "setup": rec.get("setup"), "status": "closed"}
