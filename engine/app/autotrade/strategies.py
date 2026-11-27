"""Regime filter + primary strategies.

These are deliberately simple, transparent rules so behaviour is auditable. They have NOT been
validated by the walk-forward backtester (Phase 2) and confidence is a heuristic score, not a
calibrated probability (the LightGBM meta-model is Phase 3). That is why every strategy starts
in PAPER and can only reach real money through the promotion gate.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone

from .bars import BarSeries
from .indicators import atr, efficiency_ratio, ema, ema_series, rsi, sma


@dataclass
class TradeSignal:
    strategy: str
    side: str            # BUY / SELL
    entry: float
    stop: float
    target: float
    confidence: float    # heuristic 0..1
    regime: str
    reason: str
    atr: float
    ts: datetime = field(default_factory=lambda: datetime.now(timezone.utc))

    @property
    def risk_per_unit(self) -> float:
        return abs(self.entry - self.stop)

    @property
    def reward_risk(self) -> float:
        r = self.risk_per_unit
        return abs(self.target - self.entry) / r if r else 0.0


def regime(s: BarSeries, high_vol_atr_pct: float = 1.2) -> tuple[str, float]:
    """TREND / RANGE / HIGH_VOL / UNCERTAIN, plus how sure we are (0..1)."""
    c = s.closes
    er, a = efficiency_ratio(c, 20), atr(s.highs, s.lows, c, 14)
    if er is None or a is None:
        return "UNCERTAIN", 0.0
    atr_pct = a / c[-1] * 100
    if atr_pct >= high_vol_atr_pct:
        return "HIGH_VOL", min(1.0, atr_pct / (2 * high_vol_atr_pct))
    if er >= 0.40:
        return "TREND", min(1.0, (er - 0.40) / 0.4 + 0.5)
    if er <= 0.20:
        return "RANGE", min(1.0, (0.20 - er) / 0.2 * 0.5 + 0.5)
    return "UNCERTAIN", 0.0


class Strategy:
    id = "base"
    name = "base"
    description = ""
    regimes: tuple[str, ...] = ()
    min_bars = 60

    def evaluate(self, s: BarSeries, reg: str, reg_conf: float) -> TradeSignal | None:  # pragma: no cover
        raise NotImplementedError


class TrendMomentum(Strategy):
    id = "trend_momentum"
    name = "Trend momentum"
    description = "EMA20/EMA50 cross with price above both; 1.5×ATR stop, 3×ATR target. TREND regime only."
    regimes = ("TREND",)

    def evaluate(self, s, reg, reg_conf):
        c = s.closes
        if len(c) < self.min_bars or reg not in self.regimes:
            return None
        f, sl = ema_series(c, 20), ema_series(c, 50)
        a = atr(s.highs, s.lows, c, 14)
        if len(sl) < 2 or a is None or a <= 0:
            return None
        fast_now, fast_prev = f[-1], f[-2]
        slow_now, slow_prev = sl[-1], sl[-2]
        px = c[-1]
        conf = round(min(0.85, 0.45 + 0.35 * reg_conf), 3)
        if fast_prev <= slow_prev and fast_now > slow_now and px > fast_now:
            return TradeSignal(self.id, "BUY", px, px - 1.5 * a, px + 3 * a, conf, reg,
                               f"EMA20 crossed above EMA50, price above both; ER-trend {reg_conf:.2f}", a)
        if fast_prev >= slow_prev and fast_now < slow_now and px < fast_now:
            return TradeSignal(self.id, "SELL", px, px + 1.5 * a, px - 3 * a, conf, reg,
                               f"EMA20 crossed below EMA50, price below both; ER-trend {reg_conf:.2f}", a)
        return None


class MeanReversion(Strategy):
    id = "mean_reversion"
    name = "Mean reversion"
    description = "RSI(2) extreme against a flat 20-bar mean; 1×ATR stop, target the mean. RANGE regime only."
    regimes = ("RANGE",)

    def evaluate(self, s, reg, reg_conf):
        c = s.closes
        if len(c) < self.min_bars or reg not in self.regimes:
            return None
        r, m, a = rsi(c, 2), sma(c, 20), atr(s.highs, s.lows, c, 14)
        if r is None or m is None or a is None or a <= 0:
            return None
        px = c[-1]
        conf = round(min(0.8, 0.45 + 0.3 * reg_conf), 3)
        if r < 10 and px < m - 0.5 * a:
            return TradeSignal(self.id, "BUY", px, px - a, m, conf, reg,
                               f"RSI(2)={r:.0f} oversold, {abs(m - px) / a:.1f} ATR below 20-bar mean", a)
        if r > 90 and px > m + 0.5 * a:
            return TradeSignal(self.id, "SELL", px, px + a, m, conf, reg,
                               f"RSI(2)={r:.0f} overbought, {abs(px - m) / a:.1f} ATR above 20-bar mean", a)
        return None


ALL: list[Strategy] = [TrendMomentum(), MeanReversion()]
