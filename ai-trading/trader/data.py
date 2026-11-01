"""Market data: daily OHLCV from Yahoo Finance with an on-disk cache.

``source = "synthetic"`` generates deterministic fake markets so the whole pipeline can be run and
tested offline. Synthetic data is for demos only; live trading refuses to run on it.
"""
from __future__ import annotations

import logging
import re
import time
import zlib
from pathlib import Path

import numpy as np
import pandas as pd

log = logging.getLogger("trader.data")

OHLCV = ["Open", "High", "Low", "Close", "Volume"]


class MarketData:
    def __init__(self, source: str = "yahoo", cache_dir: str = ".cache",
                 cache_hours: float = 12.0, years: int = 6) -> None:
        self.source = source
        self.cache_dir = Path(cache_dir)
        self.cache_hours = cache_hours
        self.years = years

    def get(self, symbols: list[str], refresh: bool = False) -> dict[str, pd.DataFrame]:
        symbols = [s for s in dict.fromkeys(symbols) if s]
        if self.source == "synthetic":
            return {s: synthetic_ohlcv(s, bars=int(self.years * 252)) for s in symbols}

        out: dict[str, pd.DataFrame] = {}
        missing = []
        for s in symbols:
            cached = None if refresh else self._read_cache(s)
            if cached is not None:
                out[s] = cached
            else:
                missing.append(s)
        if missing:
            for s, df in self._download(missing).items():
                self._write_cache(s, df)
                out[s] = df
        return out

    # -- Yahoo Finance -------------------------------------------------------------------------
    def _download(self, symbols: list[str]) -> dict[str, pd.DataFrame]:
        try:
            import yfinance as yf
        except ImportError:
            log.error("yfinance is not installed: pip install -r requirements.txt")
            return {}
        log.info("Downloading %d symbols from Yahoo Finance", len(symbols))
        try:
            raw = yf.download(symbols, period=f"{self.years}y", interval="1d", auto_adjust=True,
                              group_by="ticker", progress=False, threads=True)
        except Exception as exc:  # network errors, API changes
            log.error("Download failed: %s", exc)
            return {}
        out = {}
        for s in symbols:
            df = _extract(raw, s)
            if df is None or df.empty:
                log.warning("No data for %s", s)
            else:
                out[s] = df
        return out

    def _cache_path(self, symbol: str) -> Path:
        return self.cache_dir / f"{re.sub(r'[^A-Za-z0-9]+', '_', symbol)}.pkl"

    def _read_cache(self, symbol: str) -> pd.DataFrame | None:
        path = self._cache_path(symbol)
        if not path.exists() or time.time() - path.stat().st_mtime > self.cache_hours * 3600:
            return None
        try:
            return pd.read_pickle(path)
        except Exception:
            return None

    def _write_cache(self, symbol: str, df: pd.DataFrame) -> None:
        try:
            self.cache_dir.mkdir(parents=True, exist_ok=True)
            df.to_pickle(self._cache_path(symbol))
        except OSError as exc:
            log.debug("Could not cache %s: %s", symbol, exc)


def _extract(raw: pd.DataFrame, symbol: str) -> pd.DataFrame | None:
    if raw is None or raw.empty:
        return None
    if isinstance(raw.columns, pd.MultiIndex):
        if symbol in raw.columns.get_level_values(0):
            df = raw[symbol]
        elif symbol in raw.columns.get_level_values(1):
            df = raw.xs(symbol, axis=1, level=1)
        else:
            return None
    else:
        df = raw
    return clean(df)


def clean(df: pd.DataFrame) -> pd.DataFrame | None:
    df = df.copy()
    df.columns = [str(c).title() for c in df.columns]
    if any(c not in df.columns for c in OHLCV):
        return None
    df = df[OHLCV].apply(pd.to_numeric, errors="coerce")
    df = df.dropna(subset=["Open", "High", "Low", "Close"])
    df["Volume"] = df["Volume"].fillna(0.0)
    index = pd.to_datetime(df.index)
    if index.tz is not None:
        index = index.tz_localize(None)
    df.index = index
    df = df[~df.index.duplicated(keep="last")].sort_index()
    df = df[df["Close"] > 0]
    return df.astype(float)


def synthetic_ohlcv(symbol: str, bars: int = 1500) -> pd.DataFrame:
    """Deterministic regime-switching market with volatility clustering (offline demo data)."""
    rng = np.random.default_rng(zlib.crc32(symbol.encode()))
    # Markov chain over up-trend / range / down-trend regimes with persistent states.
    trans = np.array([[0.990, 0.007, 0.003],
                      [0.008, 0.984, 0.008],
                      [0.004, 0.010, 0.986]])
    cum = trans.cumsum(axis=1)
    drift = np.array([0.0011, 0.0001, -0.0009]) * rng.uniform(0.6, 1.5)
    base_vol = 0.016 * rng.uniform(0.6, 1.6)

    u = rng.random(bars)
    shocks = rng.standard_normal(bars)
    state = int(rng.integers(0, 3))
    var = base_vol ** 2
    rets = np.empty(bars)
    sig = np.empty(bars)
    for t in range(bars):
        state = int(np.searchsorted(cum[state], u[t]))
        sig[t] = np.sqrt(var)
        rets[t] = drift[state] + sig[t] * shocks[t]
        var = 0.05 * base_vol ** 2 + 0.10 * rets[t] ** 2 + 0.85 * var  # GARCH(1,1)

    close = 20 * rng.uniform(1, 10) * np.exp(np.cumsum(rets))
    prev = np.r_[close[0], close[:-1]]
    open_ = prev * np.exp(rng.normal(0, 0.25, bars) * sig)
    wick = np.abs(rng.normal(0, 0.5, (2, bars))) * sig
    high = np.maximum(open_, close) * np.exp(wick[0])
    low = np.minimum(open_, close) * np.exp(-wick[1])
    volume = rng.lognormal(14, 0.3, bars) * (1 + 25 * np.abs(rets))

    end = pd.Timestamp.today().normalize()
    if symbol.upper().endswith("-USD"):
        index = pd.date_range(end=end, periods=bars, freq="D")
    else:
        index = pd.bdate_range(end=end, periods=bars)
    return pd.DataFrame({"Open": open_, "High": high, "Low": low, "Close": close, "Volume": volume},
                        index=index)


def bars_per_year(index: pd.Index) -> float:
    if len(index) < 2:
        return 252.0
    days = (index[-1] - index[0]).days
    return len(index) / (days / 365.25) if days > 0 else 252.0
