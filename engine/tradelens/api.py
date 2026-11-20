"""Secure REST + WebSocket API consumed by the TradeLens app. Bearer-token auth on everything except /health
and the OAuth bounce (/auth/callback/{broker}), which forwards broker redirects to tradelens://auth/{broker}."""
from __future__ import annotations

import asyncio
import hmac
import time
from contextlib import asynccontextmanager
from pathlib import Path
from urllib.parse import urlencode

import numpy as np
from fastapi import BackgroundTasks, Depends, FastAPI, Header, HTTPException, Query, Request, WebSocket, WebSocketDisconnect
from fastapi.responses import FileResponse, RedirectResponse, Response

from . import reports
from .config import CONFIG
from .data import nse, yahoo
from .service import EngineService

SERVICE: EngineService | None = None
BRIEFS: list[dict] = []
BROKERS = {"kite", "upstox", "angel", "fyers", "dhan"}


def svc() -> EngineService:
    global SERVICE
    if SERVICE is None:
        SERVICE = EngineService()
    return SERVICE


def auth(authorization: str = Header(default="")) -> None:
    token = authorization.removeprefix("Bearer ").strip()
    if not CONFIG.api_token or not hmac.compare_digest(token, CONFIG.api_token):
        raise HTTPException(401, "invalid token")


@asynccontextmanager
async def lifespan(app: FastAPI):
    if not CONFIG.api_token or len(CONFIG.api_token) < 24:
        raise RuntimeError("Set TRADELENS_API_TOKEN (≥ 24 random characters) before starting the API")
    s = svc()
    if not getattr(app.state, "skip_loop", False):
        asyncio.get_running_loop().run_in_executor(None, s.load)
        s.start()
        asyncio.get_running_loop().create_task(brief_loop())
    yield


app = FastAPI(title="TradeLens Alpha Engine", version="1.0.0", lifespan=lifespan)
A = [Depends(auth)]


@app.get("/health")
def health():
    return {"ok": True, "time": int(time.time())}


@app.get("/auth/callback/{broker}")
def oauth_bounce(broker: str, request: Request):
    """Brokers require an http(s) redirect URL; this forwards the query (code / request_token / auth_code) to the app."""
    if broker not in BROKERS:
        raise HTTPException(404)
    return RedirectResponse(f"tradelens://auth/{broker}?{urlencode(dict(request.query_params))}", status_code=302)


@app.get("/api/status", dependencies=A)
def status():
    return svc().status()


@app.get("/api/signals", dependencies=A)
def signals(status: str | None = None):
    out = [s.__dict__ for s in svc().signals.values() if status is None or s.status == status]
    return sorted(out, key=lambda s: -s["createdAt"])[:200]


@app.post("/api/signals/{sid}/approve", dependencies=A)
def approve(sid: str):
    return svc().approve(sid)


@app.post("/api/signals/{sid}/reject", dependencies=A)
def reject(sid: str):
    return svc().reject(sid)


@app.get("/api/strategies", dependencies=A)
def strategies():
    return svc().strategies()


def _toggle(name: str, field: str, body: dict):
    s = svc()
    if name not in s.pipeline.records:
        raise HTTPException(404, "unknown strategy")
    r = s.pipeline.get(name)
    on = bool(body.get("enabled"))
    if field == "enabled" and on and r.stage in ("RESEARCH", "REJECTED", "DEMOTED"):
        return {"ok": False, "message": f"{name} is {r.stage}: it must pass walk-forward validation first (python -m tradelens validate {name})"}
    setattr(r, field, on)
    s.pipeline.save()
    s.audit.log("APPROVAL", f"{field} {'ON' if on else 'OFF'} for {name}")
    if field in ("enabled", "killed"):
        if field == "killed":
            (s.risk.state.killed.add if on else s.risk.state.killed.discard)(name); s.risk.save()
        if s.engine:
            s._rebuild()
    return {"ok": True, "message": f"{field}={on}"}


@app.post("/api/strategies/{name}/enabled", dependencies=A)
def set_enabled(name: str, body: dict):
    return _toggle(name, "enabled", body)


@app.post("/api/strategies/{name}/auto", dependencies=A)
def set_auto(name: str, body: dict):
    return _toggle(name, "auto", body)


@app.post("/api/strategies/{name}/kill", dependencies=A)
def set_kill_one(name: str, body: dict):
    return _toggle(name, "killed", body)


@app.post("/api/kill", dependencies=A)
def kill(body: dict):
    svc().set_kill(bool(body.get("enabled")))
    return {"ok": True}


@app.post("/api/restart", dependencies=A)
def restart():
    svc().restart()
    return {"ok": True}


@app.post("/api/flatten", dependencies=A)
def flatten():
    svc().flatten("user request")
    return {"ok": True}


@app.get("/api/risk", dependencies=A)
def risk():
    return svc().risk_snapshot()


@app.put("/api/risk/limits", dependencies=A)
def set_limits(body: dict):
    svc().set_limits(body)
    return {"ok": True}


@app.get("/api/positions", dependencies=A)
def positions():
    return svc().positions()


@app.get("/api/audit", dependencies=A)
def audit(limit: int = Query(300, le=5000)):
    return svc().audit.tail(limit)


@app.get("/api/regime", dependencies=A)
def regime():
    s = svc()
    return {"regime": s.regime, "probs": s.regime_probs, "hedgeLots": s.hedge_suggestion()}


@app.get("/api/calendar/holidays", dependencies=A)
def holidays():
    return nse.holidays()


@app.get("/api/market/flows", dependencies=A)
def flows():
    try:
        return nse.fii_dii()
    except Exception:
        return []


@app.get("/api/reports/leaderboard", dependencies=A)
def leaderboard():
    return reports.leaderboard()


@app.get("/api/reports/export", dependencies=A)
def export(format: str = "pdf"):
    rows = reports.leaderboard()
    trades = svc().engine.book.trades if svc().engine else []
    if format == "csv":
        return Response(reports.to_csv(rows), media_type="text/csv")
    return Response(reports.to_pdf(rows, trades), media_type="application/pdf")


@app.post("/api/backtest/{name}", dependencies=A)
def run_validation(name: str, bg: BackgroundTasks, years: int = 10):
    from .research import full_validation
    from .strategies import REGISTRY
    if name not in REGISTRY:
        raise HTTPException(404)
    bg.add_task(full_validation, name, years)
    return {"ok": True, "message": f"validation of {name} started; results appear in the leaderboard"}


@app.get("/api/models/meta", dependencies=A)
def model_meta():
    p = CONFIG.state_dir / "models" / "latest.json"
    if not p.exists():
        raise HTTPException(404, "no model trained yet: python -m tradelens train")
    return Response(p.read_text(), media_type="application/json")


@app.get("/api/models/latest.onnx", dependencies=A)
def model_file():
    p = CONFIG.state_dir / "models" / "latest.onnx"
    if not p.exists():
        raise HTTPException(404)
    return FileResponse(p, media_type="application/octet-stream")


@app.get("/api/briefs/latest", dependencies=A)
def briefs():
    return BRIEFS[-4:][::-1]


@app.websocket("/api/ws")
async def ws(websocket: WebSocket):
    token = (websocket.headers.get("authorization") or "").removeprefix("Bearer ").strip() or websocket.query_params.get("token", "")
    if not CONFIG.api_token or not hmac.compare_digest(token, CONFIG.api_token):
        await websocket.close(code=4401); return
    await websocket.accept()
    s = svc(); s.clients.add(websocket)
    try:
        await websocket.send_json({"type": "status", "data": s.status()})
        while True:
            msg = await websocket.receive_text()
            if '"ping"' in msg:
                await websocket.send_json({"type": "pong", "data": {"t": int(time.time())}})
    except WebSocketDisconnect:
        pass
    finally:
        s.clients.discard(websocket)


def make_brief(kind: str) -> dict:
    """Rule-based pre/post-market note with anomaly flags (the app can optionally rewrite it with an LLM)."""
    lines, anomalies = [], []
    for name in ("NIFTY 50", "BANKNIFTY", "INDIA VIX"):
        try:
            d = yahoo.download(name, "1D", start=int(time.time()) - 120 * 86400, use_cache=False)
            r = d["close"].pct_change()
            z = (r.iloc[-1] - r.iloc[-61:-1].mean()) / (r.iloc[-61:-1].std() + 1e-12)
            lines.append(f"{name}: {d['close'].iloc[-1]:,.2f} ({r.iloc[-1] * 100:+.2f}%)")
            if abs(z) > 2.5:
                anomalies.append(f"{name} moved {z:+.1f}σ")
            if d["volume"].iloc[-1] > 2.5 * d["volume"].iloc[-21:-1].mean() > 0:
                anomalies.append(f"{name} volume {d['volume'].iloc[-1] / d['volume'].iloc[-21:-1].mean():.1f}× average")
        except Exception:
            continue
    s = svc()
    lines.append(f"Regime: {s.regime}. Pending signals: {sum(1 for x in s.signals.values() if x.status == 'PENDING')}.")
    if kind == "post-market":
        st = s.status(); lines.append(f"Engine equity ₹{st['equity']:,.0f}, day P&L ₹{st['dayPnl']:,.0f}, drawdown {st['drawdownPct']:.1f}%.")
    return {"kind": kind, "title": f"{kind.title()} brief", "text": "\n".join(lines) + "\nNot financial advice.", "time": int(time.time()), "anomalies": anomalies}


async def brief_loop():
    from datetime import datetime
    from zoneinfo import ZoneInfo
    done = set()
    while True:
        now = datetime.now(ZoneInfo("Asia/Kolkata"))
        for kind, hh, mm in (("pre-market", 8, 40), ("post-market", 15, 50)):
            key = (kind, now.date())
            if key not in done and (now.hour, now.minute) >= (hh, mm) and now.weekday() < 5:
                try:
                    BRIEFS.append(await asyncio.to_thread(make_brief, kind))
                except Exception:
                    pass
                done.add(key)
        await asyncio.sleep(120)
