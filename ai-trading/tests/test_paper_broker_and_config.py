from pathlib import Path

import pandas as pd
import pytest

from trader.brokers.paper import PaperBroker
from trader.config import Config


def plan(**overrides):
    p = {"symbol": "ABC", "side": "long", "qty": 10.0, "entry_ref": 100.0, "stop": 95.0, "target": 110.0,
         "max_hold": 5, "setup": "test", "signal_date": "2026-01-02"}
    p.update(overrides)
    return p


def frame(rows, start="2026-01-02"):
    index = pd.bdate_range(start, periods=len(rows))
    return pd.DataFrame(rows, columns=["Open", "High", "Low", "Close"], index=index).assign(Volume=1e6)


def broker(tmp_path):
    return PaperBroker(str(tmp_path), 10_000, fee_pct=0.0, slippage_pct=0.0)


def test_pending_order_fills_at_next_open_then_hits_target(tmp_path):
    b = broker(tmp_path)
    assert b.submit(plan())["status"] == "pending"
    closed = b.manage({"ABC": frame([(100, 100, 100, 100), (101, 102, 100, 101), (103, 111, 102, 110)])})
    assert len(closed) == 1 and closed[0]["reason"] == "target"
    assert closed[0]["entry"] == 101 and closed[0]["exit"] == 110
    assert abs(closed[0]["pnl"] - 90) < 1e-9
    assert abs(b.account()["equity"] - 10_090) < 1e-9
    assert b.positions() == []


def test_gap_below_stop_cancels_the_entry(tmp_path):
    b = broker(tmp_path)
    b.submit(plan())
    closed = b.manage({"ABC": frame([(100, 100, 100, 100), (94, 95, 93, 94)])})
    assert closed[0]["status"] == "cancelled"
    assert b.positions() == [] and b.account()["equity"] == 10_000


def test_stop_trails_to_breakeven_after_one_r(tmp_path):
    b = broker(tmp_path)
    b.submit(plan(target=120.0))
    closed = b.manage({"ABC": frame([(100, 100, 100, 100), (100, 106, 99, 106), (105, 105, 99.5, 100)])})
    assert closed[0]["reason"] == "stop" and abs(closed[0]["exit"] - 100) < 1e-9


def test_time_exit_and_state_survives_restart(tmp_path):
    b = broker(tmp_path)
    b.submit(plan(max_hold=2))
    b.manage({"ABC": frame([(100, 100, 100, 100), (100, 101, 99, 100.5)])})
    reloaded = broker(tmp_path)
    assert reloaded.positions()[0]["status"] == "open"
    closed = reloaded.manage({"ABC": frame([(100, 100, 100, 100), (100, 101, 99, 100.5), (100.5, 102, 100, 101)])})
    assert closed[0]["reason"] == "time" and closed[0]["exit"] == 101


def test_short_position_accounting(tmp_path):
    b = broker(tmp_path)
    b.submit(plan(side="short", stop=105.0, target=90.0))
    closed = b.manage({"ABC": frame([(100, 100, 100, 100), (100, 101, 99, 100), (98, 99, 89, 90)])})
    assert closed[0]["reason"] == "target" and abs(closed[0]["pnl"] - 100) < 1e-9
    assert abs(b.account()["equity"] - 10_100) < 1e-9


def test_toml_overrides_merge_with_defaults(tmp_path):
    path = tmp_path / "c.toml"
    path.write_text('[universe]\npreset = "crypto"\n[risk]\nrisk_per_trade = 0.005\n'
                    '[decision.weights]\nml = 0.5\n')
    cfg = Config.load(path).finalize()
    assert cfg.universe.benchmark == "BTC-USD" and "ETH-USD" in cfg.universe.symbols
    assert cfg.risk.risk_per_trade == 0.005
    assert cfg.decision.weights["ml"] == 0.5 and cfg.decision.weights["optimizer"] == 0.25


def test_unknown_config_key_is_rejected(tmp_path):
    path = tmp_path / "c.toml"
    path.write_text("[risk]\nrisk_per_trad = 0.01\n")
    with pytest.raises(ValueError, match="risk_per_trad"):
        Config.load(path)


def test_example_config_loads():
    cfg = Config.load(Path(__file__).resolve().parents[1] / "config.example.toml").finalize()
    assert cfg.universe.benchmark == "SPY" and len(cfg.universe.symbols) == 30


@pytest.mark.parametrize("changes", [
    {"mode": "live"},                                          # live needs a real broker
    {"mode": "live", "broker": "alpaca", "source": "synthetic"},  # never live on fake data
    {"risk_per_trade": 0.5},                                   # typo guard: 50% risk per trade
])
def test_unsafe_settings_are_refused(changes):
    cfg = Config()
    cfg.execution.mode = changes.get("mode", "paper")
    cfg.execution.broker = changes.get("broker", "paper")
    cfg.data.source = changes.get("source", "yahoo")
    cfg.risk.risk_per_trade = changes.get("risk_per_trade", 0.01)
    with pytest.raises(ValueError):
        cfg.finalize()
