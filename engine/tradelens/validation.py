"""Anti-overfitting suite (mandatory before a strategy can be promoted).

* Walk-forward optimisation (rolling train/test windows; parameters chosen on train only)
* Purged & embargoed K-fold CV (López de Prado) for ML models
* Deflated Sharpe ratio (Bailey & López de Prado 2014)
* Probability of Backtest Overfitting via CSCV (Bailey et al. 2015)
* Monte Carlo: trade-order reshuffling and stationary bootstrap of returns → 5th-percentile drawdown/return
* Parameter-stability heatmap and a "plateau" score
"""
from __future__ import annotations

import itertools
import math
from dataclasses import dataclass, field
from typing import Callable

import numpy as np
import pandas as pd
from scipy.stats import norm

EULER = 0.5772156649


# ------------------------------------------------------------------ purged k-fold
def purged_kfold(n: int, n_splits: int = 5, horizon: int = 5, embargo: float = 0.01):
    """Yields (train_idx, test_idx). Training samples whose label window overlaps the test fold are purged,
    and an embargo of `embargo`×n samples after each test fold is removed."""
    idx = np.arange(n)
    folds = np.array_split(idx, n_splits)
    emb = int(math.ceil(n * embargo))
    for f in folds:
        lo, hi = f[0], f[-1]
        mask = np.ones(n, bool)
        mask[max(0, lo - horizon): min(n, hi + 1 + emb)] = False
        yield idx[mask], f


# ------------------------------------------------------------------ deflated sharpe
def sharpe(r: np.ndarray) -> float:
    sd = np.std(r, ddof=1)
    return float(np.mean(r) / sd) if sd > 0 else 0.0


def expected_max_sharpe(n_trials: int, var_sr: float) -> float:
    if n_trials <= 1:
        return 0.0
    return math.sqrt(var_sr) * ((1 - EULER) * norm.ppf(1 - 1 / n_trials) + EULER * norm.ppf(1 - 1 / (n_trials * math.e)))


def deflated_sharpe(returns: np.ndarray, n_trials: int, trial_sharpes: list[float] | None = None) -> float:
    """Probability that the true (per-period) Sharpe > the expected maximum of `n_trials` unskilled trials."""
    r = np.asarray(returns, float)
    r = r[~np.isnan(r)]
    T = len(r)
    if T < 10:
        return 0.0
    sr = sharpe(r)
    var_sr = np.var(trial_sharpes, ddof=1) if trial_sharpes and len(trial_sharpes) > 1 else (1 / T)
    sr0 = expected_max_sharpe(n_trials, var_sr)
    g3 = float(pd.Series(r).skew()); g4 = float(pd.Series(r).kurt()) + 3
    denom = math.sqrt(max(1 - g3 * sr + (g4 - 1) / 4 * sr * sr, 1e-12))
    return float(norm.cdf((sr - sr0) * math.sqrt(T - 1) / denom))


# ------------------------------------------------------------------ PBO (CSCV)
def pbo(perf: pd.DataFrame, n_blocks: int = 16) -> float:
    """perf: rows = time periods, columns = strategy configurations (per-period returns).
    Returns the probability that the in-sample best configuration ranks below the median out-of-sample."""
    m = perf.values
    T, N = m.shape
    if N < 2 or T < n_blocks:
        return 1.0
    blocks = np.array_split(np.arange(T), n_blocks)
    logits = []
    for combo in itertools.combinations(range(n_blocks), n_blocks // 2):
        is_idx = np.concatenate([blocks[i] for i in combo])
        oos_idx = np.concatenate([blocks[i] for i in range(n_blocks) if i not in combo])
        is_sr = np.array([sharpe(m[is_idx, j]) for j in range(N)])
        oos_sr = np.array([sharpe(m[oos_idx, j]) for j in range(N)])
        best = int(np.argmax(is_sr))
        rank = (oos_sr < oos_sr[best]).sum() + 0.5 * ((oos_sr == oos_sr[best]).sum() - 1)
        w = (rank + 1) / (N + 1)
        logits.append(math.log(w / (1 - w)))
        if len(logits) >= 2000:
            break
    return float(np.mean(np.array(logits) <= 0))


# ------------------------------------------------------------------ Monte Carlo
def _mdd(eq: np.ndarray) -> float:
    peak = np.maximum.accumulate(eq)
    return float(np.max((peak - eq) / peak))


def monte_carlo(trade_pnls: list[float], daily_returns: np.ndarray, capital: float, n: int = 2000, block: int = 10, seed: int = 7) -> dict:
    rng = np.random.default_rng(seed)
    pn = np.asarray(trade_pnls, float)
    dd_shuffle, ret_shuffle = [], []
    if len(pn):
        for _ in range(n):
            eq = capital + np.cumsum(rng.permutation(pn))
            eq = np.concatenate([[capital], eq])
            dd_shuffle.append(_mdd(np.maximum(eq, 1e-9))); ret_shuffle.append(eq[-1] / capital - 1)
    dr = np.asarray(daily_returns, float); dr = dr[~np.isnan(dr)]
    dd_boot, ret_boot = [], []
    if len(dr) > block:
        for _ in range(n):
            starts = rng.integers(0, len(dr) - block, size=len(dr) // block + 1)
            sample = np.concatenate([dr[s:s + block] for s in starts])[: len(dr)]
            eq = capital * np.cumprod(1 + sample)
            dd_boot.append(_mdd(eq)); ret_boot.append(eq[-1] / capital - 1)
    pct = lambda a, q: float(np.percentile(a, q)) if len(a) else float("nan")
    return {
        "shuffle_dd_p95": pct(dd_shuffle, 95) * 100, "shuffle_return_p5": pct(ret_shuffle, 5) * 100,
        "bootstrap_dd_p95": pct(dd_boot, 95) * 100, "bootstrap_return_p5": pct(ret_boot, 5) * 100,
        "prob_loss": float(np.mean(np.array(ret_boot) < 0)) if ret_boot else float("nan"),
    }


# ------------------------------------------------------------------ parameter stability
def param_heatmap(run: Callable[[dict], float], grid: dict[str, list]) -> pd.DataFrame:
    keys = list(grid)
    rows = []
    for combo in itertools.product(*grid.values()):
        p = dict(zip(keys, combo))
        rows.append({**p, "score": run(p)})
    return pd.DataFrame(rows)


def stability_score(heat: pd.DataFrame) -> float:
    """Share of the grid whose score is ≥ 50% of the best (positive) score. < 0.4 → narrow, fragile optimum."""
    s = heat["score"]
    best = s.max()
    if best <= 0:
        return 0.0
    return float((s >= 0.5 * best).mean())


# ------------------------------------------------------------------ walk-forward
@dataclass
class WalkForwardResult:
    oos_returns: pd.Series
    chosen: list[dict]
    windows: list[tuple]
    trial_returns: pd.DataFrame = field(default_factory=pd.DataFrame)


def walk_forward(evaluate: Callable[[dict, object, object], pd.Series], index: pd.DatetimeIndex, grid: dict[str, list],
                 train_years: float = 3, test_months: int = 6) -> WalkForwardResult:
    """evaluate(params, start, end) → daily returns of the strategy over [start, end].
    For each window the best-Sharpe parameter set on train is applied to the next test window."""
    combos = [dict(zip(grid, c)) for c in itertools.product(*grid.values())] or [{}]
    start = index[0] + pd.DateOffset(years=train_years)
    oos, chosen, windows, trials = [], [], [], {i: [] for i in range(len(combos))}
    t = start
    while t < index[-1]:
        tr0, tr1, te1 = t - pd.DateOffset(years=train_years), t, min(t + pd.DateOffset(months=test_months), index[-1])
        scores = []
        for i, p in enumerate(combos):
            r = evaluate(p, tr0, tr1)
            scores.append(sharpe(r.values) if len(r) else -9)
        best = combos[int(np.argmax(scores))]
        r_oos = evaluate(best, tr1, te1)
        oos.append(r_oos); chosen.append(best); windows.append((tr0, tr1, te1))
        for i, p in enumerate(combos):
            trials[i].append(evaluate(p, tr1, te1))
        t = te1
        if te1 >= index[-1]:
            break
    oos_r = pd.concat(oos) if oos else pd.Series(dtype=float)
    trial_df = pd.DataFrame({i: pd.concat(v) for i, v in trials.items() if v}) if trials else pd.DataFrame()
    return WalkForwardResult(oos_r[~oos_r.index.duplicated()], chosen, windows, trial_df)


# ------------------------------------------------------------------ acceptance
@dataclass
class Verdict:
    passed: bool
    reasons: list[str]
    stats: dict


def acceptance(daily_returns: pd.Series, trades: list[dict], pbo_value: float, dsr: float, stability: float, mc: dict,
               min_years: float = 5, min_trades: int = 200, max_pbo: float = 0.3, min_dsr: float = 0.9, max_dd_p95: float = 25.0) -> Verdict:
    reasons = []
    years = (daily_returns.index[-1] - daily_returns.index[0]).days / 365.25 if len(daily_returns) > 1 else 0
    if years < min_years: reasons.append(f"only {years:.1f} years of data (< {min_years})")
    if len(trades) < min_trades: reasons.append(f"only {len(trades)} trades (< {min_trades})")
    if pbo_value > max_pbo: reasons.append(f"PBO {pbo_value:.2f} > {max_pbo}")
    if dsr < min_dsr: reasons.append(f"deflated Sharpe probability {dsr:.2f} < {min_dsr}")
    if stability < 0.4: reasons.append(f"parameter stability {stability:.2f} < 0.40 (narrow optimum)")
    if mc.get("bootstrap_dd_p95", 0) > max_dd_p95: reasons.append(f"Monte-Carlo 95th-pct drawdown {mc['bootstrap_dd_p95']:.1f}% > {max_dd_p95}%")
    return Verdict(not reasons, reasons, {"years": years, "trades": len(trades), "pbo": pbo_value, "dsr": dsr, "stability": stability, **mc})
