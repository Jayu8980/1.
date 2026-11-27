"""Server-side store for broker session tokens (OAuth access tokens etc.).

File is created 0600 and lives on the VM's data volume. In production point
ENGINE_TOKEN_STORE_PATH at an encrypted volume or swap this for a secrets manager.
Tokens are never sent to the app.
"""
from __future__ import annotations

import json
import os
import threading
from dataclasses import asdict, dataclass
from datetime import datetime, time, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

IST = ZoneInfo("Asia/Kolkata")


@dataclass
class BrokerToken:
    access_token: str
    expires_at: str              # ISO datetime (IST)
    account: str = ""
    extra: dict | None = None

    def expired(self, now: datetime | None = None) -> bool:
        return (now or datetime.now(IST)) >= datetime.fromisoformat(self.expires_at)


def next_expiry(at: time, now: datetime | None = None) -> datetime:
    """Next occurrence of `at` IST — broker daily tokens die at a fixed time each morning."""
    now = now or datetime.now(IST)
    cand = datetime.combine(now.date(), at, tzinfo=IST)
    return cand if cand > now else cand + timedelta(days=1)


class TokenStore:
    def __init__(self, path: str | Path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.Lock()

    def _read(self) -> dict:
        if not self.path.exists():
            return {}
        return json.loads(self.path.read_text() or "{}")

    def _write(self, data: dict) -> None:
        tmp = self.path.with_suffix(".tmp")
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as f:
            json.dump(data, f)
        os.replace(tmp, self.path)

    def get(self, broker: str) -> BrokerToken | None:
        with self._lock:
            d = self._read().get(broker)
        return BrokerToken(**d) if d else None

    def put(self, broker: str, tok: BrokerToken) -> None:
        with self._lock:
            data = self._read(); data[broker] = asdict(tok); self._write(data)

    def delete(self, broker: str) -> None:
        with self._lock:
            data = self._read(); data.pop(broker, None); self._write(data)
