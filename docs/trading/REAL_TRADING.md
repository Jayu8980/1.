# Real trading checklist

The app no longer starts in Demo. First launch asks: **Connect my engine (real trading)** or
Explore demo.

## One-time
1. Engine on the Mumbai VM with HTTPS (`infra/README.md`), `ENGINE_ALLOW_LIVE=true`,
   `ENGINE_STATIC_IP` = the VM IP, static IP registered with your broker.
2. App → Settings: engine URL, TLS pins, log in (registers this phone's biometric key).
3. App → More → Brokers: connect your broker → **Use for LIVE**.
4. App → Risk: set your real limits (daily loss, risk per trade, max position).

## Each session
- Home → **LIVE trading** switch → type the phrase → fingerprint. Home and Positions now show
  "REAL account".
- Markets → tap a symbol → order ticket. The banner says **REAL MONEY · <broker>** in red when
  LIVE. A second confirmation and your fingerprint are required for every real order.
- **Real broker account** chip (Home / Positions) shows your actual broker positions and orders
  even while the engine is in PAPER; working orders there can be cancelled.
- The engine restarts in PAPER after any restart — switch LIVE on again deliberately.

## What protects each real order
kill switch → mode re-check (ticket refused if LIVE/PAPER changed since you opened it) →
risk veto (daily loss, drawdown, position size) → static-IP check before **every** live order →
≤8 orders/s → broker. MARKET orders are sent as LIMIT 0.5% through LTP. Every step is in the
hash-chained audit log.

## Auto-trading with real money
Auto-trade places real orders only for strategies promoted past the gate
(`docs/trading/AUTOTRADE.md`). Strategies in PAPER stage stay on paper even when LIVE is on.
