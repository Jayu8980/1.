"""Indian transaction-cost model (brokerage, STT, exchange txn, SEBI, stamp, GST).

!! These rates move (SEBI/exchange circulars, Union Budget, broker plan changes).
!! Defaults below are a starting point and MUST be verified against Angel One's
!! current charge sheet before trusting paper P&L or the cost-aware signal filter.
Every rate is overridable via `CostSchedule`.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from decimal import ROUND_HALF_UP, Decimal

from app.core.enums import Exchange, Instrument, Product, Side

D = Decimal


@dataclass(frozen=True)
class SegmentRates:
    brokerage_pct: Decimal          # percent of turnover (cap applies)
    brokerage_cap: Decimal          # per executed order, INR
    brokerage_flat: Decimal | None  # flat per order (overrides pct/cap when set)
    stt_buy_pct: Decimal
    stt_sell_pct: Decimal
    exch_txn_pct: Decimal
    stamp_buy_pct: Decimal


@dataclass(frozen=True)
class CostSchedule:
    delivery: SegmentRates = SegmentRates(D("0.1"), D("20"), None, D("0.1"), D("0.1"), D("0.00297"), D("0.015"))
    intraday: SegmentRates = SegmentRates(D("0.03"), D("20"), None, D("0"), D("0.025"), D("0.00297"), D("0.003"))
    futures: SegmentRates = SegmentRates(D("0"), D("0"), D("20"), D("0"), D("0.02"), D("0.00173"), D("0.002"))
    options: SegmentRates = SegmentRates(D("0"), D("0"), D("20"), D("0"), D("0.1"), D("0.03503"), D("0.003"))
    sebi_per_crore: Decimal = D("10")
    gst_pct: Decimal = D("18")
    dp_charge_per_sell: Decimal = D("0")   # depository charge on delivery sells; set per your plan
    min_brokerage: Decimal = D("0")

    def rates_for(self, instrument: Instrument, product: Product) -> SegmentRates:
        if instrument is Instrument.FUTURE:
            return self.futures
        if instrument is Instrument.OPTION:
            return self.options
        return self.delivery if product is Product.DELIVERY else self.intraday


@dataclass(frozen=True)
class CryptoFeeSchedule:
    """Delta Exchange style: % of notional per fill + GST on the fee. No STT/stamp/SEBI.
    !! Verify current maker/taker rates for your Delta account tier. Indian tax on crypto
    !! (income tax / TDS on VDA) is NOT modelled here — it's a tax-filing matter, not a fill cost."""
    taker_pct: Decimal = D("0.05")
    maker_pct: Decimal = D("0.02")
    gst_pct: Decimal = D("18")


@dataclass(frozen=True)
class CostBreakdown:
    turnover: Decimal
    brokerage: Decimal
    stt: Decimal
    exchange: Decimal
    sebi: Decimal
    stamp: Decimal
    gst: Decimal
    dp: Decimal = D("0")
    total: Decimal = field(init=False)

    def __post_init__(self):
        object.__setattr__(self, "total", _r(self.brokerage + self.stt + self.exchange + self.sebi
                                             + self.stamp + self.gst + self.dp))


def _r(x: Decimal) -> Decimal:
    return x.quantize(D("0.01"), rounding=ROUND_HALF_UP)


def compute_costs(side: Side, qty: int, price: Decimal, instrument: Instrument,
                  product: Product, schedule: CostSchedule = CostSchedule(), *,
                  exchange: Exchange | None = None, multiplier: Decimal = D("1"),
                  crypto: CryptoFeeSchedule = CryptoFeeSchedule(), maker: bool = False) -> CostBreakdown:
    """Costs for one executed order leg. For options, `price` is the premium."""
    turnover = D(qty) * D(price) * D(multiplier)
    if exchange is Exchange.DELTA:
        fee = turnover * (crypto.maker_pct if maker else crypto.taker_pct) / 100
        z = D("0")
        return CostBreakdown(_r(turnover), _r(fee), z, z, z, z, _r(fee * crypto.gst_pct / 100))
    rt = schedule.rates_for(instrument, product)
    if rt.brokerage_flat is not None:
        brokerage = rt.brokerage_flat
    else:
        brokerage = min(turnover * rt.brokerage_pct / 100, rt.brokerage_cap)
        brokerage = max(brokerage, schedule.min_brokerage) if turnover > 0 else D("0")
    stt_pct = rt.stt_buy_pct if side is Side.BUY else rt.stt_sell_pct
    stt = (turnover * stt_pct / 100).quantize(D("1"), rounding=ROUND_HALF_UP)  # STT rounds to rupee
    exchange = turnover * rt.exch_txn_pct / 100
    sebi = turnover * schedule.sebi_per_crore / D("10000000")
    stamp = turnover * rt.stamp_buy_pct / 100 if side is Side.BUY else D("0")
    gst = (brokerage + exchange + sebi) * schedule.gst_pct / 100
    dp = schedule.dp_charge_per_sell if (side is Side.SELL and product is Product.DELIVERY
                                         and instrument is Instrument.EQUITY) else D("0")
    return CostBreakdown(_r(turnover), _r(brokerage), _r(stt), _r(exchange), _r(sebi), _r(stamp), _r(gst), _r(dp))


def round_trip_cost(qty: int, entry: Decimal, exit_: Decimal, instrument: Instrument,
                    product: Product, schedule: CostSchedule = CostSchedule(),
                    slippage_bps: Decimal = D("0")) -> Decimal:
    """Total INR cost for buy-then-sell (incl. slippage) — used by the cost-aware signal filter."""
    b = compute_costs(Side.BUY, qty, entry, instrument, product, schedule).total
    s = compute_costs(Side.SELL, qty, exit_, instrument, product, schedule).total
    slip = D(qty) * (D(entry) + D(exit_)) * slippage_bps / D("10000")
    return _r(b + s + slip)
