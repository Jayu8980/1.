"""Statistical arbitrage: cointegrated pairs within a sector, z-score entry/exit."""
from __future__ import annotations

import itertools

import numpy as np
import pandas as pd

from ..core.strategy import Context, Strategy
from ..core.types import Direction, Signal


def adf_tstat(x: np.ndarray) -> float:
    """Augmented Dickey-Fuller t-statistic (1 lag, constant) on series x."""
    dx = np.diff(x); xl = x[:-1]
    y = dx[1:]; X = np.column_stack([np.ones(len(y)), xl[1:], dx[:-1]])
    beta, *_ = np.linalg.lstsq(X, y, rcond=None)
    resid = y - X @ beta
    s2 = resid @ resid / (len(y) - X.shape[1])
    cov = s2 * np.linalg.inv(X.T @ X)
    return float(beta[1] / np.sqrt(cov[1, 1]))


def engle_granger(a: pd.Series, b: pd.Series) -> tuple[float, float, float]:
    """Returns (hedge ratio, intercept, ADF t-stat of the residual). t < -3.37 ≈ cointegrated at 5%."""
    la, lb = np.log(a.values), np.log(b.values)
    X = np.column_stack([np.ones(len(lb)), lb])
    (c, beta), *_ = np.linalg.lstsq(X, la, rcond=None)
    return float(beta), float(c), adf_tstat(la - c - beta * lb)


class PairsStatArb(Strategy):
    name = "stat_arb"
    description = "Cointegrated sector pairs, z-score 2.0 entry / 0.5 exit / 4.0 stop"
    active_regimes = {"range", "trending_up", "trending_down"}
    params = {"lookback": 250, "entry_z": 2.0, "exit_z": 0.5, "stop_z": 4.0, "refit": 21, "adf_crit": -3.37}
    param_grid = {"entry_z": [1.5, 2.0, 2.5], "lookback": [120, 250]}

    def __init__(self, **p):
        super().__init__(**p)
        self._pairs: list[tuple[str, str, float, float]] = []
        self._bars = 0
        self._open: dict[tuple[str, str], int] = {}

    def _fit(self, ctx: Context):
        sectors = ctx.meta.get("sectors", {})
        groups: dict[str, list[str]] = {}
        for s in ctx.symbols():
            groups.setdefault(sectors.get(s, "Other"), []).append(s)
        pairs = []
        for sec, syms in groups.items():
            if sec == "Other" or len(syms) < 2:
                continue
            for a, b in itertools.combinations(sorted(syms)[:15], 2):
                ha = ctx.history(a, self.params["lookback"])["close"]; hb = ctx.history(b, self.params["lookback"])["close"]
                df = pd.concat([ha, hb], axis=1).dropna()
                if len(df) < self.params["lookback"] * 0.9:
                    continue
                beta, c, t = engle_granger(df.iloc[:, 0], df.iloc[:, 1])
                if t < self.params["adf_crit"] and 0.2 < beta < 5:
                    pairs.append((t, a, b, beta, c))
        self._pairs = [(a, b, beta, c) for t, a, b, beta, c in sorted(pairs)[:10]]

    def on_bar(self, ctx: Context) -> list[Signal]:
        if self._bars % self.params["refit"] == 0:
            self._fit(ctx)
        self._bars += 1
        out = []
        for a, b, beta, c in self._pairs:
            ha = ctx.history(a, 60)["close"]; hb = ctx.history(b, 60)["close"]
            if len(ha) < 60 or len(hb) < 60:
                continue
            spread = np.log(ha.values) - c - beta * np.log(hb.values)
            z = (spread[-1] - spread.mean()) / (spread.std() + 1e-12)
            key = (a, b); state = self._open.get(key, 0)
            if state == 0 and abs(z) > self.params["entry_z"]:
                d = -1 if z > 0 else 1
                self._open[key] = d
                out += [Signal(a, Direction(d), 0.5, 0.6, reason=f"pair {a}/{b} z={z:.2f}", segment="FUTURES"),
                        Signal(b, Direction(-d), 0.5 * min(beta, 2), 0.6, reason=f"pair hedge z={z:.2f}", segment="FUTURES")]
            elif state != 0 and (abs(z) < self.params["exit_z"] or abs(z) > self.params["stop_z"]):
                self._open[key] = 0
                out += [Signal(a, Direction.FLAT, 0, 1, reason=f"pair exit z={z:.2f}"), Signal(b, Direction.FLAT, 0, 1, reason="pair exit")]
        return out
