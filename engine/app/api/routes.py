from __future__ import annotations

from html import escape

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import HTMLResponse
from pydantic import BaseModel

from app.core.errors import BrokerError, OrderBlocked

from .security import check_password, current_user, signed_user

router = APIRouter(prefix="/api/v1")


class LoginBody(BaseModel):
    username: str
    password: str
    devicePublicKey: str


@router.post("/auth/login")
async def login(body: LoginBody, request: Request):
    st = request.app.state
    s = st.settings
    if not (s.app_user and s.app_password_hash) or body.username != s.app_user or \
            not check_password(body.password, s.app_password_hash):
        await st.audit.record("auth.app_login_failed", user=body.username)
        raise HTTPException(401, "bad credentials")
    try:
        st.security.devices.put(body.username, body.devicePublicKey)
    except Exception:
        raise HTTPException(400, "invalid device public key") from None
    await st.audit.record("auth.app_login", user=body.username)
    return {"token": st.security.issue(body.username), "expiresIn": s.jwt_ttl_min * 60}


# ------------------------------------------------------------------ brokers
def _err(e: Exception) -> HTTPException:
    if isinstance(e, OrderBlocked):
        return HTTPException(409, e.reason)
    return HTTPException(400, str(e))


@router.get("/brokers")
async def list_brokers(request: Request, user: str = Depends(current_user)):
    return await request.app.state.brokers.list()


@router.post("/brokers/{broker_id}/connect")
async def connect(broker_id: str, request: Request, user: str = Depends(signed_user)):
    try:
        return await request.app.state.brokers.start_connect(broker_id, user)
    except (BrokerError, OrderBlocked) as e:
        raise _err(e) from e


@router.post("/brokers/{broker_id}/activate")
async def activate(broker_id: str, request: Request, user: str = Depends(signed_user)):
    try:
        await request.app.state.brokers.activate(broker_id, user)
    except (BrokerError, OrderBlocked) as e:
        raise _err(e) from e
    return {"ok": True, "message": f"{broker_id} is now the live broker"}


@router.post("/brokers/{broker_id}/disconnect")
async def disconnect(broker_id: str, request: Request, user: str = Depends(current_user)):
    m = request.app.state.brokers
    if broker_id == m.active_id and request.app.state.gateway.mode.value == "LIVE":
        raise HTTPException(409, "switch to PAPER before disconnecting the live broker")
    try:
        await m.disconnect(broker_id, user)
    except BrokerError as e:
        raise _err(e) from e
    return {"ok": True, "message": "Disconnected"}


def _page(title: str, msg: str, ok: bool) -> HTMLResponse:
    color = "#16a34a" if ok else "#dc2626"
    return HTMLResponse(
        f"<!doctype html><meta name=viewport content='width=device-width'><title>{escape(title)}</title>"
        f"<body style='font-family:sans-serif;background:#0e1116;color:#eee;padding:32px;text-align:center'>"
        f"<h2 style='color:{color}'>{escape(title)}</h2><p>{escape(msg)}</p>"
        f"<p><a style='color:#6ea0ff;font-size:18px' href='aitrader://brokers'>Return to AI Trader</a></p></body>",
        status_code=200 if ok else 400)


@router.get("/brokers/{broker_id}/callback", include_in_schema=False)
async def oauth_callback(broker_id: str, request: Request):
    """Browser redirect target registered in each broker's developer console. Protected by the
    single-use `state` minted in /connect (no JWT: this is the system browser)."""
    params = dict(request.query_params)
    try:
        account = await request.app.state.brokers.finish_oauth(broker_id, params)
    except Exception as e:
        return _page("Broker login failed", str(e), False)
    return _page("Broker connected", f"{broker_id} account {account or ''} is connected to the engine.", True)
