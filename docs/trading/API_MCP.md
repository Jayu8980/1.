# Broker API & MCP server

Connect **AI assistants (MCP)** and **your own code (REST)** to your broker connections through the
engine. Broker credentials never leave the engine; clients get scoped API keys.

## 1. Create a key (in the app)
More → **API & MCP** → New API key → pick scopes → fingerprint. The key (`atk_…`) is shown **once**
together with ready-to-paste MCP configs. Revoke any time from the same screen.

| Scope | Allows |
|---|---|
| `read` | brokers, quotes, instrument search, account/positions/orders (engine, real broker, paper), risk, signals, approval status |
| `trade` | place orders — **PAPER executes; LIVE creates an approval request on your phone** |
| `control` | cancel orders, engage kill switch, auto-trade OFF, start broker logins, add to watchlist |

**No key can ever:** switch LIVE on, turn auto-trade on, change risk limits, promote strategies,
release the kill switch, or approve a live order. Those are biometric-only in the app.
Keys are stored hashed (0600), rate-limited (`ENGINE_API_RATE_PER_MIN`, default 120/min) and every
call that changes anything is in the audit log as `key:<id> (<name>)`.

## 2. MCP — Claude Desktop / Claude Code / any MCP client
**Remote (recommended)** — the engine serves MCP at `https://<ENGINE_DOMAIN>/mcp/` (streamable HTTP):
```json
{ "mcpServers": { "aitrader": {
    "type": "http", "url": "https://trader.example.com/mcp/",
    "headers": { "X-API-Key": "atk_..." } } } }
```
Claude Code: `claude mcp add --transport http aitrader https://trader.example.com/mcp/ --header "X-API-Key: atk_..."`

**Local stdio** — runs on your computer and proxies to the engine's REST API:
```json
{ "mcpServers": { "aitrader": {
    "command": "python", "args": ["-m", "app.mcp_server", "--engine", "https://trader.example.com"],
    "cwd": "/path/to/repo/engine", "env": { "AITRADER_API_KEY": "atk_..." } } } }
```

### Tools
| Tool | Scope | Notes |
|---|---|---|
| `aitrader_status` | read | mode, kill switch, active broker, feeds, key scopes |
| `aitrader_list_brokers` | read | all 6 broker connections and their session state |
| `aitrader_connect_broker` | control | TOTP/API-key brokers connect; OAuth returns a login URL for the human |
| `aitrader_get_quotes` | read | streamed LTPs |
| `aitrader_search_instruments` / `aitrader_watch` | read / control | find + stream new symbols |
| `aitrader_get_account` / `_positions` / `_orders` | read | `account`: engine, live (real broker), paper |
| `aitrader_get_risk` / `aitrader_get_signals` | read | limits & usage; auto-trader decisions |
| `aitrader_place_order` | trade | PAPER → filled; LIVE → `PENDING_APPROVAL` + `intentId` |
| `aitrader_get_order_request` | read | PENDING / APPROVED / REJECTED / EXPIRED / FAILED |
| `aitrader_cancel_order` / `aitrader_engage_kill_switch` / `aitrader_autotrade_off` | control | risk-reducing |

All tools carry MCP annotations (`readOnlyHint`, `destructiveHint`…) so clients can ask before
running write tools. Errors come back as readable messages (e.g. missing scope, not in watchlist).

## 3. REST API
Base `https://<ENGINE_DOMAIN>/api/v1/ext`, header `X-API-Key: atk_…`. OpenAPI at `/docs`.
```bash
K="X-API-Key: atk_..."; E=https://trader.example.com/api/v1/ext
curl -H "$K" $E/status
curl -H "$K" "$E/quotes?symbols=SBIN-EQ,BTCUSD"
curl -H "$K" "$E/positions?account=live"
curl -H "$K" -H 'Content-Type: application/json' -X POST $E/orders \
     -d '{"symbol":"SBIN-EQ","exchange":"NSE","side":"BUY","qty":5,"orderType":"LIMIT","price":800,"reason":"test"}'
curl -H "$K" $E/intents/oi_0123456789      # poll a live approval request
```

## 4. Live approval flow
1. Client places an order while the engine is LIVE → engine validates it and creates a request
   (expires in `ENGINE_INTENT_TTL_S`, default 300 s). Nothing is sent to the broker.
2. Phone: Home shows **“N orders waiting for your approval”** (and a notification) → Approvals →
   review symbol, size, requester and reason → **Approve (REAL)** + fingerprint, or Reject.
3. On approval the order goes through the full gateway again (kill switch, risk limits, static-IP
   check) — if the engine left LIVE in the meantime, approval fails safely.
