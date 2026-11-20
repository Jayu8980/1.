"""Command line: python -m tradelens <command>

  serve [--host 0.0.0.0] [--port 8000] [--ssl-cert c --ssl-key k]   run the REST/WebSocket API + paper/live loop
  backtest <strategy> [--years 10] [--limit 60]                     event-driven backtest with real charges
  validate <strategy|all> [--years 10]                              walk-forward, PBO, DSR, Monte Carlo, stability → promotion
  train [--years 8] [--limit 60]                                    train the ML direction model and export ONNX for the app
  regime                                                            print today's NIFTY regime
  token                                                             generate a strong API token
  secrets                                                           encrypt secrets from the environment into secrets.enc
"""
from __future__ import annotations

import argparse
import json
import os
import secrets as pysecrets
import sys

from .config import CONFIG


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="tradelens")
    sub = p.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("serve"); s.add_argument("--host", default="0.0.0.0"); s.add_argument("--port", type=int, default=8000)
    s.add_argument("--ssl-cert"); s.add_argument("--ssl-key")
    b = sub.add_parser("backtest"); b.add_argument("strategy"); b.add_argument("--years", type=int, default=10); b.add_argument("--limit", type=int, default=60)
    v = sub.add_parser("validate"); v.add_argument("strategy"); v.add_argument("--years", type=int, default=10); v.add_argument("--limit", type=int, default=60)
    t = sub.add_parser("train"); t.add_argument("--years", type=int, default=8); t.add_argument("--limit", type=int, default=60)
    sub.add_parser("regime"); sub.add_parser("token"); sub.add_parser("secrets")
    a = p.parse_args(argv)
    CONFIG.ensure_dirs()

    if a.cmd == "serve":
        import uvicorn
        uvicorn.run("tradelens.api:app", host=a.host, port=a.port, ssl_certfile=a.ssl_cert, ssl_keyfile=a.ssl_key, log_level="info")
    elif a.cmd == "backtest":
        from .research import backtest, load_for
        data, sectors, bench, ppy = load_for(a.strategy, a.years, a.limit)
        res = backtest(a.strategy, data, sectors, bench, ppy)
        print(json.dumps({k: round(v, 3) if isinstance(v, float) else v for k, v in res.metrics.items()}, indent=2))
        print(f"risk events: {res.risk_events[:5]}")
    elif a.cmd == "validate":
        from .research import full_validation
        from .strategies import REGISTRY
        names = list(REGISTRY) if a.strategy == "all" else [a.strategy]
        for n in names:
            try:
                r = full_validation(n, a.years, a.limit)
                print(f"{n}: Sharpe {r['sharpe']:.2f} CAGR {r['cagr']:.1f}% MaxDD {r['maxDd']:.1f}% trades {r['trades']} → {r['stage']}"
                      + ("" if r["verdict"] else f"  ({'; '.join(r['reasons'])})"))
            except Exception as e:
                print(f"{n}: failed – {e}")
    elif a.cmd == "train":
        import pandas as pd
        from .ml.features import build, label
        from .ml.model import export_onnx, train
        from .research import load_universe
        data, _ = load_universe("nifty100", a.years, a.limit)
        X = pd.concat([build(d) for d in data.values()]); y = pd.concat([label(d) for d in data.values()])
        tm = train(X, y)
        out = CONFIG.state_dir / "models"; out.mkdir(parents=True, exist_ok=True)
        meta = export_onnx(tm, str(out / "latest.onnx"), str(out / "latest.json"))
        print(f"CV AUC {tm.cv_auc:.3f}; calibrated threshold {tm.threshold_cal:.2f}; exported {out / 'latest.onnx'}")
        print(json.dumps(meta, indent=2))
    elif a.cmd == "regime":
        from .data import yahoo
        from .regime import detect, probabilities
        r = detect(yahoo.download("NIFTY 50", "1D"), vix=yahoo.download("INDIA VIX", "1D")["close"])
        print(r.tail(10)); print(probabilities(r))
    elif a.cmd == "token":
        print(pysecrets.token_urlsafe(32))
    elif a.cmd == "secrets":
        from cryptography.fernet import Fernet
        from .config import save_secrets
        key = os.environ.get("TRADELENS_MASTER_KEY") or Fernet.generate_key().decode()
        names = ["TRADELENS_API_TOKEN", "TRADELENS_RELAY_TOKEN", "DELTA_API_KEY", "DELTA_API_SECRET", "TRADELENS_BROKER_SESSION"]
        save_secrets({n: os.environ[n] for n in names if os.environ.get(n)}, key)
        print("Wrote secrets.enc. Keep this master key safe and set it as TRADELENS_MASTER_KEY when starting the engine:\n" + key)
    return 0


if __name__ == "__main__":
    sys.exit(main())
