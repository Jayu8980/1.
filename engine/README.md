# Trading Engine

See `../docs/trading/ARCHITECTURE.md`. Default mode is **PAPER**.

## Run tests
```bash
cd engine
python3 -m venv .venv && . .venv/bin/activate
pip install -r requirements-dev.txt
pytest -q
```
Docker: `docker build --target test .` runs the suite inside the image.

## What Phase 1 contains
- `broker/paper.py` — fills (market w/ adverse slippage, limit, SL-M, SL-L), positions,
  realised/unrealised P&L, flips, margin approximation, no-short-delivery rule.
- `broker/costs.py` — brokerage, STT, exchange txn, SEBI, stamp, GST, optional DP charge.
  **Rates must be verified against Angel One's current charge sheet** (they change by circular/budget).
- `broker/smartapi/` — async REST client (no SDK), TOTP session manager with daily
  logout/login cycle and one-shot re-auth, SmartAPI → internal model mapping.
- `core/ratelimit.py` — sliding-window limiter (never > N orders in any 1 s window).
- `core/ipguard.py` — static-IP check, fails closed.
- `core/audit.py` — append-only, SHA-256 hash-chained JSONL with `verify()`.
- `execution/gateway.py` — the single order path; kill switch; 3-lock LIVE arming.

SmartAPI setup, VM and APK instructions are added in Phases 4–6.
