"""NSE public files: trading holidays, index constituents (with sectors) and provisional FII/DII flows."""
from __future__ import annotations

import io
import json
from datetime import date

import httpx
import pandas as pd

from ..config import CONFIG

UA = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/140 Safari/537.36", "Accept-Language": "en-US,en;q=0.9"}

# NSE equity trading holidays 2026 (fallback when the NSE API is unreachable).
HOLIDAYS_2026 = ["2026-01-26", "2026-03-03", "2026-03-26", "2026-03-31", "2026-04-03", "2026-04-14", "2026-05-01",
                 "2026-05-28", "2026-06-26", "2026-09-14", "2026-10-02", "2026-10-20", "2026-11-10", "2026-11-24", "2026-12-25"]


def _session() -> httpx.Client:
    c = httpx.Client(headers=UA, timeout=20, follow_redirects=True)
    try:
        c.get("https://www.nseindia.com")  # sets cookies required by the JSON APIs
    except httpx.HTTPError:
        pass
    return c


def holidays() -> list[str]:
    cache = CONFIG.state_dir / "holidays.json"
    try:
        with _session() as c:
            j = c.get("https://www.nseindia.com/api/holiday-master", params={"type": "trading"}).json()
        days = sorted({pd.to_datetime(h["tradingDate"], format="%d-%b-%Y").date().isoformat() for h in j.get("CM", [])})
        if days:
            cache.write_text(json.dumps(days))
            return days
    except Exception:
        pass
    return json.loads(cache.read_text()) if cache.exists() else HOLIDAYS_2026


def is_trading_day(d: date, hol: set[str] | None = None) -> bool:
    return d.weekday() < 5 and d.isoformat() not in (hol or set(holidays()))


def constituents(index: str = "nifty500") -> pd.DataFrame:
    """Symbol, Company Name, Industry for an NSE index (current members)."""
    cache = CONFIG.data_dir / f"{index}.csv"
    try:
        r = httpx.get(f"https://archives.nseindia.com/content/indices/ind_{index}list.csv", headers=UA, timeout=30)
        r.raise_for_status()
        df = pd.read_csv(io.StringIO(r.text))
        df.to_csv(cache, index=False)
        return df
    except Exception:
        if cache.exists():
            return pd.read_csv(cache)
        raise


def fii_dii() -> list[dict]:
    """Provisional cash-market FII/DII net (₹ crore)."""
    with _session() as c:
        rows = c.get("https://www.nseindia.com/api/fiidiiTradeReact").json()
    out: dict[str, dict] = {}
    for r in rows:
        d = out.setdefault(r["date"], {"date": r["date"], "fiiNet": 0.0, "diiNet": 0.0})
        net = float(str(r.get("netValue", "0")).replace(",", ""))
        if "FII" in r.get("category", "").upper() or "FPI" in r.get("category", "").upper():
            d["fiiNet"] = net
        else:
            d["diiNet"] = net
    return list(out.values())
