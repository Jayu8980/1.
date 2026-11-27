"""Append-only, hash-chained audit log (JSONL).

Every record stores the SHA-256 of the previous record, so any edit, deletion
or reordering is detectable with `verify()`. On the VM, the file is also set
`chattr +a` (see infra/) so the OS refuses non-append writes.
"""
from __future__ import annotations

import asyncio
import hashlib
import json
import os
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

GENESIS = "0" * 64


def _digest(prev: str, body: dict[str, Any]) -> str:
    payload = json.dumps(body, sort_keys=True, separators=(",", ":"), default=str)
    return hashlib.sha256((prev + payload).encode()).hexdigest()


class AuditLog:
    def __init__(self, path: str | Path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = asyncio.Lock()
        self._last_hash = self._load_last_hash()

    def _load_last_hash(self) -> str:
        if not self.path.exists() or self.path.stat().st_size == 0:
            return GENESIS
        with self.path.open("rb") as f:
            f.seek(0, os.SEEK_END)
            pos = f.tell() - 1
            while pos > 0:
                f.seek(pos - 1)
                if f.read(1) == b"\n":
                    break
                pos -= 1
            f.seek(max(pos, 0))
            return json.loads(f.readline())["hash"]

    async def record(self, event: str, **data: Any) -> dict[str, Any]:
        async with self._lock:
            body = {"ts": datetime.now(timezone.utc).isoformat(), "event": event, "data": data}
            h = _digest(self._last_hash, body)
            rec = {**body, "prev": self._last_hash, "hash": h}
            line = json.dumps(rec, sort_keys=True, separators=(",", ":"), default=str) + "\n"
            # O_APPEND write + fsync: durable before the caller proceeds.
            fd = os.open(self.path, os.O_WRONLY | os.O_APPEND | os.O_CREAT, 0o640)
            try:
                os.write(fd, line.encode())
                os.fsync(fd)
            finally:
                os.close(fd)
            self._last_hash = h
            return rec

    def tail(self, n: int = 100) -> list[dict[str, Any]]:
        if not self.path.exists():
            return []
        with self.path.open() as f:
            lines = f.readlines()[-n:]
        return [json.loads(x) for x in lines]

    def verify(self) -> tuple[bool, int | None]:
        """Return (ok, first_bad_line_number)."""
        prev = GENESIS
        if not self.path.exists():
            return True, None
        with self.path.open() as f:
            for i, line in enumerate(f, 1):
                rec = json.loads(line)
                body = {k: rec[k] for k in ("ts", "event", "data")}
                if rec["prev"] != prev or rec["hash"] != _digest(prev, body):
                    return False, i
                prev = rec["hash"]
        return True, None
