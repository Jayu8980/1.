import os
import sys
import tempfile
import time

os.environ["RELAY_TOKEN"] = "relay-test-token-0123456789abcdef"
os.environ["RELAY_STATE_DIR"] = tempfile.mkdtemp()
os.environ["ALGO_ID_KITE"] = "ALGO123"
sys.path.insert(0, os.path.dirname(os.path.dirname(__file__)))

import httpx  # noqa: E402
from fastapi.testclient import TestClient  # noqa: E402

import relay  # noqa: E402

H = {"Authorization": "Bearer relay-test-token-0123456789abcdef", "X-Broker-Session": "sess"}
c = TestClient(relay.app)


def order(**kw):
    o = {"broker": "kite", "exchange": "NSE", "symbol": "INFY", "side": "BUY", "qty": 10, "price": 1500.0, "product": "DELIVERY",
         "clientRef": f"ref{time.time_ns()}", "approvedAt": int(time.time() * 1000)}
    o.update(kw)
    return o


def test_auth():
    assert c.post("/orders", json=order()).status_code == 401
    assert c.post("/orders", json=order(), headers={"Authorization": "Bearer nope"}).status_code == 401


def test_rejects_non_limit_and_oversize_and_stale(monkeypatch):
    assert c.post("/orders", json=order(orderType="MARKET"), headers=H).status_code == 400
    assert c.post("/orders", json=order(qty=10_000), headers=H).status_code == 400
    assert c.post("/orders", json=order(approvedAt=int(time.time() * 1000) - 600_000), headers=H).status_code == 400


def test_forwards_tagged_limit_order_and_blocks_replay(monkeypatch):
    sent = []

    def fake_send(req):
        sent.append(req)
        return httpx.Response(200, json={"status": "success", "data": {"order_id": "2511"}}, request=req)
    monkeypatch.setattr(relay.SEND, "send", fake_send)
    o = order()
    r = c.post("/orders", json=o, headers=H).json()
    assert r["status"] == "accepted" and r["brokerOrderId"] == "2511"
    body = sent[0].content.decode()
    assert "order_type=LIMIT" in body and "tag=ALGO123" in body and "product=CNC" in body
    assert c.post("/orders", json=o, headers=H).status_code == 409


def test_kill_file_blocks_orders():
    assert c.post("/kill", json={"enabled": True}, headers=H).json()["kill"]
    assert c.post("/orders", json=order(), headers=H).status_code == 423
    c.post("/kill", json={"enabled": False}, headers=H)


def test_all_brokers_build_limit_requests():
    for b in ("kite", "upstox", "angel", "fyers", "dhan"):
        req = relay.build_request(relay.OrderIn(**order(broker=b, token="123")), "sess")
        assert req.method == "POST" and "LIMIT" in req.content.decode() or '"type":1' in req.content.decode().replace(" ", "")
