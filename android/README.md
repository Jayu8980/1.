# AI Trader — Android app

Kotlin · Jetpack Compose · MVVM · Hilt · Room · Coroutines/Flow · Glance widget · WorkManager.
minSdk 26, target/compile 36 (Android 16), arm64.

## Install the prebuilt APK
`dist/AITrader-0.1.0-release.apk` — copy it to the phone, open it, allow "Install unknown apps"
for your file manager. It opens in **Demo mode** (synthetic on-device data, paper only).

## Screens
Home (P&L, equity vs NIFTY 50, mode badge) · Positions & orders (one-tap exit) · Signals
(confidence, regime, reason, R:R, edge after costs, why skipped) · Strategies (enable, allocation,
live vs backtest stats, drift warnings) · Backtest (server-run tearsheet: CAGR, Sharpe, Sortino,
max DD, Calmar, PF, expectancy, alpha/beta, cost drag, deflated Sharpe, ≥200-trade gate) ·
Risk (limits + big red KILL SWITCH) · AI Analyst (advisory only) · Logs & alerts · Settings.
Home-screen widget: today's P&L + one-tap kill switch. Dark theme by default, follows system.

## Security model
- **Money actions** (exit, strategy change, risk change, mode switch) are signed with an EC P-256
  key in the Android Keystore that only works right after a *strong biometric*. The engine
  verifies `X-Action-Signature` over `METHOD\nPATH\nTS\nsha256(body)` with the public key the
  phone registers at login. A stolen JWT alone cannot move money.
- **Kill switch is not biometric-gated** on purpose — stopping must be instant (app + widget).
- **LIVE** = typed phrase + biometric signature + server `ENGINE_ALLOW_LIVE=true`. Demo can't go LIVE.
- JWT encrypted at rest with an AES-GCM Keystore key. No broker creds / TOTP on the phone.
- HTTPS only (cleartext disabled), OkHttp certificate pinning with the pin set in Settings.
- Backups disabled. Offline: last good response of every screen is cached in Room.

## Connecting to the engine
Settings → turn off Demo → engine URL `https://your-vm` + TLS pin → log in.
Get the pin on the VM:
```bash
openssl s_client -connect your-vm:443 </dev/null 2>/dev/null | openssl x509 -pubkey -noout \
 | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | base64   # prefix with sha256/
```
API contract: `app/src/main/java/com/aitrader/data/remote/EngineApi.kt` (base path `/api/v1/`).
The engine-side endpoints are Phase 4 work — until then use Demo mode.

## Build it yourself
```bash
export ANDROID_HOME=~/Android/Sdk          # platforms;android-36, build-tools;36.0.0
cd android
./gradlew testDebugUnitTest                 # unit tests
./gradlew connectedDebugAndroidTest         # UI tests (device/emulator attached)
./gradlew assembleRelease                   # signed if keystore.properties exists
```
`keystore.properties` (git-ignored):
```
storeFile=release.jks
storePassword=…
keyAlias=aitrader
keyPassword=…
```
Or set `AITRADER_KEYSTORE`, `AITRADER_KEYSTORE_PASSWORD`, `AITRADER_KEY_ALIAS`, `AITRADER_KEY_PASSWORD`.
Keep the keystore safe: updates must be signed with the same key.

## Notifications
Local notifications today (fills, stops, risk breaches, kill switch, engine errors) via a 15-min
WorkManager sync. Real-time FCM push needs your Firebase `google-services.json` (Phase 5).
