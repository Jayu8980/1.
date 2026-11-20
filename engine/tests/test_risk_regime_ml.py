import numpy as np
import pandas as pd

from tradelens.config import RiskLimits
from tradelens.construction import StrategyStats, kelly_cap, target_positions, hedge_qty
from tradelens.core.types import Direction, Signal
from tradelens.regime import detect
from tradelens.risk import RiskManager
from conftest import make_bars


def test_daily_loss_and_kill_switches():
    rm = RiskManager(RiskLimits(dailyLossPct=2, maxDrawdownPct=10, maxOrdersPerDay=2))
    rm.new_day(pd.Timestamp("2026-01-05").date(), 100.0)
    assert rm.on_equity(97.5) == "flatten_halt_day"
    assert not rm.can_trade("a")[0] and rm.can_trade("a", risk_reducing=True)[0]
    rm.new_day(pd.Timestamp("2026-01-06").date(), 97.5)
    assert rm.can_trade("a")[0]
    rm.record_order(); rm.record_order()
    assert not rm.can_trade("a")[0]
    rm.state.global_kill = True
    assert not rm.can_trade("a", risk_reducing=True)[0]


def test_kelly_cap_and_sizing_limits():
    assert kelly_cap(0.6, 2.0, 1.0) <= 0.25 * 0.4 + 1e-9
    sigs = [Signal("A", Direction.LONG, 1, 0.6, stop=95, strategy="s"), Signal("B", Direction.LONG, 1, 0.6, stop=95, strategy="s")]
    tgt = target_positions(sigs, {"A": 100, "B": 100}, {"A": 0.3, "B": 0.3}, 1_000_000, {"s": 1.0}, {"s": StrategyStats(1, 0.55, 2)},
                           RiskLimits(maxPositionPct=10, maxSectorPct=15), sectors={"A": "Bank", "B": "Bank"})
    vals = {k[1]: v * 100 for k, v in tgt.items()}
    assert all(v <= 100_000 + 1e-6 for v in vals.values())
    assert sum(vals.values()) <= 150_000 + 1e-6
    assert hedge_qty("crash", 2_000_000, 25000, 75) == -1 and hedge_qty("range", 2_000_000, 25000) == 0


def test_regime_flags_crash():
    calm = make_bars(700, drift=0.0006, vol=0.008, seed=8)
    crash = make_bars(60, drift=-0.012, vol=0.035, seed=9, start=float(calm["close"].iloc[-1]))
    crash.index = pd.date_range(calm.index[-1] + pd.Timedelta(days=1), periods=60, freq="B", tz="UTC")
    df = pd.concat([calm, crash])
    reg = detect(df)
    assert reg["regime"].iloc[-1] in ("crash", "high_volatility")
    assert (reg["regime"].iloc[300:650] != "crash").mean() > 0.9


def test_ml_train_and_onnx_parity(tmp_path):
    import onnxruntime as ort
    from tradelens.ml.features import FEATURES, build, label
    from tradelens.ml.model import export_onnx, train
    frames = [make_bars(900, seed=s) for s in range(3)]
    X = pd.concat([build(f) for f in frames]); y = pd.concat([label(f) for f in frames])
    tm = train(X, y)
    assert 0.3 < tm.cv_auc < 0.7  # random walk → no edge, and the CV must not leak
    meta = export_onnx(tm, str(tmp_path / "m.onnx"), str(tmp_path / "m.json"))
    assert meta["features"] == FEATURES
    sess = ort.InferenceSession(str(tmp_path / "m.onnx"))
    x = X[FEATURES].dropna().iloc[:20].values.astype(np.float32)
    out = sess.run(None, {"input": x})
    p_onnx = out[-1][:, 1]
    p_lgb = tm.model.predict_proba(x)[:, 1]
    assert np.allclose(p_onnx, p_lgb, atol=1e-4)
