import os
import sys
import tempfile

import numpy as np
import pandas as pd
import pytest

sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))
os.environ.setdefault("TRADELENS_API_TOKEN", "test-token-0123456789abcdefghij")
os.environ.setdefault("TRADELENS_STATE_DIR", tempfile.mkdtemp())
os.environ.setdefault("TRADELENS_DATA_DIR", tempfile.mkdtemp())


def make_bars(n=800, drift=0.0004, vol=0.015, seed=0, start=100.0, freq="B"):
    rng = np.random.default_rng(seed)
    r = rng.normal(drift, vol, n)
    close = start * np.exp(np.cumsum(r))
    idx = pd.date_range("2018-01-01", periods=n, freq=freq, tz="UTC")
    open_ = np.r_[close[0], close[:-1]] * (1 + rng.normal(0, 0.002, n))
    high = np.maximum(open_, close) * (1 + np.abs(rng.normal(0, 0.004, n)))
    low = np.minimum(open_, close) * (1 - np.abs(rng.normal(0, 0.004, n)))
    vol_ = rng.integers(1e5, 1e6, n).astype(float)
    return pd.DataFrame({"open": open_, "high": high, "low": low, "close": close, "volume": vol_}, index=idx)


@pytest.fixture
def panel():
    data = {f"S{i}": make_bars(seed=i, drift=0.0002 * i) for i in range(12)}
    data["NIFTY"] = make_bars(seed=99, drift=0.0004, vol=0.01)
    return data
