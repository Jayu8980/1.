"""Layer 7 - Machine-learning forecaster."""
from __future__ import annotations

import warnings

import numpy as np
import pandas as pd

from ..strategies import trade_outcomes
from .base import Agent, clip


def make_features(ind: pd.DataFrame) -> pd.DataFrame:
    """Scale-free features, all computed from data available at the close of each bar."""
    c = ind["close"]
    f = pd.DataFrame(index=ind.index)
    for n in (1, 5, 10, 21, 63):
        f[f"ret_{n}"] = c.pct_change(n)
    for n in (20, 50, 200):
        f[f"dist_sma{n}"] = c / ind[f"sma{n}"] - 1
    f["sma50_vs_200"] = ind["sma50"] / ind["sma200"] - 1
    f["rsi14"] = ind["rsi14"] / 100
    f["rsi2"] = ind["rsi2"] / 100
    f["macd_hist"] = ind["macd_hist"] / c
    f["atr_pct"] = ind["atr"] / c
    f["vol21"] = ind["vol21"]
    f["vol_ratio_21_63"] = ind["vol21"] / ind["vol63"]
    f["bb_pctb"] = ind["bb_pctb"]
    f["bb_width"] = ind["bb_width"]
    f["stoch_k"] = ind["stoch_k"] / 100
    f["adx"] = ind["adx"] / 100
    f["di_spread"] = (ind["plus_di"] - ind["minus_di"]) / 100
    f["cmf"] = ind["cmf"]
    f["mfi"] = ind["mfi"] / 100
    f["donchian_pos"] = (c - ind["dc_low20"]) / (ind["dc_high20"] - ind["dc_low20"])
    f["volume_z"] = ind["vol_z"]
    f["rs21"] = ind["rs21"]
    f["rs63"] = ind["rs63"]
    f["lr_slope"] = ind["lr_slope50"] * 252
    f["drawdown_252"] = c / c.rolling(252, min_periods=50).max() - 1
    f["supertrend"] = ind["st_dir"]
    return f.replace([np.inf, -np.inf], np.nan)


def build_models(names: list[str], seed: int = 7) -> list:
    from sklearn.ensemble import HistGradientBoostingClassifier, RandomForestClassifier
    from sklearn.impute import SimpleImputer
    from sklearn.linear_model import LogisticRegression
    from sklearn.pipeline import make_pipeline
    from sklearn.preprocessing import StandardScaler

    models = []
    for name in names:
        if name == "gbm":
            models.append(HistGradientBoostingClassifier(max_iter=150, learning_rate=0.05, max_depth=3,
                                                         min_samples_leaf=30, l2_regularization=1.0,
                                                         random_state=seed))
        elif name == "forest":
            models.append(make_pipeline(SimpleImputer(strategy="median"),
                                        RandomForestClassifier(n_estimators=150, max_depth=6,
                                                               min_samples_leaf=20, n_jobs=-1,
                                                               random_state=seed)))
        elif name == "logit":
            models.append(make_pipeline(SimpleImputer(strategy="median"), StandardScaler(),
                                        LogisticRegression(C=0.3, max_iter=1000)))
        else:
            raise ValueError(f"unknown model '{name}' (use gbm, forest, logit)")
    return models


def ensemble_proba(names, X_train, y_train, X_test):
    probs, fitted = [], []
    with warnings.catch_warnings():
        warnings.simplefilter("ignore")
        for model in build_models(names):
            model.fit(X_train, y_train)
            probs.append(model.predict_proba(X_test)[:, 1])
            fitted.append(model)
    return np.mean(probs, axis=0), fitted


def walk_forward(X: pd.DataFrame, y: pd.Series, mc, purge: int) -> dict | None:
    """Purged walk-forward evaluation, then a final fit on all labelled history."""
    from sklearn.metrics import roc_auc_score

    rows = y.notna() & (X.notna().mean(axis=1) >= 0.9)
    Xl, yl = X[rows], y[rows].astype(int)
    n = len(Xl)
    if n < mc.min_train + 100 or yl.nunique() < 2:
        return None
    fold = (n - mc.min_train) // mc.n_splits
    oos = np.full(n, np.nan)
    for k in range(mc.n_splits):
        start = mc.min_train + k * fold
        end = n if k == mc.n_splits - 1 else start + fold
        train_end = start - purge  # drop labels that overlap the test window
        if yl.iloc[:train_end].nunique() < 2:
            continue
        oos[start:end] = ensemble_proba(mc.models, Xl.iloc[:train_end], yl.iloc[:train_end],
                                        Xl.iloc[start:end])[0]
    mask = ~np.isnan(oos)
    y_oos, p_oos = yl.to_numpy()[mask], oos[mask]
    if mask.sum() < 50 or len(np.unique(y_oos)) < 2:
        return None
    auc = float(roc_auc_score(y_oos, p_oos))
    p_now, fitted = ensemble_proba(mc.models, Xl, yl, X.iloc[[-1]])
    top = []
    for model in fitted:
        forest = getattr(model, "steps", [[None, None]])[-1][1]
        importances = getattr(forest, "feature_importances_", None)
        if importances is not None and len(importances) == X.shape[1]:
            top = [str(X.columns[i]) for i in np.argsort(importances)[::-1][:5]]
    return {"prob": float(p_now[0]), "auc": auc, "base_rate": float(yl.mean()), "samples": n,
            "oos_samples": int(mask.sum()), "brier": float(np.mean((p_oos - y_oos) ** 2)),
            "top_features": top, "oos": pd.Series(p_oos, index=Xl.index[mask])}


class MLAgent(Agent):
    name = "ml"
    title = "Machine-Learning Forecaster"
    layer = 7
    role = ("Trains an ensemble (gradient boosting, random forest, logistic regression) with purged "
            "walk-forward validation to estimate the probability that this exact trade - same stop, "
            "target and time exit - ends in profit.")

    def evaluate(self, cand):
        mc = self.cfg.ml
        setup = cand.cache["setup"]
        stop_atr, rr, hold = setup["stop_atr"], setup["reward_risk"], setup["max_hold"]
        X = make_features(cand.ind)
        outcomes = trade_outcomes(cand.ind, cand.direction, stop_atr, rr, hold, self.cfg.optimizer.fee_pct)
        y = (outcomes > 0).astype(float).where(outcomes.notna())
        res = walk_forward(X, y, mc, purge=hold + 1)
        if res is None:
            return self.fail("not enough labelled history to train a reliable model")
        oos = res.pop("oos")
        avg_win = float(outcomes[outcomes > 0].mean())
        avg_loss = float(-outcomes[outcomes <= 0].mean())
        if not (avg_win > 0 and avg_loss > 0):
            return self.fail("trade outcomes are one-sided - cannot estimate an edge")
        breakeven = avg_loss / (avg_win + avg_loss)
        p, auc, base = res["prob"], res["auc"], res["base_rate"]
        ev = p * avg_win - (1 - p) * avg_loss
        r_oos = outcomes.reindex(oos.index)
        top_r = float(r_oos[oos >= oos.quantile(0.8)].mean())  # how the model's favourite bars did
        data = {**res, "breakeven": breakeven, "expected_r": ev, "avg_win_r": avg_win,
                "avg_loss_r": avg_loss, "oos_top_quintile_r": top_r, "oos_all_r": float(r_oos.mean())}
        if not auc >= mc.min_auc:
            return self.fail(f"model shows no out-of-sample skill (AUC {auc:.2f} < {mc.min_auc})", **data)
        if p < breakeven + mc.min_edge:
            return self.fail(f"P(profit) {p:.0%} too close to break-even {breakeven:.0%}", **data)
        if p < base:
            return self.fail(f"model rates today below an average bar ({p:.0%} < base rate {base:.0%})", **data)
        score = 0.6 * clip(0.5 + ev / 2) + 0.4 * clip((auc - 0.5) / 0.1)
        reasons = [f"P(profit) {p:.0%} vs break-even {breakeven:.0%}, base rate {base:.0%}",
                   f"walk-forward AUC {auc:.2f} on {res['oos_samples']} unseen bars; its top-20% picks "
                   f"averaged {top_r:+.2f}R vs {data['oos_all_r']:+.2f}R overall",
                   f"model expected value {ev:+.2f}R (avg win {avg_win:.2f}R, avg loss {avg_loss:.2f}R)"]
        if res["top_features"]:
            reasons.append("top drivers: " + ", ".join(res["top_features"][:3]))
        return self.ok(clip(score), reasons, **data)
