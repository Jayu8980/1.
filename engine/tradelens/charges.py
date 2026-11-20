"""Indian statutory charges + Delta Exchange India fees. Mirrors android-app/.../domain/Charges.kt exactly."""
from __future__ import annotations

from dataclasses import dataclass, asdict

SEGMENTS = ("EQ_DELIVERY", "EQ_INTRADAY", "FUTURES", "OPTIONS", "COMMODITY_FUT", "CRYPTO_FUT", "CRYPTO_OPT")


@dataclass(frozen=True)
class Rates:
    brokerage_flat: float = 20.0
    brokerage_pct: float = 0.0003
    delivery_brokerage: float = 0.0
    stt_delivery: float = 0.001
    stt_intraday_sell: float = 0.00025
    stt_futures_sell: float = 0.0002
    stt_options_sell: float = 0.001
    ctt_commodity_sell: float = 0.0001
    nse_equity_txn: float = 0.0000297
    nse_futures_txn: float = 0.0000173
    nse_options_txn: float = 0.0003503
    mcx_futures_txn: float = 0.000021
    sebi_per_crore: float = 10.0
    stamp_delivery: float = 0.00015
    stamp_intraday: float = 0.00003
    stamp_futures: float = 0.00002
    stamp_options: float = 0.00003
    stamp_commodity: float = 0.00002
    gst: float = 0.18
    dp_per_sell_scrip: float = 15.34
    delta_fut_taker: float = 0.0005
    delta_fut_maker: float = 0.0002
    delta_opt_fee: float = 0.0003
    delta_opt_cap_of_premium: float = 0.10


@dataclass
class Breakdown:
    brokerage: float = 0.0
    stt: float = 0.0
    exchange: float = 0.0
    sebi: float = 0.0
    stamp: float = 0.0
    gst: float = 0.0
    dp: float = 0.0
    trading_fee: float = 0.0

    @property
    def total(self) -> float:
        return round(self.brokerage + self.stt + self.exchange + self.sebi + self.stamp + self.gst + self.dp + self.trading_fee, 2)

    def to_dict(self) -> dict:
        d = asdict(self)
        d["total"] = self.total
        return d


DEFAULT = Rates()


def leg(segment: str, side: str, price: float, qty: float, rates: Rates = DEFAULT, maker: bool = False, underlying_price: float = 0.0) -> Breakdown:
    """Charges for one executed leg. side is 'BUY' or 'SELL'; price is premium for options."""
    t = price * qty
    buy = side.upper() == "BUY"
    sebi = t * rates.sebi_per_crore / 1e7
    r0 = lambda v: float(round(v))  # STT / stamp are rounded to the rupee like contract notes
    if segment == "EQ_DELIVERY":
        brk = rates.delivery_brokerage
        exch = t * rates.nse_equity_txn
        dp = 0.0 if buy else rates.dp_per_sell_scrip
        return Breakdown(brk, r0(t * rates.stt_delivery), exch, sebi, r0(t * rates.stamp_delivery) if buy else 0.0, rates.gst * (brk + exch + sebi + dp), dp)
    if segment == "EQ_INTRADAY":
        brk = min(rates.brokerage_flat, t * rates.brokerage_pct)
        exch = t * rates.nse_equity_txn
        return Breakdown(brk, 0.0 if buy else r0(t * rates.stt_intraday_sell), exch, sebi, r0(t * rates.stamp_intraday) if buy else 0.0, rates.gst * (brk + exch + sebi))
    if segment == "FUTURES":
        brk = min(rates.brokerage_flat, t * rates.brokerage_pct)
        exch = t * rates.nse_futures_txn
        return Breakdown(brk, 0.0 if buy else r0(t * rates.stt_futures_sell), exch, sebi, r0(t * rates.stamp_futures) if buy else 0.0, rates.gst * (brk + exch + sebi))
    if segment == "OPTIONS":
        brk = rates.brokerage_flat
        exch = t * rates.nse_options_txn
        return Breakdown(brk, 0.0 if buy else r0(t * rates.stt_options_sell), exch, sebi, r0(t * rates.stamp_options) if buy else 0.0, rates.gst * (brk + exch + sebi))
    if segment == "COMMODITY_FUT":
        brk = min(rates.brokerage_flat, t * rates.brokerage_pct)
        exch = t * rates.mcx_futures_txn
        return Breakdown(brk, 0.0 if buy else r0(t * rates.ctt_commodity_sell), exch, sebi, r0(t * rates.stamp_commodity) if buy else 0.0, rates.gst * (brk + exch + sebi))
    if segment == "CRYPTO_FUT":
        fee = t * (rates.delta_fut_maker if maker else rates.delta_fut_taker)
        return Breakdown(gst=rates.gst * fee, trading_fee=fee)
    if segment == "CRYPTO_OPT":
        notional = (underlying_price or price) * qty
        fee = min(notional * rates.delta_opt_fee, t * rates.delta_opt_cap_of_premium)
        return Breakdown(gst=rates.gst * fee, trading_fee=fee)
    raise ValueError(f"unknown segment {segment}")


def round_trip(segment: str, entry: float, exit_: float, qty: float, long: bool = True) -> Breakdown:
    a = leg(segment, "BUY" if long else "SELL", entry, qty)
    b = leg(segment, "SELL" if long else "BUY", exit_, qty)
    return Breakdown(*(getattr(a, f) + getattr(b, f) for f in ("brokerage", "stt", "exchange", "sebi", "stamp", "gst", "dp", "trading_fee")))


def slippage_bps(avg_daily_value: float, order_value: float, base_bps: float = 3.0) -> float:
    """Liquidity-aware slippage: half-spread cost plus square-root market impact (100 bps × sqrt(participation)).

    1% of ADV → base + 10 bps; 10% of ADV → base + ~32 bps. Illiquid names (no ADV) are charged 50 bps."""
    if avg_daily_value <= 0:
        return 50.0
    participation = max(order_value / avg_daily_value, 0.0)
    return base_bps + 100.0 * participation ** 0.5
