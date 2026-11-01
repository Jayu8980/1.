"""The desk server the phone app talks to."""
import json
import threading
import time
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer

import pytest

from trader.server import DeskServer, make_handler

TOKEN = "test-token-123456"


@pytest.fixture()
def api(tmp_path):
    cfg = tmp_path / "config.toml"
    cfg.write_text(f"""
[universe]
symbols = ["SYN001", "SYN002", "SYN003", "SYN004"]
[data]
source = "synthetic"
cache_dir = "{tmp_path / 'cache'}"
[llm]
enabled = "off"
[execution]
broker = "paper"
state_dir = "{tmp_path / 'state'}"
reports_dir = "{tmp_path / 'reports'}"
""")
    desk = DeskServer(["-c", str(cfg)], live=False, token=TOKEN)
    httpd = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(desk))
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    base = f"http://127.0.0.1:{httpd.server_address[1]}"

    def call(method, path, body=None, token=TOKEN):
        req = urllib.request.Request(base + path, method=method, data=json.dumps(body).encode() if body is not None else None)
        if token:
            req.add_header("Authorization", f"Bearer {token}")
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                return r.status, json.loads(r.read())
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read())

    call.desk = desk
    yield call
    httpd.shutdown()


def wait(call, job_id):
    for _ in range(600):
        code, view = call("GET", f"/api/jobs/{job_id}")
        assert code == 200
        if view["status"] != "running":
            return view
        time.sleep(0.2)
    raise AssertionError("job did not finish")


def test_health_needs_no_token_but_everything_else_does(api):
    assert api("GET", "/api/health", token=None)[0] == 200
    assert api("GET", "/api/info", token=None)[0] == 401
    assert api("GET", "/api/info", token="wrong-token-000000")[0] == 401
    code, info = api("GET", "/api/info")
    assert code == 200 and info["broker"] == "paper" and info["live"] is False


def test_scan_job_reports_progress_and_result(api):
    code, started = api("POST", "/api/scan", {"synthetic": True})
    assert code == 202
    view = wait(api, started["job"])
    assert view["status"] == "done", view["error"]
    assert view["lines"], "progress lines are streamed"
    result = view["result"]
    assert len(result["funnel"]) == 12 and result["mode"] == "paper"
    later = api("GET", f"/api/jobs/{started['job']}?since={view['next']}")[1]
    assert later["lines"] == []


def test_trade_then_status_and_monitor(api):
    view = wait(api, api("POST", "/api/trade", {"synthetic": True})[1]["job"])
    assert view["status"] == "done", view["error"]
    code, status = api("GET", "/api/status")
    assert code == 200 and status["broker"] == "paper" and "equity" in status["account"]
    view = wait(api, api("POST", "/api/monitor", {})[1]["job"])
    assert view["status"] == "done" and "closed" in view["result"]


def test_bad_requests_are_rejected(api):
    assert api("POST", "/api/scan", {"preset": "no_such_preset"})[0] == 400
    assert api("POST", "/api/scan", {"segment": "margin"})[0] == 400
    assert api("POST", "/api/scan", {"symbols": ["OK", "bad symbol; rm -rf"]})[0] == 400
    assert api("POST", "/api/nope", {})[0] == 404


def test_one_job_at_a_time(api):
    api.desk.lock.acquire()
    try:
        assert api("POST", "/api/scan", {})[0] == 409
        assert api("GET", "/api/status")[0] == 409
    finally:
        api.desk.lock.release()


def test_live_server_requires_explicit_confirmation(api):
    api.desk.live = True
    code, body = api("POST", "/api/trade", {"synthetic": True})
    assert code == 400 and "LIVE" in body["error"]
