from __future__ import annotations

import pandas as pd

from ..core.strategy import Context, Strategy, atr
from ..core.types import Direction, Signal
from ..ml.features import build, label
from ..ml.model import train


class MLEnsemble(Strategy):
    """LightGBM on engineered features predicting the next-5-bar direction. Retrained walk-forward every
    `retrain` bars on data strictly before the current bar (purged/embargoed CV inside), trades only when the
    calibrated probability clears the threshold."""
    name = "ml_ensemble"
    description = "LightGBM/XGBoost next-5-bar direction, calibrated probability threshold"
    active_regimes = {"trending_up", "range", "trending_down"}
    params = {"horizon": 5, "retrain": 126, "train_bars": 750, "max_names": 40, "kind": "lightgbm"}
    min_history = 400

    def __init__(self, **p):
        super().__init__(**p)
        self._model = None
        self._since = 10 ** 9
        self._held: dict[str, int] = {}

    def _retrain(self, ctx: Context):
        Xs, ys = [], []
        for s in ctx.symbols()[: self.params["max_names"]]:
            h = ctx.history(s, self.params["train_bars"])
            if len(h) < 300:
                continue
            X = build(h); y = label(h, self.params["horizon"])
            Xs.append(X); ys.append(y)
        if not Xs:
            return
        X = pd.concat(Xs); y = pd.concat(ys)
        self._model = train(X, y, horizon=self.params["horizon"], kind=self.params["kind"])
        self._since = 0

    def on_bar(self, ctx: Context) -> list[Signal]:
        self._since += 1
        if self._model is None or self._since >= self.params["retrain"]:
            self._retrain(ctx)
        if self._model is None:
            return []
        out = []
        for s in ctx.symbols()[: self.params["max_names"]]:
            h = ctx.history(s, 260)
            if len(h) < 210:
                continue
            if ctx.positions.get(s, 0):
                self._held[s] = self._held.get(s, 0) + 1
                if self._held[s] >= self.params["horizon"]:
                    out.append(Signal(s, Direction.FLAT, 0, 1, reason="horizon reached")); self._held.pop(s)
                continue
            p = float(self._model.predict_proba(build(h).iloc[[-1]].fillna(0))[0])
            if p >= self._model.threshold_cal:
                self._held[s] = 0
                out.append(Signal(s, Direction.LONG, min((p - 0.5) * 4, 1), p, stop=h["close"].iloc[-1] - 2 * atr(h).iloc[-1],
                                  horizon="swing", reason=f"P(up)={p:.2f} ≥ {self._model.threshold_cal:.2f}"))
        return out
