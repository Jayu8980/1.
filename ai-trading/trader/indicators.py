"""Technical indicators (pure functions over pandas objects) and the per-symbol indicator frame."""
from __future__ import annotations

import numpy as np
import pandas as pd


def sma(s: pd.Series, n: int) -> pd.Series:
    return s.rolling(n).mean()


def ema(s: pd.Series, n: int) -> pd.Series:
    return s.ewm(span=n, adjust=False, min_periods=n).mean()


def wilder(s: pd.Series, n: int) -> pd.Series:
    return s.ewm(alpha=1 / n, adjust=False, min_periods=n).mean()


def rsi(close: pd.Series, n: int = 14) -> pd.Series:
    delta = close.diff()
    gain = wilder(delta.clip(lower=0), n)
    loss = wilder(-delta.clip(upper=0), n)
    out = 100 - 100 / (1 + gain / loss)
    return out.where(loss != 0, 100.0)


def macd(close: pd.Series, fast: int = 12, slow: int = 26, signal: int = 9):
    line = ema(close, fast) - ema(close, slow)
    sig = ema(line, signal)
    return line, sig, line - sig


def true_range(df: pd.DataFrame) -> pd.Series:
    prev = df["Close"].shift()
    parts = [df["High"] - df["Low"], (df["High"] - prev).abs(), (df["Low"] - prev).abs()]
    return pd.concat(parts, axis=1).max(axis=1)


def atr(df: pd.DataFrame, n: int = 14) -> pd.Series:
    return wilder(true_range(df), n)


def adx(df: pd.DataFrame, n: int = 14):
    up = df["High"].diff()
    down = -df["Low"].diff()
    plus_dm = pd.Series(np.where((up > down) & (up > 0), up, 0.0), index=df.index)
    minus_dm = pd.Series(np.where((down > up) & (down > 0), down, 0.0), index=df.index)
    tr = wilder(true_range(df), n)
    plus_di = 100 * wilder(plus_dm, n) / tr
    minus_di = 100 * wilder(minus_dm, n) / tr
    dx = 100 * (plus_di - minus_di).abs() / (plus_di + minus_di).replace(0, np.nan)
    return wilder(dx, n), plus_di, minus_di


def bollinger(close: pd.Series, n: int = 20, k: float = 2.0):
    mid = sma(close, n)
    sd = close.rolling(n).std()
    upper, lower = mid + k * sd, mid - k * sd
    pct_b = (close - lower) / (upper - lower).replace(0, np.nan)
    width = (upper - lower) / mid
    return mid, upper, lower, pct_b, width


def stochastic(df: pd.DataFrame, n: int = 14, d: int = 3):
    lo = df["Low"].rolling(n).min()
    hi = df["High"].rolling(n).max()
    k = 100 * (df["Close"] - lo) / (hi - lo).replace(0, np.nan)
    return k, k.rolling(d).mean()


def williams_r(df: pd.DataFrame, n: int = 14) -> pd.Series:
    hi = df["High"].rolling(n).max()
    lo = df["Low"].rolling(n).min()
    return -100 * (hi - df["Close"]) / (hi - lo).replace(0, np.nan)


def cci(df: pd.DataFrame, n: int = 20) -> pd.Series:
    tp = (df["High"] + df["Low"] + df["Close"]) / 3
    mean = tp.rolling(n).mean()
    mad = tp.rolling(n).apply(lambda x: np.abs(x - x.mean()).mean(), raw=True)
    return (tp - mean) / (0.015 * mad.replace(0, np.nan))


def obv(df: pd.DataFrame) -> pd.Series:
    return (np.sign(df["Close"].diff()).fillna(0) * df["Volume"]).cumsum()


def mfi(df: pd.DataFrame, n: int = 14) -> pd.Series:
    tp = (df["High"] + df["Low"] + df["Close"]) / 3
    flow = tp * df["Volume"]
    pos = flow.where(tp > tp.shift(), 0.0).rolling(n).sum()
    neg = flow.where(tp < tp.shift(), 0.0).rolling(n).sum()
    return 100 - 100 / (1 + pos / neg.replace(0, np.nan))


def cmf(df: pd.DataFrame, n: int = 20) -> pd.Series:
    rng = (df["High"] - df["Low"]).replace(0, np.nan)
    mult = ((df["Close"] - df["Low"]) - (df["High"] - df["Close"])) / rng
    return (mult.fillna(0) * df["Volume"]).rolling(n).sum() / df["Volume"].rolling(n).sum().replace(0, np.nan)


def supertrend(df: pd.DataFrame, n: int = 10, mult: float = 3.0) -> pd.Series:
    """+1 while the SuperTrend is bullish, -1 while bearish."""
    a = atr(df, n).to_numpy()
    hl2 = ((df["High"] + df["Low"]) / 2).to_numpy()
    c = df["Close"].to_numpy()
    upper, lower = hl2 + mult * a, hl2 - mult * a
    fu, fl = upper.copy(), lower.copy()
    direction = np.ones(len(c))
    for i in range(1, len(c)):
        fu[i] = upper[i] if (np.isnan(fu[i - 1]) or upper[i] < fu[i - 1] or c[i - 1] > fu[i - 1]) else fu[i - 1]
        fl[i] = lower[i] if (np.isnan(fl[i - 1]) or lower[i] > fl[i - 1] or c[i - 1] < fl[i - 1]) else fl[i - 1]
        if direction[i - 1] > 0 and c[i] < fl[i]:
            direction[i] = -1
        elif direction[i - 1] < 0 and c[i] > fu[i]:
            direction[i] = 1
        else:
            direction[i] = direction[i - 1]
    return pd.Series(direction, index=df.index)


def lr_slope(close: pd.Series, n: int = 50) -> pd.Series:
    """Slope of a least-squares line through log prices (log return per bar)."""
    x = np.arange(n) - (n - 1) / 2
    denom = (x ** 2).sum()
    return np.log(close).rolling(n).apply(lambda w: np.dot(x, w - w.mean()) / denom, raw=True)


def resample(df: pd.DataFrame, rule: str) -> pd.DataFrame:
    agg = {"Open": "first", "High": "max", "Low": "min", "Close": "last", "Volume": "sum"}
    return df.resample(rule).agg(agg).dropna(subset=["Close"])


def compute_all(df: pd.DataFrame, bench_close: pd.Series | None = None) -> pd.DataFrame:
    """Every indicator the agents use, one column each, aligned with ``df``."""
    c, h, l, v = df["Close"], df["High"], df["Low"], df["Volume"]
    out = pd.DataFrame(index=df.index)
    out["open"], out["high"], out["low"], out["close"], out["volume"] = df["Open"], h, l, c, v
    for n in (20, 50, 200):
        out[f"sma{n}"] = sma(c, n)
    for n in (9, 20, 21, 50):
        out[f"ema{n}"] = ema(c, n)
    out["rsi2"] = rsi(c, 2)
    out["rsi14"] = rsi(c, 14)
    out["macd"], out["macd_signal"], out["macd_hist"] = macd(c)
    out["atr"] = atr(df, 14)
    out["adx"], out["plus_di"], out["minus_di"] = adx(df, 14)
    out["bb_mid"], out["bb_up"], out["bb_low"], out["bb_pctb"], out["bb_width"] = bollinger(c)
    out["stoch_k"], out["stoch_d"] = stochastic(df)
    out["willr"] = williams_r(df)
    out["cci"] = cci(df)
    out["obv"] = obv(df)
    out["obv_ema20"] = ema(out["obv"], 20)
    out["mfi"] = mfi(df)
    out["cmf"] = cmf(df)
    out["st_dir"] = supertrend(df)
    for n in (20, 55):  # Donchian channel of the *previous* n bars
        out[f"dc_high{n}"] = h.rolling(n).max().shift(1)
        out[f"dc_low{n}"] = l.rolling(n).min().shift(1)
    out["vol_avg20"] = v.rolling(20).mean()
    out["vol_ratio"] = v / out["vol_avg20"].replace(0, np.nan)
    out["vol_z"] = (v - out["vol_avg20"]) / v.rolling(20).std().replace(0, np.nan)
    rets = c.pct_change()
    out["ret1"] = rets
    out["ret10"] = c.pct_change(10)
    out["vol21"] = rets.rolling(21).std()
    out["vol63"] = rets.rolling(63).std()
    out["lr_slope50"] = lr_slope(c, 50)
    if bench_close is not None and len(bench_close):
        b = bench_close.reindex(df.index).ffill()
        out["rs21"] = c.pct_change(21) - b.pct_change(21)
        out["rs63"] = c.pct_change(63) - b.pct_change(63)
    else:
        out["rs21"] = 0.0
        out["rs63"] = 0.0
    return out.replace([np.inf, -np.inf], np.nan)
