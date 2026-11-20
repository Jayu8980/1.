"""LightGBM/XGBoost direction model with purged & embargoed K-fold CV, isotonic calibration, a calibrated
probability threshold and ONNX export for on-device scoring."""
from __future__ import annotations

import json
import time
from dataclasses import dataclass

import numpy as np
import pandas as pd
from sklearn.isotonic import IsotonicRegression
from sklearn.metrics import roc_auc_score

from ..validation import purged_kfold
from .features import FEATURES


@dataclass
class TrainedModel:
    model: object
    calibrator: IsotonicRegression
    threshold_raw: float      # threshold in the model's raw-probability space (what ONNX outputs)
    threshold_cal: float      # calibrated probability threshold
    cv_auc: float
    features: list[str]

    def predict_proba(self, X: pd.DataFrame) -> np.ndarray:
        return self.calibrator.predict(self.model.predict_proba(X[self.features].values)[:, 1])


def _make(kind: str = "lightgbm", seed: int = 7):
    if kind == "xgboost":
        from xgboost import XGBClassifier
        return XGBClassifier(n_estimators=300, max_depth=4, learning_rate=0.03, subsample=0.8, colsample_bytree=0.8, random_state=seed, eval_metric="logloss")
    from lightgbm import LGBMClassifier
    return LGBMClassifier(n_estimators=300, num_leaves=15, learning_rate=0.03, subsample=0.8, subsample_freq=1, colsample_bytree=0.8,
                          min_child_samples=50, reg_lambda=1.0, random_state=seed, verbose=-1)


def train(X: pd.DataFrame, y: pd.Series, horizon: int = 5, n_splits: int = 5, embargo: float = 0.01, kind: str = "lightgbm",
          target_precision: float = 0.55, features: list[str] | None = None) -> TrainedModel:
    feats = features or FEATURES
    d = pd.concat([X[feats], y.rename("y")], axis=1).dropna()
    Xv, yv = d[feats].values, d["y"].values
    oof = np.full(len(d), np.nan)
    for tr, te in purged_kfold(len(d), n_splits, horizon, embargo):
        m = _make(kind); m.fit(Xv[tr], yv[tr])
        oof[te] = m.predict_proba(Xv[te])[:, 1]
    ok = ~np.isnan(oof)
    auc = float(roc_auc_score(yv[ok], oof[ok])) if len(set(yv[ok])) > 1 else 0.5
    cal = IsotonicRegression(out_of_bounds="clip", y_min=0, y_max=1).fit(oof[ok], yv[ok])
    # smallest calibrated threshold whose out-of-fold precision ≥ target, with ≥ 5% coverage
    p_cal = cal.predict(oof[ok]); thr_cal = 0.6
    for t in np.arange(0.5, 0.8, 0.01):
        sel = p_cal >= t
        if sel.mean() >= 0.05 and yv[ok][sel].mean() >= target_precision:
            thr_cal = float(t); break
    raw_sorted = np.sort(oof[ok])
    thr_raw = float(raw_sorted[np.searchsorted(cal.predict(raw_sorted), thr_cal).clip(0, len(raw_sorted) - 1)])
    final = _make(kind); final.fit(Xv, yv)
    return TrainedModel(final, cal, thr_raw, thr_cal, auc, feats)


def export_onnx(tm: TrainedModel, path: str, meta_path: str, name: str = "lgbm_direction") -> dict:
    from onnxmltools import convert_lightgbm
    from onnxmltools.convert.common.data_types import FloatTensorType
    onx = convert_lightgbm(tm.model, initial_types=[("input", FloatTensorType([None, len(tm.features)]))], zipmap=False, target_opset=15)
    with open(path, "wb") as f:
        f.write(onx.SerializeToString())
    meta = {"name": name, "features": tm.features, "threshold": tm.threshold_raw, "version": time.strftime("%Y%m%d"), "trainedAt": int(time.time()),
            "cvAuc": tm.cv_auc, "calibratedThreshold": tm.threshold_cal}
    with open(meta_path, "w") as f:
        json.dump(meta, f)
    return meta
