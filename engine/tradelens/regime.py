"""Market regime detection: trending_up, trending_down, range, high_volatility, crash.

Inputs: index ADX, 200-DMA slope, India VIX, realised volatility, breadth (% of universe above 50-DMA and the
advance/decline ratio) and optionally FII flows. A Gaussian HMM on (return, realised vol) supplies state
probabilities; transparent rules map features + HMM state to a label each day.
"""
from __future__ import annotations

import warnings

import numpy as np
import pandas as pd

from .core.strategy import adx

REGIMES = ("trending_up", "trending_down", "range", "high_volatility", "crash")


def features(index: pd.DataFrame, vix: pd.Series | None = None, universe_closes: pd.DataFrame | None = None,
             fii: pd.Series | None = None) -> pd.DataFrame:
    c = index["close"]
    f = pd.DataFrame(index=index.index)
    f["ret"] = np.log(c).diff()
    f["rv20"] = f["ret"].rolling(20).std() * np.sqrt(252)
    f["adx"] = adx(index, 14)
    sma200 = c.rolling(200).mean()
    f["slope200"] = sma200.pct_change(20)
    f["above200"] = (c > sma200).astype(float)
    f["dd60"] = c / c.rolling(60).max() - 1
    f["vix"] = vix.reindex(f.index).ffill() if vix is not None else f["rv20"] * 100
    if universe_closes is not None and not universe_closes.empty:
        u = universe_closes.reindex(f.index).ffill()
        f["breadth50"] = (u > u.rolling(50).mean()).mean(axis=1)
        chg = u.pct_change()
        adv = (chg > 0).sum(axis=1); dec = (chg < 0).sum(axis=1)
        f["adratio"] = (adv / dec.replace(0, np.nan)).rolling(5).mean()
    else:
        f["breadth50"] = f["above200"]
        f["adratio"] = 1.0
    f["fii5"] = fii.reindex(f.index).fillna(0).rolling(5).sum() if fii is not None else 0.0
    return f


def fit_hmm(f: pd.DataFrame, n_states: int = 3, seed: int = 7):
    from hmmlearn.hmm import GaussianHMM
    x = f[["ret", "rv20"]].dropna()
    if len(x) < 250:
        return None, None
    m = GaussianHMM(n_components=n_states, covariance_type="full", n_iter=200, random_state=seed)
    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        m.fit(x.values)
    # state with the highest mean realised vol = "stress" state
    stress = int(np.argmax(m.means_[:, 1]))
    return m, stress


def classify(f: pd.DataFrame, hmm=None, stress_state: int | None = None) -> pd.DataFrame:
    out = pd.DataFrame(index=f.index)
    p_stress = pd.Series(0.0, index=f.index)
    if hmm is not None:
        x = f[["ret", "rv20"]].dropna()
        probs = hmm.predict_proba(x.values)
        p_stress.loc[x.index] = probs[:, stress_state]
    vix_hi = f["vix"].rolling(252, min_periods=60).quantile(0.8)
    labels = []
    for t, r in f.iterrows():
        if any(pd.isna(r[k]) for k in ("adx", "slope200", "rv20")):
            labels.append("range"); continue
        crash = r["dd60"] < -0.12 and r["rv20"] > 0.30 or (r["dd60"] < -0.08 and p_stress[t] > 0.8 and r["breadth50"] < 0.25)
        hv = (not pd.isna(vix_hi[t]) and r["vix"] > vix_hi[t]) or p_stress[t] > 0.7 or r["rv20"] > 0.25
        if crash:
            labels.append("crash")
        elif hv:
            labels.append("high_volatility")
        elif r["adx"] > 22 and r["slope200"] > 0.005 and r["breadth50"] > 0.5:
            labels.append("trending_up")
        elif r["adx"] > 22 and r["slope200"] < -0.005 and r["breadth50"] < 0.5:
            labels.append("trending_down")
        else:
            labels.append("range")
    out["regime"] = labels
    out["p_stress"] = p_stress
    # 3-day persistence filter to avoid whipsaw
    out["regime"] = out["regime"].where(out["regime"] == out["regime"].shift(1), out["regime"].shift(1)).fillna(out["regime"])
    return out


def detect(index: pd.DataFrame, **kw) -> pd.DataFrame:
    f = features(index, **kw)
    m, st = fit_hmm(f)
    return classify(f, m, st)


def probabilities(reg: pd.DataFrame, lookback: int = 20) -> dict[str, float]:
    tail = reg["regime"].tail(lookback)
    return {r: float((tail == r).mean()) for r in REGIMES}


def detect_walk_forward(index: pd.DataFrame, refit_every: int = 252, min_train: int = 500, **kw) -> pd.DataFrame:
    """Look-ahead-free regimes for backtests: the HMM is refit every `refit_every` bars using only past data."""
    f = features(index, **kw)
    parts = []
    start = min(min_train, len(f))
    if start > 0:
        parts.append(classify(f.iloc[:start]))
    for s in range(start, len(f), refit_every):
        m, st = fit_hmm(f.iloc[:s])
        chunk = classify(f.iloc[: s + refit_every], m, st).iloc[s: s + refit_every]
        parts.append(chunk)
    return pd.concat(parts) if parts else classify(f)
