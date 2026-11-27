"""AI Trader MCP server: lets MCP clients (Claude Desktop / Claude Code / any MCP client) use
your broker connections through the engine.

Two ways to run it, same tools:
  * Remote (HTTPS): mounted inside the engine at  https://<ENGINE_DOMAIN>/mcp/
      header:  X-API-Key: atk_...    (or Authorization: Bearer atk_...)
  * Local (stdio): python -m app.mcp_server --engine https://<ENGINE_DOMAIN>
      env:     AITRADER_API_KEY=atk_...      (proxies to the engine's /api/v1/ext API)

Safety model (enforced by the ENGINE, not by this file):
  - read tools need scope "read"; order tools need "trade"; cancel/kill/connect need "control".
  - In PAPER, orders execute (simulated). In LIVE, an order becomes an approval request that
    only the phone can approve with fingerprint/face. No MCP tool can switch LIVE on, turn
    auto-trade on, change risk limits or approve its own orders.
"""
from __future__ import annotations

import argparse
import os
import sys
from typing import Annotated, Any, Literal

import httpx
from pydantic import Field

from mcp.server.mcpserver import Context, MCPServer
from mcp.server.mcpserver.exceptions import ToolError
from mcp.types import ToolAnnotations

INSTRUCTIONS = (
    "Tools for an Indian-markets trading engine (NSE/BSE/F&O via Angel One, Zerodha, Upstox, Dhan, Fyers; "
    "crypto via Delta Exchange). Start with aitrader_status. Prices are INR except DELTA (USD). "
    "aitrader_place_order executes immediately only in PAPER mode; in LIVE mode it returns PENDING_APPROVAL and a "
    "human must approve on their phone — tell the user to open the AI Trader app, then poll "
    "aitrader_get_order_request. Never retry a pending live order. Symbols must be in the watchlist "
    "(aitrader_search_instruments + aitrader_watch). Use aitrader_engage_kill_switch only if the user asks or "
    "something is clearly wrong; only the human can release it."
)

READ = ToolAnnotations(read_only_hint=True, destructive_hint=False, idempotent_hint=True, open_world_hint=True)
CONTROL = ToolAnnotations(read_only_hint=False, destructive_hint=False, idempotent_hint=True, open_world_hint=True)
TRADE = ToolAnnotations(read_only_hint=False, destructive_hint=True, idempotent_hint=False, open_world_hint=True)

Account = Annotated[Literal["engine", "live", "paper"], Field(
    description="'engine' = the account the engine trades now (paper or live); 'live' = your real broker account "
                "(read-only view even in PAPER); 'paper' = the simulated account")]


class Backend:
    """How tools reach the engine. `call(ctx, scope, method, **kwargs)`."""

    async def call(self, ctx: Context | None, scope: str, method: str, **kw) -> Any:  # pragma: no cover
        raise NotImplementedError


class LocalBackend(Backend):
    """In-process (mounted in the engine). The API key was verified by ApiKeyMiddleware."""

    def __init__(self, state):
        self.state = state

    async def call(self, ctx, scope, method, **kw):
        req = getattr(ctx.request_context, "request", None) if ctx else None
        key = getattr(getattr(req, "state", None), "api_key", None) if req is not None else None
        if key is None:
            raise PermissionError("not authenticated: send X-API-Key")
        if scope not in key.scopes:
            raise PermissionError(f"this API key lacks the '{scope}' scope (has: {', '.join(key.scopes)})")
        return await getattr(self.state.external, method)(key, **kw)


# method -> (HTTP verb, path template, how kwargs are sent)
ROUTES: dict[str, tuple[str, str, str]] = {
    "status": ("GET", "/status", "query"), "brokers": ("GET", "/brokers", "query"),
    "connect_broker": ("POST", "/brokers/{broker_id}/connect", "none"),
    "quotes": ("GET", "/quotes", "query"), "search": ("GET", "/instruments/search", "query"),
    "account": ("GET", "/account", "query"), "positions": ("GET", "/positions", "query"),
    "orders": ("GET", "/orders", "query"), "risk": ("GET", "/risk", "query"), "signals": ("GET", "/signals", "query"),
    "intent": ("GET", "/intents/{intent_id}", "none"), "place_order": ("POST", "/orders", "json"),
    "cancel": ("POST", "/orders/{order_id}/cancel", "query"), "kill_switch": ("POST", "/kill-switch", "json"),
    "autotrade_off": ("POST", "/autotrade/off", "none"), "watch": ("POST", "/watch", "json"),
}


class RemoteBackend(Backend):
    """stdio mode: proxy to https://<engine>/api/v1/ext with the API key from the environment."""

    def __init__(self, base_url: str, api_key: str, transport: httpx.AsyncBaseTransport | None = None):
        self.http = httpx.AsyncClient(base_url=base_url.rstrip("/") + "/api/v1/ext", timeout=30,
                                      headers={"X-API-Key": api_key, "User-Agent": "aitrader-mcp"}, transport=transport)

    async def call(self, ctx, scope, method, **kw):
        verb, path, mode = ROUTES[method]
        path = path.format(**kw)
        kw = {k: v for k, v in kw.items() if "{" + k + "}" not in ROUTES[method][1] and v is not None}
        if method == "quotes" and "symbols" in kw:
            kw["symbols"] = ",".join(kw["symbols"])
        if method == "place_order":
            kw = {**kw.pop("ticket"), "reason": kw.get("reason", "")}
        if method in ("account", "positions", "orders", "cancel") and "venue" in kw:
            kw["account"] = kw.pop("venue")
        r = await self.http.request(verb, path, params=kw if mode == "query" else None,
                                    json=kw if mode == "json" else None)
        if r.status_code >= 400:
            try:
                detail = r.json().get("detail")
            except ValueError:
                detail = r.text[:200]
            raise PermissionError(detail) if r.status_code in (401, 403, 429) else ValueError(detail)
        return r.json()


class _Guarded(Backend):
    """Turns permission/validation/broker problems into ToolErrors whose text reaches the client, so the
    model sees an actionable reason (missing scope, not in watchlist, kill switch on…), never a stack trace."""

    def __init__(self, inner: Backend):
        self.inner = inner

    async def call(self, ctx, scope, method, **kw):
        try:
            return await self.inner.call(ctx, scope, method, **kw)
        except ToolError:
            raise
        except PermissionError as e:
            raise ToolError(f"Not allowed: {e}") from None
        except (ValueError, KeyError) as e:
            raise ToolError(f"Invalid request: {e}") from None
        except httpx.HTTPError as e:
            raise ToolError(f"Engine unreachable: {e.__class__.__name__}") from None
        except Exception as e:  # broker errors etc. — message only, no internals
            raise ToolError(f"{e.__class__.__name__}: {str(e)[:200]}") from None


def build_mcp(backend: Backend) -> MCPServer:
    backend = _Guarded(backend)
    mcp = MCPServer("aitrader_mcp", title="AI Trader", instructions=INSTRUCTIONS, version="0.7.0")

    @mcp.tool(name="aitrader_status", annotations=READ)
    async def status(ctx: Context) -> dict:
        """Engine status: PAPER/LIVE mode, kill switch, active broker, auto-trade, which feeds are streaming,
        and the scopes of the API key in use. Call this first."""
        return await backend.call(ctx, "read", "status")

    @mcp.tool(name="aitrader_list_brokers", annotations=READ)
    async def list_brokers(ctx: Context) -> list:
        """All broker connections (angelone, zerodha, upstox, dhan, fyers, delta): configured, connected, account,
        session expiry, which one is ACTIVE for live orders."""
        return await backend.call(ctx, "read", "brokers")

    @mcp.tool(name="aitrader_connect_broker", annotations=CONTROL)
    async def connect_broker(ctx: Context, broker_id: Annotated[Literal["angelone", "zerodha", "upstox", "dhan", "fyers", "delta"],
                             Field(description="Broker to connect")]) -> dict:
        """Start a broker session. TOTP/API-key brokers connect immediately; OAuth brokers (zerodha, upstox, fyers)
        return a loginUrl the HUMAN must open and complete in a browser. Needs 'control' scope."""
        return await backend.call(ctx, "control", "connect_broker", broker_id=broker_id)

    @mcp.tool(name="aitrader_get_quotes", annotations=READ)
    async def get_quotes(ctx: Context, symbols: Annotated[list[str] | None, Field(
            description="e.g. ['SBIN-EQ','BTCUSD']; omit for the whole watchlist", max_length=50)] = None) -> list:
        """Latest streamed prices for watchlist symbols, with timestamp and feed source."""
        return await backend.call(ctx, "read", "quotes", symbols=symbols)

    @mcp.tool(name="aitrader_search_instruments", annotations=READ)
    async def search(ctx: Context, query: Annotated[str, Field(min_length=2, max_length=30, description="e.g. 'RELIANCE'")],
                     exchange: Literal["NSE", "BSE", "NFO", "BFO", "DELTA"] = "NSE") -> list:
        """Find tradable instruments. NSE/BSE/NFO need Angel One connected; DELTA is public. Returns contracts
        you can pass to aitrader_watch."""
        return await backend.call(ctx, "read", "search", q=query, exchange=exchange)

    @mcp.tool(name="aitrader_watch", annotations=CONTROL)
    async def watch(ctx: Context, contract: Annotated[dict, Field(
            description="A contract object exactly as returned by aitrader_search_instruments")]) -> dict:
        """Add an instrument to the watchlist so it streams prices and can be traded. Needs 'control' scope."""
        return await backend.call(ctx, "control", "watch", contract=contract)

    @mcp.tool(name="aitrader_get_account", annotations=READ)
    async def get_account(ctx: Context, account: Account = "engine") -> dict:
        """Equity, today's P&L (₹ and %), open position count for an account."""
        return await backend.call(ctx, "read", "account", venue=account)

    @mcp.tool(name="aitrader_get_positions", annotations=READ)
    async def get_positions(ctx: Context, account: Account = "engine") -> list:
        """Open positions: symbol, qty (negative = short), average price, LTP, realised P&L, product."""
        return await backend.call(ctx, "read", "positions", venue=account)

    @mcp.tool(name="aitrader_get_orders", annotations=READ)
    async def get_orders(ctx: Context, account: Account = "engine", open_only: bool = False) -> list:
        """Today's orders, newest first. open_only=true returns only working orders (cancellable)."""
        return await backend.call(ctx, "read", "orders", venue=account, open_only=open_only)

    @mcp.tool(name="aitrader_get_risk", annotations=READ)
    async def get_risk(ctx: Context) -> dict:
        """Risk limits and usage: daily loss limit/used, drawdown, max position %, kill switch, active breaches."""
        return await backend.call(ctx, "read", "risk")

    @mcp.tool(name="aitrader_get_signals", annotations=READ)
    async def get_signals(ctx: Context, limit: Annotated[int, Field(ge=1, le=200)] = 20) -> list:
        """Recent auto-trader signals with confidence, regime, reason, expected edge after costs and whether each
        was TAKEN or why it was skipped/vetoed."""
        return await backend.call(ctx, "read", "signals", limit=limit)

    @mcp.tool(name="aitrader_place_order", annotations=TRADE)
    async def place_order(
        ctx: Context,
        symbol: Annotated[str, Field(description="Watchlist symbol, e.g. 'SBIN-EQ', 'BTCUSD'")],
        exchange: Literal["NSE", "BSE", "NFO", "BFO", "DELTA"],
        side: Literal["BUY", "SELL"],
        qty: Annotated[int, Field(gt=0, description="Shares/contracts; F&O must be a lot multiple")],
        reason: Annotated[str, Field(min_length=3, max_length=300,
                                     description="Why — shown to the human on the approval screen")],
        order_type: Literal["LIMIT", "MARKET", "STOPLOSS_LIMIT", "STOPLOSS_MARKET"] = "LIMIT",
        price: Annotated[float | None, Field(gt=0, description="Required for LIMIT / STOPLOSS_LIMIT")] = None,
        trigger_price: Annotated[float | None, Field(gt=0, description="Required for stop-loss orders")] = None,
        product: Literal["INTRADAY", "DELIVERY", "CARRYFORWARD"] = "INTRADAY",
    ) -> dict:
        """Place an order through the engine's full safety path (kill switch, risk limits, static IP).
        PAPER mode: executes (simulated) and returns the order. LIVE mode: returns PENDING_APPROVAL with an
        intentId — a human must approve it in the app with fingerprint/face; poll aitrader_get_order_request.
        Needs 'trade' scope."""
        ticket = {"symbol": symbol, "exchange": exchange, "side": side, "qty": qty, "orderType": order_type,
                  "price": price, "triggerPrice": trigger_price, "product": product}
        return await backend.call(ctx, "trade", "place_order", ticket=ticket, reason=reason)

    @mcp.tool(name="aitrader_get_order_request", annotations=READ)
    async def get_intent(ctx: Context, intent_id: Annotated[str, Field(pattern=r"^oi_[0-9a-f]{10}$")]) -> dict:
        """Status of a live-order approval request: PENDING / APPROVED (with orderId) / REJECTED / EXPIRED / FAILED."""
        return await backend.call(ctx, "read", "intent", intent_id=intent_id)

    @mcp.tool(name="aitrader_cancel_order", annotations=CONTROL)
    async def cancel_order(ctx: Context, order_id: Annotated[str, Field(min_length=1, max_length=40)],
                           account: Account = "engine") -> dict:
        """Cancel a working order (reduces risk, no approval needed). Needs 'control' scope."""
        return await backend.call(ctx, "control", "cancel", order_id=order_id, venue=account)

    @mcp.tool(name="aitrader_engage_kill_switch", annotations=CONTROL)
    async def kill(ctx: Context, reason: Annotated[str, Field(min_length=3, max_length=200)]) -> dict:
        """EMERGENCY STOP: block all new orders and cancel working entries (protective stops stay). Only the human
        can release it from the app. Needs 'control' scope."""
        return await backend.call(ctx, "control", "kill_switch", reason=reason)

    @mcp.tool(name="aitrader_autotrade_off", annotations=CONTROL)
    async def autotrade_off(ctx: Context) -> dict:
        """Stop automatic entries (open trades stay protected). Turning it back on is app-only. Needs 'control'."""
        return await backend.call(ctx, "control", "autotrade_off")

    return mcp


class ApiKeyMiddleware:
    """ASGI guard for the mounted HTTP MCP endpoint: verifies the API key server-side (scope 'read' minimum,
    per-tool scopes are checked again in LocalBackend) and attaches the verified key to request state."""

    def __init__(self, app, store):
        """`store` is an ApiKeyStore or a zero-arg callable returning one (resolved per request)."""
        self.app, self._store = app, store

    @property
    def store(self):
        return self._store() if callable(self._store) else self._store

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        headers = {k.decode().lower(): v.decode() for k, v in scope.get("headers", [])}
        raw = headers.get("x-api-key") or (headers.get("authorization", "")[7:]
                                          if headers.get("authorization", "").lower().startswith("bearer atk_") else None)
        try:
            key = self.store.verify(raw, "read")
        except PermissionError as e:
            import json
            body = json.dumps({"error": str(e)}).encode()
            await send({"type": "http.response.start", "status": 401,
                        "headers": [(b"content-type", b"application/json"), (b"www-authenticate", b"ApiKey")]})
            await send({"type": "http.response.body", "body": body})
            return
        scope.setdefault("state", {})["api_key"] = key
        return await self.app(scope, receive, send)


def main() -> None:
    import logging
    logging.getLogger("httpx").setLevel(logging.WARNING)   # stdio: keep stderr quiet, stdout is the protocol
    ap = argparse.ArgumentParser(description="AI Trader MCP server (stdio, proxies to your engine)")
    ap.add_argument("--engine", default=os.environ.get("AITRADER_ENGINE_URL", ""), help="https://your-engine")
    a = ap.parse_args()
    key = os.environ.get("AITRADER_API_KEY", "")
    if not a.engine.startswith("https://") and not a.engine.startswith("http://127.0.0.1"):
        sys.exit("--engine must be https://… (or http://127.0.0.1 for local testing)")
    if not key.startswith("atk_"):
        sys.exit("set AITRADER_API_KEY=atk_… (create one in the app: More → API & MCP)")
    build_mcp(RemoteBackend(a.engine, key)).run("stdio")


if __name__ == "__main__":
    main()
