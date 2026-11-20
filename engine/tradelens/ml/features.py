"""Feature engineering. The first 12 columns (FEATURES) MUST match android-app domain/Features.kt so the
exported ONNX model scores identically on the phone."""
from __future__ import annotations

import numpy as np
import pandas as pd

from ..core.strategy import adx, atr, ema, rsi

FEATURES = ["ret_1", "ret_5", "ret_20", "vol_20", "rsi_14", "macd_hist_norm", "adx_14", "atr_pct", "bb_pos", "ema_20_50", "price_ema_200", "vol_ratio_20"]
EXTRA = ["oi_chg_5", "sector_rel_20", "vix_level", "vix_chg_5"]


def build(df: pd.DataFrame, sector: pd.Series | None = None, vix: pd.Series | None = None) -> pd.DataFrame:
    c = df["close"]
    lr = np.log(c).diff()
    m = ema(c, 12) - ema(c, 26)
    hist = m - m.ewm(span=9, adjust=False).mean()
    mid = c.rolling(20).mean(); sd = c.rolling(20).std(ddof=0)
    up, lo = mid + 2 * sd, mid - 2 * sd
    e200 = ema(c, 200)
    f = pd.DataFrame(index=df.index)
    f["ret_1"] = np.log(c / c.shift(1)); f["ret_5"] = np.log(c / c.shift(5)); f["ret_20"] = np.log(c / c.shift(20))
    f["vol_20"] = lr.rolling(20).std(ddof=0)
    f["rsi_14"] = rsi(c, 14) / 100
    f["macd_hist_norm"] = hist / c
    f["adx_14"] = adx(df, 14) / 100
    f["atr_pct"] = atr(df, 14) / c
    f["bb_pos"] = ((c - lo) / (up - lo)).where(up > lo, 0.5)
    f["ema_20_50"] = ema(c, 20) / ema(c, 50) - 1
    f["price_ema_200"] = (c / e200 - 1).fillna(0)
    f["vol_ratio_20"] = df["volume"] / df["volume"].shift(1).rolling(20).mean()
    f["oi_chg_5"] = df["oi"].pct_change(5) if "oi" in df else 0.0
    f["sector_rel_20"] = (f["ret_20"] - np.log(sector / sector.shift(20)).reindex(f.index)) if sector is not None else 0.0
    f["vix_level"] = (vix.reindex(f.index).ffill() / 100) if vix is not None else 0.0
    f["vix_chg_5"] = (vix.reindex(f.index).ffill().pct_change(5)) if vix is not None else 0.0
    return f.replace([np.inf, -np.inf], np.nan)


def label(df: pd.DataFrame, horizon: int = 5) -> pd.Series:
    """1 if the forward `horizon`-bar return is positive. The last `horizon` labels are NaN (unknown)."""
    fwd = df["close"].shift(-horizon) / df["close"] - 1
    return (fwd > 0).astype(float).where(fwd.notna())
