"""Performance metrics reported for every strategy (vs NIFTY 50 TRI, or BTC for crypto)."""
from __future__ import annotations

import math

import numpy as np
import pandas as pd

NIFTY_DIV_YIELD = 0.012  # ~1.2%/yr; approximates NIFTY 50 TRI from the price index when TRI data is unavailable


def tri_from_price(price: pd.Series, div_yield: float = NIFTY_DIV_YIELD, periods: int = 252) -> pd.Series:
    r = price.pct_change().fillna(0) + div_yield / periods
    return price.iloc[0] * (1 + r).cumprod()


def max_drawdown(equity: pd.Series) -> float:
    peak = equity.cummax()
    return float(((peak - equity) / peak).max()) if len(equity) else 0.0


def drawdown_series(equity: pd.Series) -> pd.Series:
    return -(equity.cummax() - equity) / equity.cummax()


def summary(equity: pd.Series, trades: list[dict] | None = None, bench: pd.Series | None = None, periods: int = 252,
            turnover: float | None = None) -> dict:
    trades = trades or []
    r = equity.pct_change().dropna()
    if len(r) < 2:
        return {k: 0.0 for k in ("cagr", "sharpe", "sortino", "calmar", "maxDd", "winRate", "profitFactor", "expectancy", "alpha", "beta", "ir", "turnover")} | {"trades": 0}
    years = max((equity.index[-1] - equity.index[0]).days / 365.25, 1 / periods) if isinstance(equity.index, pd.DatetimeIndex) else len(r) / periods
    cagr = (equity.iloc[-1] / equity.iloc[0]) ** (1 / years) - 1
    sd = r.std(ddof=0)
    dsd = math.sqrt((r.clip(upper=0) ** 2).mean())
    mdd = max_drawdown(equity)
    pnl = np.array([t["pnl"] for t in trades]) if trades else np.array([])
    wins, losses = pnl[pnl > 0], pnl[pnl <= 0]
    out = {
        "totalReturn": float(equity.iloc[-1] / equity.iloc[0] - 1) * 100,
        "cagr": cagr * 100,
        "sharpe": float(r.mean() / sd * math.sqrt(periods)) if sd > 0 else 0.0,
        "sortino": float(r.mean() / dsd * math.sqrt(periods)) if dsd > 0 else 0.0,
        "maxDd": mdd * 100,
        "calmar": float(cagr / mdd) if mdd > 0 else 0.0,
        "winRate": float(len(wins) / len(pnl) * 100) if len(pnl) else 0.0,
        "profitFactor": float(wins.sum() / -losses.sum()) if len(losses) and losses.sum() < 0 else (float("inf") if len(wins) else 0.0),
        "expectancy": float(pnl.mean()) if len(pnl) else 0.0,
        "trades": int(len(pnl)),
        "turnover": float(turnover or 0.0),
        "alpha": 0.0, "beta": 0.0, "ir": 0.0,
    }
    if bench is not None and len(bench) > 2:
        b = bench.reindex(equity.index).ffill().pct_change()
        df = pd.concat([r, b], axis=1, keys=["s", "b"]).dropna()
        if len(df) > 2 and df["b"].var() > 0:
            beta = df["s"].cov(df["b"]) / df["b"].var()
            out["beta"] = float(beta)
            out["alpha"] = float((df["s"].mean() - beta * df["b"].mean()) * periods * 100)
            act = df["s"] - df["b"]
            out["ir"] = float(act.mean() / act.std(ddof=0) * math.sqrt(periods)) if act.std(ddof=0) > 0 else 0.0
    return out


def monthly_returns(equity: pd.Series) -> dict[str, float]:
    m = equity.resample("ME").last().pct_change().dropna()
    first = equity.resample("ME").last()
    if len(first):
        m = pd.concat([pd.Series([first.iloc[0] / equity.iloc[0] - 1], index=[first.index[0]]), m])
    return {d.strftime("%Y-%m"): float(v * 100) for d, v in m.items()}
