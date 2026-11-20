import numpy as np
import pandas as pd

from tradelens import validation as V
from tradelens.core.metrics import summary
from tradelens.promotion import Pipeline


def test_purged_kfold_has_no_leakage():
    for tr, te in V.purged_kfold(1000, 5, horizon=5, embargo=0.01):
        assert not set(tr) & set(range(te[0] - 5, te[-1] + 1 + 10))


def test_pbo_detects_overfitting():
    rng = np.random.default_rng(1)
    noise = pd.DataFrame(rng.normal(0, 0.01, (800, 30)))
    assert V.pbo(noise) > 0.3  # selecting the best of pure noise is overfit
    good = noise.copy(); good[0] += 0.003  # one genuinely better configuration
    assert V.pbo(good) < 0.1


def test_deflated_sharpe_penalises_many_trials():
    rng = np.random.default_rng(2)
    r = rng.normal(0.0006, 0.01, 1500)
    assert V.deflated_sharpe(r, 1) > V.deflated_sharpe(r, 500)


def test_monte_carlo_outputs():
    rng = np.random.default_rng(3)
    mc = V.monte_carlo(list(rng.normal(100, 1000, 300)), rng.normal(0.0005, 0.01, 1000), 1_000_000, n=300)
    assert mc["bootstrap_dd_p95"] > 0 and mc["shuffle_dd_p95"] > 0 and 0 <= mc["prob_loss"] <= 1


def test_stability_and_acceptance():
    heat = pd.DataFrame({"a": [1, 2, 3], "score": [1.0, 1.1, 0.9]})
    assert V.stability_score(heat) == 1.0
    spike = pd.DataFrame({"a": [1, 2, 3, 4], "score": [0.0, 0.0, 2.0, 0.0]})
    assert V.stability_score(spike) < 0.4
    idx = pd.date_range("2015-01-01", periods=2000, freq="B")
    v = V.acceptance(pd.Series(0.001, idx), [{"pnl": 1}] * 250, 0.1, 0.97, 0.8, {"bootstrap_dd_p95": 12})
    assert v.passed
    v2 = V.acceptance(pd.Series(0.001, idx[:300]), [{"pnl": 1}] * 50, 0.5, 0.3, 0.2, {"bootstrap_dd_p95": 40})
    assert not v2.passed and len(v2.reasons) == 6


def test_walk_forward_uses_only_train_for_selection():
    idx = pd.date_range("2015-01-01", periods=1500, freq="B")
    calls = []

    def ev(p, s, e):
        calls.append((p["k"], s, e))
        noise = pd.Series(np.random.default_rng(0).normal(0, 0.01, len(idx)), idx)
        r = noise + 0.002 * p["k"]
        return r[(r.index > s) & (r.index <= e)]

    wf = V.walk_forward(ev, idx, {"k": [1, 2]}, train_years=2, test_months=6)
    assert all(c == {"k": 2} for c in wf.chosen) and len(wf.oos_returns) > 0


def test_metrics_alpha_beta():
    idx = pd.date_range("2020-01-01", periods=500, freq="B")
    b = pd.Series(100 * np.exp(np.cumsum(np.random.default_rng(4).normal(0.0003, 0.01, 500))), idx)
    eq = b * 2
    m = summary(eq, [], b)
    assert abs(m["beta"] - 1) < 1e-6 and abs(m["alpha"]) < 1e-6


def test_promotion_and_demotion(tmp_path):
    p = Pipeline(tmp_path / "p.json")

    class Vd:
        passed = True; reasons = []; stats = {"dsr": 0.97, "pbo": 0.1}
    r = p.record_backtest("x", Vd(), np.random.default_rng(5).normal(0.001, 0.01, 1000), 1.2)
    assert r.stage == "PAPER" and r.enabled
    r.stage = "LIVE"
    for _ in range(30):
        res = p.record_live_return("x", -0.02)
    assert p.get("x").stage == "DEMOTED" and not p.get("x").enabled
