import base64
import time
from urllib.parse import parse_qs, urlparse

import httpx
import pytest
import respx
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.api.routes import router
from app.api.security import hash_password, signing_payload
from app.config import Settings
from app.core.enums import Mode
from app.main import build_state

PW = "correct horse"
PHRASE = "I UNDERSTAND THIS USES REAL MONEY"


class Phone:
    """Stands in for the Android Keystore key."""

    def __init__(self):
        self.key = ec.generate_private_key(ec.SECP256R1())

    @property
    def pub(self) -> str:
        return base64.b64encode(self.key.public_key().public_bytes(
            serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)).decode()

    def sign(self, method, path, body=b"", ts=None):
        ts = ts or int(time.time() * 1000)
        sig = self.key.sign(signing_payload(method, path, ts, body), ec.ECDSA(hashes.SHA256()))
        return {"X-Action-Ts": str(ts), "X-Action-Signature": base64.b64encode(sig).decode()}


@pytest.fixture
def env(tmp_path):
    s = Settings(_env_file=None, audit_log_path=str(tmp_path / "a.jsonl"), token_store_path=str(tmp_path / "t.json"),
                 device_keys_path=str(tmp_path / "d.json"), jwt_secret="t" * 40, app_user="me",
                 app_password_hash=hash_password(PW), public_base_url="https://vm.example",
                 zerodha_api_key="kkey", zerodha_api_secret="ksec", dhan_client_id="1000001",
                 dhan_access_token="DTOK", live_broker="angelone", static_ip="1.2.3.4", allow_live=True)
    app = FastAPI(); app.include_router(router)

    async def ip():
        return "1.2.3.4"
    build_state(app, s, ip_fetcher=ip)
    phone = Phone()
    c = TestClient(app)
    r = c.post("/api/v1/auth/login", json={"username": "me", "password": PW, "devicePublicKey": phone.pub})
    assert r.status_code == 200
    c.headers["Authorization"] = f"Bearer {r.json()['token']}"
    return c, phone, app


def test_login_rejects_bad_password(env):
    c, phone, _ = env
    r = c.post("/api/v1/auth/login", json={"username": "me", "password": "nope", "devicePublicKey": phone.pub})
    assert r.status_code == 401


def test_list_requires_jwt_and_shows_all_brokers(env):
    c, _, app = env
    assert TestClient(app).get("/api/v1/brokers").status_code == 401
    rows = {b["id"]: b for b in c.get("/api/v1/brokers").json()}
    assert set(rows) == {"angelone", "zerodha", "upstox", "dhan", "fyers"}
    assert rows["zerodha"]["configured"] and rows["zerodha"]["auth"] == "OAUTH"
    assert not rows["upstox"]["configured"] and "ENGINE_UPSTOX_CLIENT_ID" in rows["upstox"]["requiredSettings"]
    assert rows["angelone"]["active"]


def test_connect_requires_valid_fresh_unreplayed_signature(env):
    c, phone, _ = env
    p = "/api/v1/brokers/zerodha/connect"
    assert c.post(p).status_code == 403
    assert c.post(p, headers=phone.sign("POST", p, ts=int(time.time() * 1000) - 120_000)).status_code == 403
    assert c.post(p, headers=Phone().sign("POST", p)).status_code == 403          # someone else's key
    h = phone.sign("POST", p)
    assert c.post(p, headers=h).status_code == 200
    assert c.post(p, headers=h).status_code == 403                                 # replay


def test_unconfigured_broker_connect_explains_settings(env):
    c, phone, _ = env
    p = "/api/v1/brokers/upstox/connect"
    r = c.post(p, headers=phone.sign("POST", p))
    assert r.status_code == 400 and "ENGINE_UPSTOX_CLIENT_ID" in r.json()["detail"]


def test_zerodha_oauth_round_trip_and_activate(env):
    c, phone, app = env
    p = "/api/v1/brokers/zerodha/connect"
    url = c.post(p, headers=phone.sign("POST", p)).json()["loginUrl"]
    state = parse_qs(parse_qs(urlparse(url).query)["redirect_params"][0])["state"][0]

    # forged / wrong state is refused and nothing is stored
    bad = c.get("/api/v1/brokers/zerodha/callback", params={"request_token": "RT", "status": "success", "state": "x"})
    assert bad.status_code == 400

    with respx.mock(base_url="https://api.kite.trade", assert_all_called=False) as m:
        m.post("/session/token").mock(return_value=httpx.Response(200, json={
            "status": "success", "data": {"access_token": "AT", "user_id": "AB1234"}}))
        ok = c.get("/api/v1/brokers/zerodha/callback", params={"request_token": "RT", "status": "success", "state": state})
        assert ok.status_code == 200 and "aitrader://brokers" in ok.text
        # state is single-use
        again = c.get("/api/v1/brokers/zerodha/callback", params={"request_token": "RT", "status": "success", "state": state})
        assert again.status_code == 400

    rows = {b["id"]: b for b in c.get("/api/v1/brokers").json()}
    assert rows["zerodha"]["connected"] and rows["zerodha"]["account"] == "AB1234"

    a = "/api/v1/brokers/zerodha/activate"
    assert c.post(a, headers=phone.sign("POST", a)).status_code == 200
    assert app.state.gateway.live.name == "zerodha" and app.state.brokers.active_id == "zerodha"


def test_cannot_activate_unconnected_or_switch_while_live(env):
    c, phone, app = env
    a = "/api/v1/brokers/fyers/activate"
    r = c.post(a, headers=phone.sign("POST", a))
    assert r.status_code == 409 and "not connected" in r.json()["detail"]

    with respx.mock(base_url="https://api.dhan.co/v2") as m:
        m.get("/profile").mock(return_value=httpx.Response(200, json={"dhanClientId": "1000001"}))
        p = "/api/v1/brokers/dhan/connect"
        assert c.post(p, headers=phone.sign("POST", p)).json()["ok"]
    a = "/api/v1/brokers/dhan/activate"
    assert c.post(a, headers=phone.sign("POST", a)).status_code == 200

    import asyncio
    gw = app.state.gateway
    asyncio.run(gw.set_mode(Mode.LIVE, confirm_phrase=PHRASE))
    a2 = "/api/v1/brokers/zerodha/activate"
    r = c.post(a2, headers=phone.sign("POST", a2))
    assert r.status_code == 409 and "PAPER" in r.json()["detail"]
    assert c.post("/api/v1/brokers/dhan/disconnect").status_code == 409


def test_live_refused_when_active_broker_not_connected(env):
    _, _, app = env
    import asyncio
    from app.core.errors import OrderBlocked
    with pytest.raises(OrderBlocked, match="not connected"):
        asyncio.run(app.state.gateway.set_mode(Mode.LIVE, confirm_phrase=PHRASE))
