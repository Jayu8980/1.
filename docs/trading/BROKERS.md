# Broker connections

The engine talks to brokers; the phone never holds broker keys or session tokens.
One broker is **active** and receives LIVE orders. PAPER never touches a broker.
Switch the active broker from the app (Brokers screen, biometric) — only allowed in PAPER.

| Broker | Auth | Daily re-login | Server settings | Instrument id in `Contract.ids` |
|---|---|---|---|---|
| Angel One (SmartAPI) | TOTP, done by the server | automatic (08:30 IST) | `ENGINE_SMARTAPI_*` | `token` field (symboltoken) |
| Zerodha (Kite Connect) | Browser OAuth | yes, token dies ~06:00 IST | `ENGINE_ZERODHA_API_KEY/SECRET` | optional `zerodha` (defaults to symbol without `-EQ`) |
| Upstox | Browser OAuth | yes, token dies ~03:30 IST | `ENGINE_UPSTOX_CLIENT_ID/SECRET` | **required** `upstox` = `instrument_key` e.g. `NSE_EQ|INE062A01020` |
| Dhan | Access token on server | when the token expires (≤30 days) | `ENGINE_DHAN_CLIENT_ID/ACCESS_TOKEN` | **required** `dhan` = `securityId` |
| Fyers | Browser OAuth | yes | `ENGINE_FYERS_APP_ID/SECRET` | optional `fyers` (defaults to `NSE:<symbol>`) |

## Setup, per broker
1. Create an API app in the broker's developer console.
2. Register the **engine VM's static IP** with that broker (SEBI retail-algo rule — every broker).
3. For OAuth brokers set the redirect URL to
   `https://<ENGINE_PUBLIC_BASE_URL>/api/v1/brokers/<id>/callback` (`zerodha`, `upstox`, `fyers`).
4. Put the keys in the server's `.env` / secrets manager and restart the engine.
5. App → More → **Brokers** → *Log in* / *Connect*. OAuth opens the broker's login page in your
   browser; after login the engine's page has a **Return to AI Trader** link (`aitrader://brokers`).
6. Tap **Use for LIVE** on the broker you want (PAPER mode only, biometric).

## How a connection works
```
App ──(signed POST /brokers/zerodha/connect)──► Engine ──► mints single-use state, returns login URL
App ──opens browser──► kite.zerodha.com login ──redirect──► Engine /callback?request_token&state
Engine ──verifies state (10 min, single use) ──► exchanges token with broker ──► stores token 0600 on VM
```
- Connect/Activate need a fresh (≤60 s), un-replayed biometric signature from the registered phone.
- LIVE mode is refused if the active broker isn't connected; switching or disconnecting the active
  broker is refused while LIVE.
- Every connect / failure / activate / disconnect goes to the hash-chained audit log.
- Each adapter has its own order rate limiter: min(`ENGINE_ORDERS_PER_SEC`, the broker's own cap).

## ⚠ Before real money
The Zerodha, Upstox, Dhan and Fyers adapters are written from the brokers' public API docs and
tested only against mocked responses. Before LIVE on any of them, run a small manual check on a real
account: place → modify → cancel a far-from-market limit order, and check positions/funds parsing.
Instrument masters (for `Contract.ids`) get loaded in Phase 2.
