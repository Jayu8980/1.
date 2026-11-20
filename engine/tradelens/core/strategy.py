"""Strategy plugin interface. Every strategy runs unchanged in backtest, paper and live."""
from __future__ import annotations

from abc import ABC, abstractmethod

import numpy as np
import pandas as pd

from .types import MarketEvent, Signal


class Context:
    """Read-only, look-ahead-free view of the market for strategies."""

    def __init__(self, data: dict[str, pd.DataFrame], regime: str = "range", meta: dict | None = None):
        self._data = data
        self._pos: dict[str, int] = {}
        self.now = None
        self.regime = regime
        self.meta = meta or {}
        self.positions: dict[str, float] = {}

    def advance(self, ev: MarketEvent) -> None:
        self.now = ev.ts
        for s in ev.bars:
            df = self._data[s]
            i = self._pos.get(s, 0)
            while i < len(df) and df.index[i] <= ev.ts:
                i += 1
            self._pos[s] = i

    def symbols(self) -> list[str]:
        return [s for s in self._data if self._pos.get(s, 0) > 0]

    def history(self, symbol: str, n: int | None = None) -> pd.DataFrame:
        """Bars up to and including the current timestamp (never the future)."""
        df = self._data.get(symbol)
        if df is None:
            return pd.DataFrame()
        end = self._pos.get(symbol, 0)
        return df.iloc[max(0, end - n) if n else 0:end]

    def close(self, symbol: str) -> float:
        h = self.history(symbol, 1)
        return float(h["close"].iloc[-1]) if len(h) else float("nan")


class Strategy(ABC):
    name: str = "base"
    description: str = ""
    universe: list[str] = []
    segment: str = "EQ_DELIVERY"
    # Regimes in which the strategy is allowed to trade (None = all) and size multipliers per regime.
    active_regimes: set[str] | None = None
    regime_scale: dict[str, float] = {}
    params: dict = {}
    param_grid: dict[str, list] = {}
    min_history: int = 60

    def __init__(self, **params):
        self.params = {**self.__class__.params, **params}

    def on_tick(self, symbol: str, price: float, ctx: Context) -> list[Signal]:
        """Optional intrabar hook (live feeds only)."""
        return []

    @abstractmethod
    def on_bar(self, ctx: Context) -> list[Signal]:
        ...

    def generate_signals(self, ctx: Context) -> list[Signal]:
        if self.active_regimes is not None and ctx.regime not in self.active_regimes:
            return [Signal(s, 0, 0.0, 1.0, strategy=self.name, reason=f"regime {ctx.regime} inactive") for s, q in ctx.positions.items() if q]  # flatten
        sigs = self.on_bar(ctx)
        scale = self.regime_scale.get(ctx.regime, 1.0)
        for s in sigs:
            s.strategy = self.name
            s.segment = s.segment if s.segment != "EQ_DELIVERY" else self.segment
            s.strength = float(np.clip(s.strength * scale, 0, 1))
        return sigs


# ---- small vectorised indicator helpers used by strategies (pandas) ----

def ema(s: pd.Series, n: int) -> pd.Series:
    return s.ewm(span=n, adjust=False, min_periods=n).mean()


def rsi(s: pd.Series, n: int = 14) -> pd.Series:
    d = s.diff()
    up = d.clip(lower=0).ewm(alpha=1 / n, adjust=False, min_periods=n).mean()
    dn = (-d.clip(upper=0)).ewm(alpha=1 / n, adjust=False, min_periods=n).mean()
    rs = up / dn.replace(0, np.nan)
    return (100 - 100 / (1 + rs)).fillna(100.0).where(dn.notna())


def atr(df: pd.DataFrame, n: int = 14) -> pd.Series:
    pc = df["close"].shift()
    tr = pd.concat([df["high"] - df["low"], (df["high"] - pc).abs(), (df["low"] - pc).abs()], axis=1).max(axis=1)
    return tr.ewm(alpha=1 / n, adjust=False, min_periods=n).mean()


def adx(df: pd.DataFrame, n: int = 14) -> pd.Series:
    up = df["high"].diff(); dn = -df["low"].diff()
    pdm = up.where((up > dn) & (up > 0), 0.0); mdm = dn.where((dn > up) & (dn > 0), 0.0)
    a = atr(df, n)
    pdi = 100 * pdm.ewm(alpha=1 / n, adjust=False).mean() / a
    mdi = 100 * mdm.ewm(alpha=1 / n, adjust=False).mean() / a
    dx = 100 * (pdi - mdi).abs() / (pdi + mdi).replace(0, np.nan)
    return dx.ewm(alpha=1 / n, adjust=False, min_periods=n).mean()


def supertrend(df: pd.DataFrame, n: int = 10, mult: float = 3.0) -> pd.Series:
    """Returns +1 (uptrend) / -1 (downtrend)."""
    a = atr(df, n).values; hl2 = ((df["high"] + df["low"]) / 2).values; c = df["close"].values
    fu = fl = np.nan; trend = np.ones(len(df))
    for i in range(len(df)):
        if np.isnan(a[i]):
            continue
        bu, bl = hl2[i] + mult * a[i], hl2[i] - mult * a[i]
        pc = c[i - 1] if i else c[i]
        fu = bu if np.isnan(fu) or bu < fu or pc > fu else fu
        fl = bl if np.isnan(fl) or bl > fl or pc < fl else fl
        prev = trend[i - 1] if i else 1
        trend[i] = -1 if prev == 1 and c[i] < fl else 1 if prev == -1 and c[i] > fu else prev
    return pd.Series(trend, index=df.index)
