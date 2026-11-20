"""Local parquet cache of OHLCV bars (one file per symbol/timeframe)."""
from __future__ import annotations

from pathlib import Path

import pandas as pd

from ..config import CONFIG


def _path(source: str, symbol: str, tf: str) -> Path:
    safe = symbol.replace("^", "IDX_").replace("/", "_").replace(":", "_").replace(" ", "_")
    p = CONFIG.data_dir / source / tf
    p.mkdir(parents=True, exist_ok=True)
    return p / f"{safe}.parquet"


def load(source: str, symbol: str, tf: str) -> pd.DataFrame | None:
    p = _path(source, symbol, tf)
    return pd.read_parquet(p) if p.exists() else None


def save(source: str, symbol: str, tf: str, df: pd.DataFrame) -> None:
    old = load(source, symbol, tf)
    if old is not None and not old.empty:
        df = pd.concat([old, df])
        df = df[~df.index.duplicated(keep="last")].sort_index()
    df.to_parquet(_path(source, symbol, tf))
