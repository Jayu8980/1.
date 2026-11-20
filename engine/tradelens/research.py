"""Research driver: load data, run a strategy through the event-driven engine, then the full
anti-overfitting suite, and record the verdict in the promotion pipeline."""
from __future__ import annotations

import json

import numpy as np
import pandas as pd

from .config import CONFIG
from .core.engine import Engine
from .core.execution import SimExecution
from .core.metrics import drawdown_series, monthly_returns, summary, tri_from_price
from .data import nse, yahoo
from .promotion import Pipeline
from .regime import detect_walk_forward
from .strategies import REGISTRY
from .strategies.options_income import SYMBOL as IC_SYMBOL, synthetic_condor_series
from . import validation as V

CRYPTO = {"funding_carry", "basis_trade", "crypto_trend"}


def load_universe(index: str = "nifty200", years: int = 10, limit: int | None = None) -> tuple[dict[str, pd.DataFrame], dict[str, str]]:
    cons = nse.constituents(index)
    syms = cons["Symbol"].tolist()[:limit] if limit else cons["Symbol"].tolist()
    sectors = dict(zip(cons["Symbol"], cons["Industry"]))
    data = {}
    for s in syms:
        try:
            df = yahoo.download(s, "1D", start=int(pd.Timestamp.now().timestamp()) - years * 365 * 86400)
            if len(df) > 260:
                data[s] = df
        except Exception:
            continue
    return data, sectors


def load_for(strategy: str, years: int = 10, limit: int | None = None):
    start = int(pd.Timestamp.now().timestamp()) - years * 365 * 86400
    if strategy in CRYPTO:
        from .data.delta import DeltaClient
        dc = DeltaClient(testnet=False)
        data = {s: dc.candles(s, "1D", start) for s in ("BTCUSD", "ETHUSD")}
        for s in ("BTCUSD", "ETHUSD"):
            f = dc.funding_history(s, years * 365).resample("1D").mean()
            data[f"FUNDING:{s}"] = pd.DataFrame({"open": f, "high": f, "low": f, "close": f, "volume": 0.0}).dropna()
        return data, {}, data["BTCUSD"]["close"], 365
    nifty = yahoo.download("NIFTY 50", "1D", start=start)
    if strategy == "options_income":
        vix = yahoo.download("INDIA VIX", "1D", start=start)
        ser = synthetic_condor_series(nifty["close"], vix["close"])
        ic = pd.DataFrame({"open": ser.shift().fillna(ser), "high": ser, "low": ser, "close": ser, "volume": 1e9})
        return {IC_SYMBOL: ic, "INDIAVIX": vix, "NIFTY": nifty}, {}, tri_from_price(nifty["close"]), 252
    if strategy in ("trend_following", "orb_vwap"):
        data = {"NIFTY": nifty, "BANKNIFTY": yahoo.download("BANKNIFTY", "1D", start=start)}
        u, sectors = load_universe("nifty50", years, limit or 20)
        return data | u, sectors, tri_from_price(nifty["close"]), 252
    u, sectors = load_universe("nifty200", years, limit)
    return {"NIFTY": nifty} | u, sectors, tri_from_price(nifty["close"]), 252


def backtest(strategy: str, data: dict, sectors: dict, bench: pd.Series, ppy: int = 252, params: dict | None = None,
             start=None, end=None, regimes: pd.Series | None = None, capital: float | None = None):
    st = REGISTRY[strategy](**(params or {}))
    if regimes is None:
        idx = data.get("NIFTY") if "NIFTY" in data else data.get("BTCUSD")
        regimes = detect_walk_forward(idx)["regime"] if idx is not None and len(idx) > 300 else None
    mult = {}
    eng = Engine([st], {k: v for k, v in data.items() if not k.startswith("FUNDING:") or True}, SimExecution(), capital or CONFIG.capital,
                 CONFIG.risk, regimes, bench, sectors, target_vol=CONFIG.target_vol, multipliers=mult, periods_per_year=ppy)
    eng.ctx.meta = {"sectors": sectors}
    return eng.run(start, end)


def full_validation(strategy: str, years: int = 10, limit: int | None = 60) -> dict:
    data, sectors, bench, ppy = load_for(strategy, years, limit)
    anchor = data.get("NIFTY", data.get("BTCUSD", next(iter(data.values()))))
    regimes = detect_walk_forward(anchor)["regime"] if len(anchor) > 300 else None
    res = backtest(strategy, data, sectors, bench, ppy, regimes=regimes)
    daily = res.equity.pct_change().dropna()
    cls = REGISTRY[strategy]
    grid = cls.param_grid or {}

    cache: dict = {}

    def evaluate(p: dict, s, e) -> pd.Series:
        key = json.dumps(p, sort_keys=True, default=str)
        if key not in cache:
            cache[key] = backtest(strategy, data, sectors, bench, ppy, p, regimes=regimes).equity.pct_change().dropna()
        r = cache[key]
        return r[(r.index > s) & (r.index <= e)]

    wf = V.walk_forward(evaluate, anchor.index, grid, train_years=3, test_months=6)
    trial = wf.trial_returns.fillna(0)
    pbo_v = V.pbo(trial) if trial.shape[1] > 1 else 0.0
    n_trials = max(trial.shape[1], 1)
    dsr = V.deflated_sharpe(wf.oos_returns.values if len(wf.oos_returns) else daily.values, n_trials,
                            [V.sharpe(trial[c].values) for c in trial.columns] if n_trials > 1 else None)
    heat = V.param_heatmap(lambda p: V.sharpe(evaluate(p, anchor.index[0], anchor.index[-1]).values) * np.sqrt(ppy), grid) if grid else pd.DataFrame({"score": [1.0]})
    stab = V.stability_score(heat)
    mc = V.monte_carlo([t["pnl"] for t in res.trades], daily.values, CONFIG.capital)
    verdict = V.acceptance(daily, res.trades, pbo_v, dsr, stab, mc)
    oos_sharpe = V.sharpe(wf.oos_returns.values) * np.sqrt(ppy) if len(wf.oos_returns) else 0.0
    rec = Pipeline(CONFIG.state_dir / "pipeline.json").record_backtest(strategy, verdict, daily.values, oos_sharpe)
    report = {
        "name": strategy, **res.metrics, "oosSharpe": oos_sharpe, "verdict": verdict.passed, "reasons": verdict.reasons, "validation": verdict.stats,
        "heatmap": heat.to_dict("records"), "stage": rec.stage,
        "equity": [{"t": int(t.timestamp()), "v": float(v)} for t, v in res.equity.items()],
        "benchmarkEquity": [{"t": int(t.timestamp()), "v": float(v)} for t, v in (bench.reindex(res.equity.index).ffill() / bench.reindex(res.equity.index).ffill().iloc[0] * res.equity.iloc[0]).dropna().items()],
        "drawdown": [{"t": int(t.timestamp()), "v": float(v * 100)} for t, v in drawdown_series(res.equity).items()],
        "monthly": monthly_returns(res.equity), "benchmark": "BTC" if strategy in CRYPTO else "NIFTY 50 TRI",
    }
    (CONFIG.state_dir / "reports").mkdir(parents=True, exist_ok=True)
    (CONFIG.state_dir / "reports" / f"{strategy}.json").write_text(json.dumps(report, default=float))
    return report
