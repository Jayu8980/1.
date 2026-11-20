"""Delta Exchange India REST client (public + HMAC-signed private endpoints).

Production: https://api.india.delta.exchange   Testnet: https://cdn-ind.testnet.deltaex.org
Never api.delta.exchange (Delta Global rejects India keys).
"""
from __future__ import annotations

import hashlib
import hmac
import json
import time
from email.utils import parsedate_to_datetime

import httpx
import pandas as pd

from ..config import CONFIG

RES = {"1m": "1m", "5m": "5m", "15m": "15m", "1h": "1h", "1D": "1d", "1W": "1w"}
SECS = {"1m": 60, "5m": 300, "15m": 900, "1h": 3600, "1D": 86400, "1W": 604800}


def sign(secret: str, payload: str) -> str:
    return hmac.new(secret.encode(), payload.encode(), hashlib.sha256).hexdigest()


class DeltaClient:
    def __init__(self, testnet: bool | None = None, key: str | None = None, secret: str | None = None):
        self.testnet = CONFIG.delta_testnet if testnet is None else testnet
        self.base = "https://cdn-ind.testnet.deltaex.org" if self.testnet else "https://api.india.delta.exchange"
        self.key = key if key is not None else CONFIG.delta_key
        self.secret = secret if secret is not None else CONFIG.delta_secret
        self.skew = 0
        self.http = httpx.Client(timeout=20, headers={"User-Agent": "tradelens-engine/1.0", "Accept": "application/json"})

    # ---- public ----
    def get(self, path: str, params: dict | None = None) -> dict:
        r = self.http.get(self.base + path, params=params)
        r.raise_for_status()
        return r.json()

    def products(self) -> list[dict]:
        out, after = [], None
        while True:
            p = {"page_size": 500, "states": "live"}
            if after:
                p["after"] = after
            j = self.get("/v2/products", p)
            out += j.get("result", [])
            after = (j.get("meta") or {}).get("after")
            if not after:
                return out

    def tickers(self, contract_types: str = "perpetual_futures") -> list[dict]:
        return self.get("/v2/tickers", {"contract_types": contract_types}).get("result", [])

    def candles(self, symbol: str, tf: str = "1D", start: int | None = None, end: int | None = None) -> pd.DataFrame:
        end = end or int(time.time())
        start = start or end - 5 * 365 * 86400
        rows, cur = {}, end
        while cur > start:
            s = max(start, cur - SECS[tf] * 2000)
            res = self.get("/v2/history/candles", {"symbol": symbol, "resolution": RES[tf], "start": s, "end": cur}).get("result", [])
            for c in res:
                rows[c["time"]] = c
            if not res and cur != end:
                break
            cur = s
        if not rows:
            return pd.DataFrame(columns=["open", "high", "low", "close", "volume"])
        df = pd.DataFrame(rows.values()).set_index("time").sort_index()
        df.index = pd.to_datetime(df.index, unit="s", utc=True)
        return df[["open", "high", "low", "close", "volume"]].astype(float)

    def funding_history(self, symbol: str, days: int = 365) -> pd.Series:
        df = self.candles(f"FUNDING:{symbol}", "1h", int(time.time()) - days * 86400)
        return df["close"] / 100.0  # % per 8h → fraction

    # ---- private (signed) ----
    def _sync_clock(self) -> None:
        r = self.http.head(self.base + "/v2/products", params={"page_size": 1})
        if "date" in r.headers:
            self.skew = int(parsedate_to_datetime(r.headers["date"]).timestamp() - time.time())

    def signed(self, method: str, path: str, params: dict | None = None, body: dict | None = None, _retry: bool = True) -> dict:
        if not (self.key and self.secret):
            raise RuntimeError("Delta API key/secret not configured")
        ts = str(int(time.time()) + self.skew)
        qs = ("?" + "&".join(f"{k}={v}" for k, v in params.items())) if params else ""
        payload = json.dumps(body, separators=(",", ":")) if body else ""
        headers = {"api-key": self.key, "timestamp": ts, "signature": sign(self.secret, method + ts + path + qs + payload), "Content-Type": "application/json"}
        r = self.http.request(method, self.base + path + qs, content=payload or None, headers=headers)
        if r.status_code == 401 and "expired" in r.text.lower() and _retry:
            self._sync_clock()
            return self.signed(method, path, params, body, False)
        r.raise_for_status()
        return r.json()

    def balances(self) -> list[dict]:
        return self.signed("GET", "/v2/wallet/balances").get("result", [])

    def positions(self) -> list[dict]:
        return self.signed("GET", "/v2/positions/margined").get("result", [])

    def place_order(self, product_id: int, size: int, side: str, order_type: str = "limit_order", limit_price: float | None = None,
                    stop_price: float | None = None, client_order_id: str = "") -> dict:
        if not self.testnet and not CONFIG.delta_production_unlocked:
            raise PermissionError("Delta production orders are locked (set DELTA_PRODUCTION_UNLOCKED=true deliberately)")
        body = {"product_id": product_id, "size": int(size), "side": side, "order_type": order_type, "client_order_id": client_order_id[:32]}
        if limit_price is not None:
            body["limit_price"] = str(limit_price)
        if stop_price is not None:
            body["stop_order_type"] = "stop_loss_order"; body["stop_price"] = str(stop_price)
        return self.signed("POST", "/v2/orders", body=body).get("result", {})
