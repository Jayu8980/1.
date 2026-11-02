import numpy as np
import pandas as pd

from trader.data import synthetic_ohlcv
from trader.indicators import compute_all, rsi
from trader.strategies import simulate, stats


def test_indicator_frame_is_sane():
    df = synthetic_ohlcv("TEST", bars=600)
    ind = compute_all(df)
    assert len(ind) == len(df)
    tail = ind.iloc[250:]
    assert tail["rsi14"].between(0, 100).all()
    assert (tail["atr"] > 0).all()
    assert set(tail["st_dir"].unique()) <= {-1.0, 1.0}
    assert not np.isinf(ind.to_numpy(dtype=float)).any()
    assert tail[["sma200", "macd", "adx", "bb_pctb", "lr_slope50", "cci", "mfi"]].notna().all().all()


def test_rsi_extremes():
    assert rsi(pd.Series(np.arange(1.0, 60.0))).iloc[-1] == 100
    assert rsi(pd.Series(np.arange(60.0, 1.0, -1))).iloc[-1] < 1


def _bars(rows):
    return tuple(np.array(col, dtype=float) for col in zip(*rows))


def test_long_trade_reaches_target():
    # signal on bar 0 -> entry at bar 1 open (100); ATR 1 x 2 = stop 98, 2R target 104
    o, h, l, c = _bars([(100, 100, 100, 100), (100, 101, 99.5, 100.5), (101, 104.5, 100.8, 104)])
    r, entries = simulate(o, h, l, c, np.ones(3), [0], 1, 2.0, 2.0, 10, fee=0.0)
    assert entries.tolist() == [1]
    assert abs(r[0] - 2.0) < 1e-9


def test_stop_is_assumed_first_when_both_levels_hit_in_one_bar():
    o, h, l, c = _bars([(100, 100, 100, 100), (100, 105, 97, 101)])
    r, _ = simulate(o, h, l, c, np.ones(2), [0], 1, 2.0, 2.0, 10, fee=0.0)
    assert abs(r[0] + 1.0) < 1e-9


def test_gap_through_stop_fills_at_the_open():
    o, h, l, c = _bars([(100, 100, 100, 100), (100, 100.5, 99.5, 100), (95, 96, 94, 95)])
    r, _ = simulate(o, h, l, c, np.ones(3), [0], 1, 2.0, 2.0, 10, fee=0.0)
    assert abs(r[0] + 2.5) < 1e-9  # worse than the planned -1R, as in real life


def test_short_trade_time_exit():
    o, h, l, c = _bars([(100, 100, 100, 100), (100, 100.5, 99.5, 99.8), (99.8, 100.2, 99.0, 99.0)])
    r, _ = simulate(o, h, l, c, np.ones(3), [0], -1, 2.0, 2.0, 2, fee=0.0)
    assert abs(r[0] - 0.5) < 1e-9  # short from 100, closed at 99 after 2 bars, risk 2


def test_only_one_position_at_a_time():
    c = np.full(40, 100.0)
    r, entries = simulate(c, c + 0.1, c - 0.1, c, np.ones(40), list(range(25)), 1, 2.0, 2.0, 5, fee=0.0)
    assert len(entries) > 1
    assert all(b - a >= 5 for a, b in zip(entries, entries[1:]))


def test_trade_statistics():
    s = stats(np.array([2.0, -1.0, 2.0, -1.0]))
    assert s["trades"] == 4 and s["win_rate"] == 0.5
    assert abs(s["expectancy"] - 0.5) < 1e-9
    assert abs(s["profit_factor"] - 2.0) < 1e-9
    assert abs(s["max_dd_r"] - 1.0) < 1e-9
