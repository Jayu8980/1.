"""Live-order approval requests.

An API key or MCP client can REQUEST a live order; only the phone, with a biometric-signed
approval, can send it to the broker. Requests expire after `ttl_s` (default 5 min) and are
re-checked at approval time (engine must still be LIVE; full gateway path applies).
"""
from __future__ import annotations

import secrets
import time
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone


@dataclass
class OrderIntent:
    id: str
    ticket: dict              # OrderTicket fields
    requested_by: str         # "key:<id> (<name>)"
    reason: str
    created: str
    expires_at: float
    status: str = "PENDING"   # PENDING / APPROVED / REJECTED / EXPIRED / FAILED
    result: str = ""
    order_id: str | None = None

    def view(self) -> dict:
        d = asdict(self)
        d["expiresInSec"] = max(0, int(self.expires_at - time.time()))
        return d


class IntentStore:
    def __init__(self, ttl_s: int = 300, keep: int = 200):
        self.ttl_s, self.keep = ttl_s, keep
        self.items: dict[str, OrderIntent] = {}

    def _expire(self) -> None:
        now = time.time()
        for i in self.items.values():
            if i.status == "PENDING" and now >= i.expires_at:
                i.status, i.result = "EXPIRED", "not approved in time"
        if len(self.items) > self.keep:
            for k in sorted(self.items, key=lambda k: self.items[k].created)[: len(self.items) - self.keep]:
                self.items.pop(k)

    def create(self, ticket: dict, requested_by: str, reason: str) -> OrderIntent:
        self._expire()
        i = OrderIntent("oi_" + secrets.token_hex(5), ticket, requested_by, reason[:300],
                        datetime.now(timezone.utc).isoformat(), time.time() + self.ttl_s)
        self.items[i.id] = i
        return i

    def get(self, iid: str) -> OrderIntent | None:
        self._expire()
        return self.items.get(iid)

    def pending(self) -> list[OrderIntent]:
        self._expire()
        return sorted((i for i in self.items.values() if i.status == "PENDING"), key=lambda i: i.created)

    def recent(self, n: int = 50) -> list[OrderIntent]:
        self._expire()
        return sorted(self.items.values(), key=lambda i: i.created, reverse=True)[:n]
