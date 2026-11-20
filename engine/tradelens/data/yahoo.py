"""Yahoo Finance chart endpoint (free, delayed). Used for research history and as the live fallback."""
from __future__ import annotations

import time

import httpx
import pandas as pd

from . import store

UA = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/140 Safari/537.36"}
INTERVALS = {"1m": "1m", "5m": "5m", "15m": "15m", "1h": "60m", "1D": "1d", "1W": "1wk"}
INDEX = {"NIFTY 50": "^NSEI", "NIFTY": "^NSEI", "BANKNIFTY": "^NSEBANK", "INDIA VIX": "^INDIAVIX", "SENSEX": "^BSESN",
         "NIFTY 50 TRI": "^NSEI"}  # Yahoo has no TRI series; reports add the dividend yield (see metrics.tri_from_price)


def ticker(symbol: str) -> str:
    s = symbol.upper()
    if s in INDEX:
        return INDEX[s]
    if s.startswith("^") or s.endswith(".NS") or s.endswith(".BO") or "-" in s and s.endswith("USD"):
        return s
    return f"{s}.NS"


def download(symbol: str, tf: str = "1D", start: int | None = None, end: int | None = None, use_cache: bool = True) -> pd.DataFrame:
    """Returns OHLCV indexed by UTC timestamp. Retries with backoff on 429/5xx."""
    end = end or int(time.time())
    start = start or end - 20 * 365 * 86400
    t = ticker(symbol)
    if use_cache:
        cached = store.load("yahoo", t, tf)
        if cached is not None and not cached.empty and cached.index[0].timestamp() <= start + 7 * 86400 and cached.index[-1].timestamp() >= end - 3 * 86400:
            return cached[(cached.index >= pd.Timestamp(start, unit="s", tz="UTC")) & (cached.index <= pd.Timestamp(end, unit="s", tz="UTC"))]
    url = f"https://query1.finance.yahoo.com/v8/finance/chart/{t}"
    params = {"interval": INTERVALS[tf], "period1": start, "period2": end, "includePrePost": "false", "events": "div,split"}
    backoff = 1.0
    for _ in range(5):
        r = httpx.get(url, params=params, headers=UA, timeout=30)
        if r.status_code in (429, 500, 502, 503):
            time.sleep(backoff); backoff *= 2; continue
        r.raise_for_status()
        break
    res = r.json()["chart"]["result"]
    if not res:
        return pd.DataFrame(columns=["open", "high", "low", "close", "volume"])
    res = res[0]
    q = res["indicators"]["quote"][0]
    df = pd.DataFrame({k: q.get(k) for k in ("open", "high", "low", "close", "volume")}, index=pd.to_datetime(res.get("timestamp", []), unit="s", utc=True))
    adj = res["indicators"].get("adjclose")
    if adj and tf in ("1D", "1W"):
        # split/dividend-adjust OHLC so returns are total-return-like for equities
        ratio = pd.Series(adj[0]["adjclose"], index=df.index) / df["close"]
        for c in ("open", "high", "low", "close"):
            df[c] = df[c] * ratio
    df = df.dropna(subset=["close"]).astype(float)
    if use_cache and not df.empty:
        store.save("yahoo", t, tf, df)
    return df
