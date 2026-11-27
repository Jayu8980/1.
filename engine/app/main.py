"""Phase-1 entrypoint: health only. Full REST/WebSocket API with JWT arrives in Phase 4."""
from fastapi import FastAPI

from app.config import get_settings

app = FastAPI(title="Trading Engine", version="0.1.0")


@app.get("/health")
async def health():
    s = get_settings()
    return {"status": "ok", "mode": s.mode, "allow_live": s.allow_live}
