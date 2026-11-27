"""Scoped API keys for scripts, other apps and MCP clients.

Keys are shown once at creation and stored only as SHA-256 hashes (file 0600).
Scopes:
  read     brokers, quotes, instruments, positions, orders, funds, risk, signals
  trade    place orders: PAPER executes; LIVE becomes an approval request on the phone
  control  cancel orders, engage the kill switch, start broker connections, auto-trade OFF
Nothing an API key can do turns LIVE on, turns auto-trade on, changes risk limits,
promotes strategies or approves a live order: those stay biometric-only in the app.
"""
from __future__ import annotations

import hashlib
import json
import os
import secrets
import time
from collections import deque
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from pathlib import Path

SCOPES = ("read", "trade", "control")


@dataclass
class ApiKey:
    id: str
    name: str
    scopes: list[str]
    hash: str
    created: str
    last_used: str | None = None
    revoked: bool = False

    def view(self) -> dict:
        d = asdict(self); d.pop("hash"); return d


def _h(raw: str) -> str:
    return hashlib.sha256(raw.encode()).hexdigest()


class ApiKeyStore:
    def __init__(self, path: str | Path, rate_per_min: int = 120):
        self.path = Path(path); self.path.parent.mkdir(parents=True, exist_ok=True)
        self.keys: dict[str, ApiKey] = {}
        if self.path.exists():
            for d in json.loads(self.path.read_text()):
                k = ApiKey(**d); self.keys[k.id] = k
        self.rate_per_min = rate_per_min
        self._hits: dict[str, deque] = {}

    def _save(self) -> None:
        fd = os.open(self.path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as f:
            json.dump([asdict(k) for k in self.keys.values()], f)

    def create(self, name: str, scopes: list[str]) -> tuple[ApiKey, str]:
        bad = [s for s in scopes if s not in SCOPES]
        if bad or not scopes:
            raise ValueError(f"scopes must be a non-empty subset of {SCOPES}")
        raw = "atk_" + secrets.token_urlsafe(32)
        k = ApiKey(secrets.token_hex(4), name.strip()[:40] or "key", sorted(set(scopes)), _h(raw),
                   datetime.now(timezone.utc).isoformat())
        self.keys[k.id] = k; self._save()
        return k, raw

    def revoke(self, key_id: str) -> bool:
        k = self.keys.get(key_id)
        if not k:
            return False
        k.revoked = True; self._save()
        return True

    def verify(self, raw: str | None, scope: str) -> ApiKey:
        """Returns the key, or raises PermissionError with a message safe to show the caller."""
        if not raw:
            raise PermissionError("missing API key (header X-API-Key or Authorization: Bearer atk_...)")
        h = _h(raw)
        k = next((x for x in self.keys.values() if secrets.compare_digest(x.hash, h)), None)
        if k is None or k.revoked:
            raise PermissionError("invalid or revoked API key")
        if scope not in k.scopes:
            raise PermissionError(f"this API key lacks the '{scope}' scope (has: {', '.join(k.scopes)})")
        q = self._hits.setdefault(k.id, deque())
        now = time.monotonic()
        while q and now - q[0] > 60:
            q.popleft()
        if len(q) >= self.rate_per_min:
            raise PermissionError(f"rate limit: {self.rate_per_min} requests/minute per key")
        q.append(now)
        k.last_used = datetime.now(timezone.utc).isoformat()
        return k

    def list(self) -> list[dict]:
        return [k.view() for k in self.keys.values()]
