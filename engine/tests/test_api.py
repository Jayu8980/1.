from fastapi.testclient import TestClient

from tradelens.api import app

app.state.skip_loop = True
H = {"Authorization": "Bearer test-token-0123456789abcdefghij"}


def test_auth_required_and_status():
    with TestClient(app) as c:
        assert c.get("/health").status_code == 200
        assert c.get("/api/status").status_code == 401
        assert c.get("/api/status", headers={"Authorization": "Bearer wrong"}).status_code == 401
        r = c.get("/api/status", headers=H)
        assert r.status_code == 200 and r.json()["mode"] == "paper"


def test_strategy_cannot_be_enabled_before_validation():
    with TestClient(app) as c:
        names = [s["name"] for s in c.get("/api/strategies", headers=H).json()]
        assert "xs_momentum" in names
        r = c.post("/api/strategies/xs_momentum/enabled", headers=H, json={"enabled": True}).json()
        assert r["ok"] is False and "validation" in r["message"]
        assert c.post("/api/strategies/xs_momentum/kill", headers=H, json={"enabled": True}).json()["ok"]


def test_kill_switch_and_audit():
    with TestClient(app) as c:
        assert c.post("/api/kill", headers=H, json={"enabled": True}).json()["ok"]
        assert c.get("/api/status", headers=H).json()["globalKill"] is True
        assert any(a["category"] == "KILL" for a in c.get("/api/audit", headers=H).json())
        c.post("/api/kill", headers=H, json={"enabled": False})


def test_oauth_bounce():
    with TestClient(app) as c:
        r = c.get("/auth/callback/kite?request_token=abc&status=success", follow_redirects=False)
        assert r.status_code == 302 and r.headers["location"].startswith("tradelens://auth/kite?request_token=abc")
        assert c.get("/auth/callback/evil", follow_redirects=False).status_code == 404


def test_websocket_auth():
    with TestClient(app) as c:
        with c.websocket_connect("/api/ws", headers=H) as ws:
            assert ws.receive_json()["type"] == "status"
