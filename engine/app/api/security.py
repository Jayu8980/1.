"""JWT sessions + verification of biometric-signed money actions from the phone.

The phone signs  METHOD \\n PATH \\n TS \\n sha256hex(body)  with an EC P-256 Keystore key that
needs a strong biometric per use. We verify with the public key registered at login, reject
stale timestamps (> 60 s) and replays.
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import secrets
import time
from collections import OrderedDict
from pathlib import Path

import jwt
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from fastapi import Depends, HTTPException, Request
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

MAX_SKEW_MS = 60_000


# ---------------------------------------------------------------- passwords
def hash_password(pw: str) -> str:
    salt = secrets.token_bytes(16)
    h = hashlib.scrypt(pw.encode(), salt=salt, n=2**14, r=8, p=1)
    return f"scrypt${base64.b64encode(salt).decode()}${base64.b64encode(h).decode()}"


def check_password(pw: str, stored: str) -> bool:
    try:
        _, salt, h = stored.split("$")
        got = hashlib.scrypt(pw.encode(), salt=base64.b64decode(salt), n=2**14, r=8, p=1)
        return hmac.compare_digest(got, base64.b64decode(h))
    except Exception:
        return False


# ---------------------------------------------------------------- device keys
class DeviceKeys:
    """username -> base64 X.509 SubjectPublicKeyInfo (from the phone's Keystore)."""

    def __init__(self, path: str | Path):
        self.path = Path(path); self.path.parent.mkdir(parents=True, exist_ok=True)

    def _read(self) -> dict:
        return json.loads(self.path.read_text()) if self.path.exists() else {}

    def put(self, user: str, pub_b64: str) -> None:
        serialization.load_der_public_key(base64.b64decode(pub_b64))   # validate
        d = self._read(); d[user] = pub_b64
        fd = os.open(self.path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as f:
            json.dump(d, f)

    def get(self, user: str) -> ec.EllipticCurvePublicKey | None:
        b = self._read().get(user)
        return serialization.load_der_public_key(base64.b64decode(b)) if b else None


def signing_payload(method: str, path: str, ts: int, body: bytes) -> bytes:
    return f"{method}\n{path}\n{ts}\n{hashlib.sha256(body).hexdigest()}".encode()


class Security:
    def __init__(self, jwt_secret: str, ttl_min: int, devices: DeviceKeys):
        if not jwt_secret or jwt_secret == "change-me" or len(jwt_secret) < 32:
            # Fail safe: random secret per process (sessions die on restart) rather than a guessable one.
            jwt_secret = secrets.token_urlsafe(32)
        self._secret, self.ttl_min, self.devices = jwt_secret, ttl_min, devices
        self._seen: OrderedDict[str, float] = OrderedDict()

    def issue(self, user: str) -> str:
        now = int(time.time())
        return jwt.encode({"sub": user, "iat": now, "exp": now + self.ttl_min * 60}, self._secret, algorithm="HS256")

    def decode(self, token: str) -> str:
        try:
            return jwt.decode(token, self._secret, algorithms=["HS256"])["sub"]
        except jwt.PyJWTError:
            raise HTTPException(401, "invalid or expired session") from None

    def verify_action(self, user: str, method: str, path: str, ts: str | None, sig: str | None, body: bytes) -> None:
        if not ts or not sig:
            raise HTTPException(403, "biometric signature required")
        try:
            ts_i = int(ts)
        except ValueError:
            raise HTTPException(403, "bad timestamp") from None
        if abs(time.time() * 1000 - ts_i) > MAX_SKEW_MS:
            raise HTTPException(403, "signature expired")
        if sig in self._seen:
            raise HTTPException(403, "replayed signature")
        key = self.devices.get(user)
        if key is None:
            raise HTTPException(403, "no device key registered — log in again from the phone")
        try:
            key.verify(base64.b64decode(sig), signing_payload(method, path, ts_i, body), ec.ECDSA(hashes.SHA256()))
        except (InvalidSignature, ValueError):
            raise HTTPException(403, "bad signature") from None
        self._seen[sig] = time.time()
        while len(self._seen) > 10_000:
            self._seen.popitem(last=False)


bearer = HTTPBearer(auto_error=False)


def current_user(request: Request, cred: HTTPAuthorizationCredentials | None = Depends(bearer)) -> str:
    if cred is None:
        raise HTTPException(401, "missing bearer token")
    return request.app.state.security.decode(cred.credentials)


async def signed_user(request: Request, user: str = Depends(current_user)) -> str:
    body = await request.body()
    request.app.state.security.verify_action(user, request.method, request.url.path,
                                             request.headers.get("X-Action-Ts"),
                                             request.headers.get("X-Action-Signature"), body)
    return user


if __name__ == "__main__":   # python -m app.api.security  -> prints a password hash for .env
    import getpass
    print(hash_password(getpass.getpass("Engine app password: ")))
