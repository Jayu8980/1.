"""Trade-setup templates, a fast trade simulator and the parameter search used by the optimizer.

Every setup is a rule that fires a signal at the close of a bar. A trade is entered at the next
bar's open with an ATR-based stop and a target at ``reward_risk`` times the stop distance, and is
closed at the stop, the target or after ``max_hold`` bars. Results are measured in R (multiples of
the amount risked), which makes symbols with different prices and volatility comparable.
"""
from __future__ import annotations

import itertools
import math

import numpy as np
import pandas as pd

# setup name -> parameter grid
SETUPS: dict[str, dict[str, list]] = {
    "trend_pullback": {"rsi2": [5, 10, 20]},           # buy short-term dips inside an up-trend
    "breakout": {"lookback": [20, 55], "vol_mult": [1.0, 1.5]},  # close beyond the N-bar range
    "momentum": {"threshold": [0.3, 0.45, 0.6]},       # indicator confluence crosses a threshold
    "mean_reversion": {"rsi": [25, 30, 35]},           # stretched below the Bollinger band
    "supertrend_flip": {},                             # SuperTrend turns in our direction
    "macd_cross": {},                                  # MACD crosses its signal line with the trend
}

WARMUP = 200  # bars before the slowest indicator (SMA200) is valid


def expand(grid: dict[str, list]) -> list[dict]:
    keys = list(grid)
    return [dict(zip(keys, values)) for values in itertools.product(*(grid[k] for k in keys))]


def describe(setup: str, params: dict) -> str:
    inner = ", ".join(f"{k}={v}" for k, v in params.items())
    return f"{setup}({inner})" if inner else setup


def setup_signal(name: str, p: dict, ind: pd.DataFrame, score: pd.Series, d: int) -> np.ndarray:
    """Boolean array: True where the setup fires at the close of that bar (d=+1 long, -1 short)."""
    c = ind["close"]
    if name == "trend_pullback":
        if d > 0:
            sig = (c > ind["sma200"]) & (ind["sma50"] > ind["sma200"]) & (ind["rsi2"] < p["rsi2"])
        else:
            sig = (c < ind["sma200"]) & (ind["sma50"] < ind["sma200"]) & (ind["rsi2"] > 100 - p["rsi2"])
    elif name == "breakout":
        n = p["lookback"]
        crossed = c > ind[f"dc_high{n}"] if d > 0 else c < ind[f"dc_low{n}"]
        no_volume = ind["vol_ratio"].isna()  # indices report no volume: judge them on price alone
        sig = crossed & ((ind["vol_ratio"] >= p["vol_mult"]) | no_volume)
    elif name == "momentum":
        s = score * d
        sig = (s >= p["threshold"]) & (s.shift(1) < p["threshold"])
    elif name == "mean_reversion":
        if d > 0:
            sig = (c < ind["bb_low"]) & (ind["rsi14"] < p["rsi"]) & (c > ind["sma200"])
        else:
            sig = (c > ind["bb_up"]) & (ind["rsi14"] > 100 - p["rsi"]) & (c < ind["sma200"])
    elif name == "supertrend_flip":
        st = ind["st_dir"]
        sig = (st == d) & (st.shift(1) == -d)
    elif name == "macd_cross":
        m, s = ind["macd"], ind["macd_signal"]
        if d > 0:
            sig = (m > s) & (m.shift(1) <= s.shift(1)) & (c > ind["sma200"])
        else:
            sig = (m < s) & (m.shift(1) >= s.shift(1)) & (c < ind["sma200"])
    else:
        raise ValueError(f"unknown setup {name}")
    return sig.fillna(False).to_numpy(dtype=bool)


def trade_exit(o, h, l, c, e, last, d, stop, target):
    """Walk bars e..last of a trade entered at the open of bar e. Returns (exit price, exit bar).

    When the stop and the target are both inside one bar we assume the stop came first, and a gap
    through a level fills at the open: both are conservative choices. Without a hit the trade is
    closed at the close of bar ``last`` (time exit).
    """
    for j in range(e, last + 1):
        if d > 0:
            if l[j] <= stop:
                return (stop if j == e else min(o[j], stop)), j
            if h[j] >= target:
                return (target if j == e else max(o[j], target)), j
        else:
            if h[j] >= stop:
                return (stop if j == e else max(o[j], stop)), j
            if l[j] <= target:
                return (target if j == e else min(o[j], target)), j
    return c[last], last


def simulate(o, h, l, c, atr, signal_idx, d, stop_atr, rr, max_hold, fee=0.0005):
    """Simulate one-position-at-a-time trades. Returns (R multiples, entry bar indices)."""
    n = len(c)
    rs, entries = [], []
    free_from = 0
    for i in signal_idx:
        if i < free_from:
            continue
        e = i + 1
        if e >= n:
            break
        a = atr[i]
        if not a > 0:
            continue
        entry = o[e]
        risk = stop_atr * a
        exit_px, exit_bar = trade_exit(o, h, l, c, e, min(e + max_hold - 1, n - 1), d,
                                       entry - d * risk, entry + d * rr * risk)
        rs.append(d * (exit_px - entry) / risk - fee * (entry + exit_px) / risk)
        entries.append(e)
        free_from = exit_bar
    return np.asarray(rs, dtype=float), np.asarray(entries, dtype=int)


def trade_outcomes(ind: pd.DataFrame, d: int, stop_atr: float, rr: float, max_hold: int,
                   fee: float = 0.0005) -> pd.Series:
    """R multiple of the trade that a signal at the close of each bar would have produced.

    NaN for the last bars, whose outcome is not known yet.
    """
    o, h, l, c = (ind[k].to_numpy() for k in ("open", "high", "low", "close"))
    atr = ind["atr"].to_numpy()
    n = len(c)
    out = np.full(n, np.nan)
    for i in range(n - max_hold - 1):
        a = atr[i]
        if not a > 0:
            continue
        e = i + 1
        entry = o[e]
        risk = stop_atr * a
        exit_px, _ = trade_exit(o, h, l, c, e, e + max_hold - 1, d, entry - d * risk, entry + d * rr * risk)
        out[i] = d * (exit_px - entry) / risk - fee * (entry + exit_px) / risk
    return pd.Series(out, index=ind.index)


def stats(r: np.ndarray) -> dict:
    n = len(r)
    if n == 0:
        return {"trades": 0, "win_rate": 0.0, "expectancy": 0.0, "profit_factor": 0.0,
                "total_r": 0.0, "max_dd_r": 0.0, "sqn": 0.0}
    wins, losses = r[r > 0], r[r <= 0]
    loss_sum = -losses.sum()
    pf = wins.sum() / loss_sum if loss_sum > 0 else (99.0 if wins.sum() > 0 else 0.0)
    equity = np.r_[0.0, np.cumsum(r)]
    max_dd = float((np.maximum.accumulate(equity) - equity).max())
    sd = r.std(ddof=1) if n > 1 else 0.0
    sqn = math.sqrt(min(n, 100)) * r.mean() / sd if sd > 0 else 0.0
    return {"trades": n, "win_rate": float(len(wins) / n), "expectancy": float(r.mean()),
            "profit_factor": float(min(pf, 99.0)), "total_r": float(r.sum()), "max_dd_r": max_dd,
            "sqn": float(sqn)}


def optimize(ind: pd.DataFrame, score: pd.Series, d: int, oc) -> list[dict]:
    """Grid-search every setup x exit combination; validate the in-sample winner out of sample.

    Returns one entry per setup variant (setup + its parameters), carrying the best exit settings
    chosen on the in-sample period only, their in-sample and out-of-sample statistics, how robust
    the variant is to the exit settings, whether it is validated and whether it is firing now.
    """
    o, h, l, c = (ind[k].to_numpy() for k in ("open", "high", "low", "close"))
    atr = ind["atr"].to_numpy()
    n = len(c)
    split = int(n * oc.in_sample_frac)
    exits = list(itertools.product(oc.stop_atr, oc.reward_risk, oc.max_hold))
    variants = []
    for name, grid in SETUPS.items():
        for params in expand(grid):
            sig = setup_signal(name, params, ind, score, d)
            idx = np.flatnonzero(sig[: n - 1])
            idx = idx[idx >= WARMUP]
            active = bool(sig[n - max(1, oc.signal_freshness):].any())
            best, evaluated, profitable = None, 0, 0
            for stop_atr, rr, hold in exits:
                r, entries = simulate(o, h, l, c, atr, idx, d, stop_atr, rr, hold, oc.fee_pct)
                is_mask = entries < split
                st_is = stats(r[is_mask])
                if st_is["trades"] < oc.min_trades_is:
                    continue
                evaluated += 1
                profitable += st_is["expectancy"] > 0
                if best is None or st_is["sqn"] > best["in_sample"]["sqn"]:
                    best = {"stop_atr": stop_atr, "reward_risk": rr, "max_hold": hold,
                            "in_sample": st_is, "out_of_sample": stats(r[~is_mask]), "all": stats(r)}
            if best is None:
                continue
            variant = {"setup": name, "params": params, "label": describe(name, params),
                       "active": active, "robustness": profitable / evaluated, **best}
            variant["validated"], variant["why"] = _validate(variant, oc)
            variants.append(variant)
    variants.sort(key=lambda v: v["in_sample"]["sqn"], reverse=True)
    return variants


def _validate(v: dict, oc) -> tuple[bool, str]:
    ins, oos = v["in_sample"], v["out_of_sample"]
    if ins["expectancy"] <= 0:
        return False, "no in-sample edge"
    if oos["trades"] < oc.min_trades_oos:
        return False, f"only {oos['trades']} out-of-sample trades"
    if oos["expectancy"] < oc.min_expectancy_oos:
        return False, f"out-of-sample expectancy {oos['expectancy']:+.2f}R"
    if oos["profit_factor"] < oc.min_profit_factor_oos:
        return False, f"out-of-sample profit factor {oos['profit_factor']:.2f}"
    if oos["max_dd_r"] > oc.max_drawdown_r:
        return False, f"out-of-sample drawdown {oos['max_dd_r']:.1f}R"
    if v["robustness"] < oc.min_robustness:
        return False, f"fragile: only {v['robustness']:.0%} of exit settings profitable"
    return True, "validated"
