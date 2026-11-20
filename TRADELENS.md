# TradeLens – AI Trading Analyser

Three parts, all in this repository:

| Folder | What it is |
|---|---|
| `android-app/` | Kotlin + Jetpack Compose app (Android 8 → 16, `arm64-v8a`, dark theme) |
| `engine/` | Python "Alpha Engine": event-driven backtest / paper / live, regimes, strategy library, ML, anti-overfitting suite, REST + WebSocket API |
| `relay-server/` | Small FastAPI order relay for your **static-IP** VPS (SEBI retail-algo rules) |

> **Not financial advice.** Everything here is an analysis and research tool. Backtests can overfit, live markets differ, leverage can wipe out margin. You are responsible for every order.

---

## 1. Install the APK on your phone

### Option A – download from GitHub Actions (no Android Studio)
1. Push this repo to GitHub. The workflow `.github/workflows/build-apk.yml` runs the unit tests and builds a **signed release APK**.
2. Open the repo → **Actions** → *Build TradeLens APK* → latest run → download the artifact `TradeLens-1.0.N-arm64` (a zip containing `app-release.apk`).
3. On the phone: open the APK → allow "Install unknown apps" for your browser/file manager → Install.

**Use your own permanent signing key** (otherwise each build is signed with a temporary key and Android will refuse to update over a previous install):
```bash
keytool -genkeypair -keystore tradelens.jks -alias tradelens -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 tradelens.jks > tradelens.jks.b64      # Windows: certutil -encode tradelens.jks tradelens.jks.b64
```
Add repository secrets (Settings → Secrets and variables → Actions): `KEYSTORE_BASE64` (contents of the .b64 file), `KEYSTORE_PASSWORD`, `KEY_ALIAS` (`tradelens`), `KEY_PASSWORD`. Keep the .jks file safe – you need the same key for every future update.

### Option B – build locally
```bash
cd android-app
export TRADELENS_KEYSTORE=/path/tradelens.jks TRADELENS_KEYSTORE_PASSWORD=... TRADELENS_KEY_ALIAS=tradelens TRADELENS_KEY_PASSWORD=...
./gradlew :app:testDebugUnitTest :app:assembleRelease      # needs JDK 17+ and the Android SDK (platform 36)
adb install -r app/build/outputs/apk/release/app-release.apk
```

### First launch
Accept the disclaimer → allow notifications → (optional) biometric lock is on by default. The app works immediately with **free delayed Yahoo data** and **live Delta Exchange public data**; every price shows a source badge (green LIVE / amber DELAYED).

Permissions: `INTERNET`, `POST_NOTIFICATIONS`, `USE_BIOMETRIC`. WorkManager additionally merges the install-time permissions `ACCESS_NETWORK_STATE`, `WAKE_LOCK` and `RECEIVE_BOOT_COMPLETED`, which background alerts need to survive reboots. No ads, no analytics, no trackers.

---

## 2. Connecting data sources (Settings tab → More → Settings)

All keys/tokens are stored in Android Keystore–backed **EncryptedSharedPreferences**. Passwords/PINs are never stored. Broker tokens expire daily – the app shows a **"Reconnect broker"** banner.

**OAuth redirect.** Brokers only accept `https://` redirect URLs, so the engine provides a bounce endpoint: register `https://<your-engine>/auth/callback/<broker>` in the broker's developer console; it forwards to `tradelens://auth/<broker>` and the app completes login. (If you do not run the engine, use any static page that redirects to `tradelens://auth/<broker>?<same query>`.)

| Broker | Where to get credentials | Fields in the app | Login |
|---|---|---|---|
| **Upstox** (v2/v3) | developer portal → create app | API key, API secret, Redirect URI | OAuth (Custom Tab) |
| **Angel One SmartAPI** | smartapi.angelbroking.com → create app; enable TOTP | SmartAPI key, client code | PIN + TOTP (PIN not stored) |
| **Fyers v3** | myapi.fyers.in → create app | App ID, secret, Redirect URI | OAuth |
| **Dhan (DhanHQ v2)** | web.dhan.co → Profile → DhanHQ Trading APIs | Client ID | paste the 24-h access token (data APIs may need Dhan's paid data plan) |
| **Zerodha Kite Connect** | developers.kite.trade (paid app) | API key, API secret | OAuth; checksum = SHA-256(api_key + request_token + api_secret) |

After login the app downloads that broker's **instrument master** into Room (and refreshes it daily on Wi-Fi). Pick one broker as **Primary data** – it becomes the first link of the fallback chain *broker → Yahoo (delayed) → offline cache*. Streams use each broker's socket (Kite binary ticker, Upstox v3 protobuf feed, Angel SmartStream, Dhan binary feed; Fyers uses 3-second batched quotes) and subscribe **only to the symbols visible on screen**, reconnecting with exponential backoff.

> Broker APIs change. Endpoints were written against each broker's v2/v3 documentation; if a broker changes a path or field, fix it in `android-app/app/src/main/java/com/tradelens/app/data/broker/<Broker>Broker.kt`.

### Delta Exchange India
- Environment toggle **Testnet** (`https://cdn-ind.testnet.deltaex.org`, `wss://socket-ind.testnet.deltaex.org`) / **Production** (`https://api.india.delta.exchange`, `wss://socket.india.delta.exchange`). Never `api.delta.exchange` (Delta Global).
- Create API keys on the matching environment; paste key + secret; tap **Test connection**. Requests are signed `HMAC-SHA256(secret, method + timestamp + path + query + body)`; the client syncs its clock from the server and re-signs once on `SignatureExpired`.
- **Live orders** are off by default and Testnet-only until you separately switch on **Unlock Production orders**.

### AI analysis
Settings → AI: choose Claude (default model `claude-opus-5-5`), OpenAI, Gemini or a local OpenAI-compatible endpoint, and paste **your own** key. The model receives only a compact JSON of computed data and returns structured analysis (bias, levels, bull/bear case, setup with entry/SL/targets/R:R, confidence, risks, uncertainty). It has no tools: it cannot place orders or change limits.

---

## 3. Orders, SEBI rules and the relay

Since 1 April 2026 Indian brokers only accept API orders from a **static IP registered with the broker**. A phone does not have one, so:

- **Default:** the order ticket offers **Paper trade** and **Open in broker app** (launches Kite/Upstox/Angel/Fyers/Dhan, or their web terminal).
- **Advanced:** **Live via relay (LIMIT)** – the app sends the approved LIMIT order to *your* relay on a static-IP VPS, which tags it with your broker-issued algo ID and forwards it.
- **Delta:** read-only + paper by default; optional live market/limit/stop/bracket orders (Testnet first).

Every live order shows a confirmation with size, price, leverage, SL, margin, charges and (crypto) estimated liquidation price, and needs your tap (or a per-strategy **Auto** toggle on the engine, off by default). Large orders are sliced into TWAP child orders; warnings appear inside the first/last 5 minutes. Every order, approval and response is written to the **Audit trail** (exportable CSV).

### Set up the relay (Ubuntu VPS with a static/elastic IP)
```bash
# 1. VPS with a static IP (e.g. AWS Lightsail/EC2 Elastic IP, DigitalOcean Reserved IP). Register that IP with your broker.
sudo apt-get update && sudo apt-get install -y docker.io docker-compose-v2 git
git clone <your repo> && cd <repo>/relay-server
cp .env.example .env && chmod 600 .env
python3 -c "import secrets;print(secrets.token_urlsafe(32))"   # → RELAY_TOKEN
nano .env            # RELAY_DOMAIN, RELAY_TOKEN, ALGO_ID_<BROKER>, broker API keys
# 2. DNS: A-record relay.example.com → the static IP. Open ports 80/443 only.
sudo docker compose up -d --build                   # Caddy obtains HTTPS automatically
curl -H "Authorization: Bearer $RELAY_TOKEN" https://relay.example.com/health   # shows egress_ip – must equal the registered IP
```
In the app: Settings → Order relay → URL + token → **Test relay** → enable **Indian orders via relay**. Emergency stop: `curl -X POST .../kill -d '{"enabled":true}' -H ...` or `touch state/KILL`.

Relay safeguards (independent of the app): LIMIT only, max order value, max orders/day, replay protection (unique clientRef, approval ≤ 120 s old), algo-ID tagging, append-only audit log.

---

## 4. Run the strategy engine

### Windows laptop (16 GB RAM, no GPU needed)
1. Install **Python 3.11 or 3.12** from python.org (tick "Add to PATH").
2. PowerShell:
   ```powershell
   cd engine
   Set-ExecutionPolicy -Scope CurrentUser RemoteSigned
   .\run_windows.ps1          # creates .venv, installs requirements, prints your API token, starts http://127.0.0.1:8000
   ```
3. The phone must reach the engine over **HTTPS**. Easiest: `cloudflared tunnel --url http://127.0.0.1:8000` (prints an https URL) or Tailscale Funnel. Put that URL and the token in the app → Settings → Engine → **Test engine**.

### Linux cloud VPS
```bash
cd engine && cp .env.example .env && nano .env      # ENGINE_DOMAIN, TRADELENS_API_TOKEN (python -m tradelens token)
sudo docker compose up -d --build                   # HTTPS via Caddy at https://ENGINE_DOMAIN
```
Optionally keep secrets encrypted: `TRADELENS_MASTER_KEY=... python -m tradelens secrets` writes `secrets.enc` (Fernet) from your environment; start the engine with only `TRADELENS_MASTER_KEY` set.

### Research workflow
```bash
python -m tradelens backtest xs_momentum --years 10      # event-driven backtest, real charges, slippage, impact
python -m tradelens validate all --years 10               # walk-forward + purged CV + DSR + PBO + Monte Carlo + stability
python -m tradelens train                                 # LightGBM direction model → state/models/latest.onnx
python -m tradelens regime                                # today's market regime
python -m pytest -q                                       # engine tests
```
`validate` writes each strategy's report (equity vs NIFTY 50 TRI / BTC, drawdown, monthly heatmap) and moves it through the **promotion pipeline**: RESEARCH → (pass) PAPER (≥ 4 weeks live data) → LIVE_SMALL → LIVE, or REJECTED. A strategy whose live returns drift beyond **2σ** of its backtest expectation is **automatically demoted**. Only PAPER/LIVE strategies can be enabled in the app; in the app → Engine → Get ML model downloads the ONNX model for on-device scoring.

Acceptance gates: ≥ 5 years of data, ≥ 200 trades, PBO ≤ 0.3, deflated-Sharpe probability ≥ 0.9, parameter-stability ≥ 0.4, Monte-Carlo 95th-percentile drawdown ≤ 25 %.

### Strategy library
Cross-sectional momentum · Trend following (Donchian/Supertrend, ATR trail) · RSI(2)/Bollinger mean reversion · Opening-range breakout + VWAP (intraday, needs 5-min data) · Iron-condor income (defined risk only; researched on a Black-Scholes/India-VIX synthetic) · Sector pairs stat-arb (Engle-Granger) · Post-results drift · Delta funding carry, basis trade, BTC/ETH trend · LightGBM/XGBoost ensemble with calibrated threshold.

Portfolio construction: meta-model weights by out-of-sample Sharpe × regime, risk ≤ 1 % per trade, ≤ 0.25 Kelly, volatility target ~13 %, ≤ 10 % per stock, ≤ 25 % per sector, correlation cap, F&O margin ≤ 50 %, NIFTY futures hedge suggestion in high-vol/crash regimes. Risk manager: 2 % daily loss → flatten & halt for the day; 10 % drawdown → circuit breaker requiring manual restart; daily order cap; per-strategy and global kill switches.

---

## 5. App features at a glance
Watchlists with live prices/sparklines · candlestick chart (pinch-zoom, pan, crosshair, trendline / horizontal level / Fibonacci) · indicators in pure Kotlin (SMA, EMA, VWAP, RSI, MACD, Stochastic, Bollinger, ATR, ADX, Supertrend, OBV, Ichimoku, Pivots) · pattern detection (S/R zones, breakouts, candlestick and chart patterns, RSI/MACD divergence) · multi-timeframe confluence score with breakdown · NIFTY 50/200/500 screener with saved screens · no-code backtester vs NIFTY buy & hold · paper trading (₹10,00,000, market/limit/SL, journal, real charges, Delta prices) · multi-broker portfolio with sector allocation · option chain (OI, ΔOI, IV, Black-Scholes Greeks, PCR, max pain, OI buildup, depth) · Delta crypto analytics (funding history, OI, basis, BTC/ETH options, liquidation calculator) · position-size & R:R tools · price/indicator/pattern alerts (WorkManager, every 15 min, 24/7 for crypto) · AI analysis + chat + pre/post-market briefs · engine signals/approvals/strategy toggles/risk/leaderboard · audit trail · PDF/CSV exports.

**Known limits:** Android runs periodic background work at most every 15 minutes, so background alerts are not tick-by-tick (open the app for live streams). FII/DII flows come from NSE via the engine. Yahoo data is delayed ~15 min and has no option chains. Charges in `domain/Charges.kt` and `engine/tradelens/charges.py` are one shared rate table – update it when SEBI/the Budget/exchanges revise rates.

## 6. Tests
- Android (JVM): `cd android-app && ./gradlew :app:testDebugUnitTest` – indicators, charges, backtester, rules, risk limits, paper fills, Black-Scholes, Delta signing, Upstox protobuf decoding, patterns/confluence.
- Engine: `cd engine && python -m pytest -q` – charges, engine accounting/no-look-ahead, risk breakers, validation (purged K-fold, PBO, DSR, Monte Carlo, walk-forward), promotion/demotion, regimes, ML + ONNX parity, API auth/kill switch/OAuth bounce.
- Relay: `cd relay-server && python -m pytest -q`.
