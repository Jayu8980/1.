# Infra: putting the engine online so the APK gets real data

## 1. VM (Mumbai region, static IP)
Any Linux VM in Mumbai (AWS ap-south-1, GCP asia-south1, Azure Central India…) with a
**reserved static public IP**. Open inbound TCP 80 and 443 only, plus SSH from your own IP.
```bash
curl -fsSL https://get.docker.com | sh
git clone <this repo> && cd <repo>/engine
cp .env.example .env && chmod 600 .env    # fill in, see below
```

## 2. DNS
Create an A record, e.g. `trader.example.com`, pointing to the static IP. Set `ENGINE_DOMAIN` and
`ENGINE_PUBLIC_BASE_URL=https://trader.example.com` in `.env`.

## 3. Secrets in `.env`
```bash
python3 -c "import secrets;print(secrets.token_urlsafe(48))"   # -> ENGINE_JWT_SECRET
docker compose run --rm engine python -m app.api.security       # -> ENGINE_APP_PASSWORD_HASH
```
Set `ENGINE_APP_USER`, `ENGINE_STATIC_IP=<the VM IP>`, and the keys for the brokers you use
(`docs/trading/BROKERS.md`). Register the static IP with every broker you trade through.

## 4. Start
```bash
docker compose up -d --build
docker compose logs -f engine          # watch the feed come up
curl https://trader.example.com/health
```

## 5. TLS pins (needed by the app)
Pin **two** keys so certificate renewals never lock you out:
1. Let's Encrypt's root, ISRG Root X1 (stable for years):
   `sha256/C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M=`
2. Your current server key (optional extra, changes when Caddy rotates the key):
```bash
openssl s_client -connect trader.example.com:443 -servername trader.example.com </dev/null 2>/dev/null \
 | openssl x509 -pubkey -noout | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | base64
```
Paste the pins in the app, comma-separated and each prefixed with `sha256/`.

## 6. Connect the app
App → More → Settings: turn **Demo mode off** → Engine URL `https://trader.example.com` → pins →
Save → log in with `ENGINE_APP_USER` / password (registers the phone's biometric key).
Then More → Brokers → connect your broker → **Markets** tab shows live prices, Home/Positions
show your real paper (or live) account.

## What streams live
| Instruments | Feed | Needs |
|---|---|---|
| Delta crypto (BTCUSD…) | Delta public WebSocket | nothing (public) |
| NSE/BSE/NFO | Angel One SmartStream WebSocket | Angel One connected |
| NSE/BSE/NFO | REST polling every 2 s | any other connected Indian broker |
