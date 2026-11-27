"""External REST API for scripts and other apps: /api/v1/ext/*, authenticated with scoped API keys.
OpenAPI docs: /docs (tag "external")."""
from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Query, Request
from pydantic import BaseModel, Field, ValidationError

from app.services.apikeys import ApiKey

router = APIRouter(prefix="/api/v1/ext", tags=["external"])


def _raw_key(request: Request) -> str | None:
    k = request.headers.get("x-api-key")
    if not k:
        auth = request.headers.get("authorization", "")
        k = auth[7:] if auth.lower().startswith("bearer atk_") else None
    return k


def need(scope: str):
    def dep(request: Request) -> ApiKey:
        try:
            return request.app.state.apikeys.verify(_raw_key(request), scope)
        except PermissionError as e:
            code = 429 if "rate limit" in str(e) else 403 if "lacks" in str(e) else 401
            raise HTTPException(code, str(e)) from None
    return dep


def ext(request: Request):
    return request.app.state.external


async def _run(coro):
    try:
        return await coro
    except (ValueError, ValidationError) as e:
        raise HTTPException(422, str(e)) from None


@router.get("/status")
async def status(request: Request, k: ApiKey = Depends(need("read"))):
    return await ext(request).status(k)


@router.get("/brokers")
async def brokers(request: Request, k: ApiKey = Depends(need("read"))):
    return await ext(request).brokers(k)


@router.post("/brokers/{broker_id}/connect")
async def connect(broker_id: str, request: Request, k: ApiKey = Depends(need("control"))):
    return await ext(request).connect_broker(k, broker_id)


@router.get("/quotes")
async def quotes(request: Request, symbols: str | None = None, k: ApiKey = Depends(need("read"))):
    return await ext(request).quotes(k, [x for x in symbols.split(",") if x] if symbols else None)


@router.get("/instruments/search")
async def search(request: Request, q: str = Query(min_length=2, max_length=30), exchange: str = "NSE",
                 k: ApiKey = Depends(need("read"))):
    return await ext(request).search(k, q, exchange)


@router.get("/account")
async def account(request: Request, account: str = "engine", k: ApiKey = Depends(need("read"))):
    return await _run(ext(request).account(k, account))


@router.get("/positions")
async def positions(request: Request, account: str = "engine", k: ApiKey = Depends(need("read"))):
    return await _run(ext(request).positions(k, account))


@router.get("/orders")
async def orders(request: Request, account: str = "engine", open_only: bool = False, k: ApiKey = Depends(need("read"))):
    return await _run(ext(request).orders(k, account, open_only))


@router.get("/risk")
async def risk(request: Request, k: ApiKey = Depends(need("read"))):
    return await ext(request).risk(k)


@router.get("/signals")
async def signals(request: Request, limit: int = Query(20, le=200), k: ApiKey = Depends(need("read"))):
    return await ext(request).signals(k, limit)


class OrderBody(BaseModel):
    symbol: str
    exchange: str
    side: str
    qty: int
    orderType: str = "LIMIT"
    price: float | None = None
    triggerPrice: float | None = None
    product: str = "INTRADAY"
    reason: str = Field("", max_length=300, description="Shown to you on the phone when approval is needed")


@router.post("/orders")
async def place(body: OrderBody, request: Request, k: ApiKey = Depends(need("trade"))):
    """PAPER: executes. LIVE: returns PENDING_APPROVAL + intentId; approve on the phone."""
    return await _run(ext(request).place_order(k, body.model_dump(exclude={"reason"}), body.reason))


@router.get("/intents/{intent_id}")
async def intent(intent_id: str, request: Request, k: ApiKey = Depends(need("read"))):
    return await _run(ext(request).intent(k, intent_id))


@router.post("/orders/{order_id}/cancel")
async def cancel(order_id: str, request: Request, account: str = "engine", k: ApiKey = Depends(need("control"))):
    return await ext(request).cancel(k, order_id, account)


class Reason(BaseModel):
    reason: str = ""


@router.post("/kill-switch")
async def kill(body: Reason, request: Request, k: ApiKey = Depends(need("control"))):
    return await ext(request).kill_switch(k, body.reason)


@router.post("/autotrade/off")
async def auto_off(request: Request, k: ApiKey = Depends(need("control"))):
    return await ext(request).autotrade_off(k)


@router.post("/watch")
async def watch(contract: dict, request: Request, k: ApiKey = Depends(need("control"))):
    return await _run(ext(request).watch(k, contract))
