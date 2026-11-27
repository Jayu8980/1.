"""Seed 5-min bars from history so strategies don't wait ~5 hours after a restart."""
from __future__ import annotations

import logging
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

import httpx

from app.core.enums import Exchange
from app.core.models import Contract

from .bars import Bar

IST = ZoneInfo("Asia/Kolkata")
log = logging.getLogger("warmup")


async def angel_bars(angel, c: Contract, days: int = 5) -> list[Bar]:
    if not angel.s.is_logged_in or c.exchange not in (Exchange.NSE, Exchange.BSE, Exchange.NFO, Exchange.BFO):
        return []
    to = datetime.now(IST); fr = to - timedelta(days=days)
    rows = await angel.s.call_with_reauth(angel.c.candles, c.exchange.value, c.token, "FIVE_MINUTE",
                                          fr.strftime("%Y-%m-%d %H:%M"), to.strftime("%Y-%m-%d %H:%M"))
    # [timestamp, open, high, low, close, volume]; drop the still-forming last bar
    return [Bar(datetime.fromisoformat(r[0]).astimezone(timezone.utc), float(r[1]), float(r[2]), float(r[3]), float(r[4]))
            for r in rows[:-1]]


async def delta_bars(base_url: str, c: Contract, hours: int = 24, transport=None) -> list[Bar]:
    if c.exchange is not Exchange.DELTA:
        return []
    end = int(datetime.now(timezone.utc).timestamp()); start = end - hours * 3600
    async with httpx.AsyncClient(base_url=base_url, timeout=10, transport=transport) as h:
        r = await h.get("/v2/history/candles", params={"resolution": "5m", "symbol": c.ids.get("delta") or c.symbol,
                                                       "start": start, "end": end})
        rows = r.json().get("result", [])
    rows = sorted(rows, key=lambda x: x["time"])[:-1]
    return [Bar(datetime.fromtimestamp(x["time"], timezone.utc), float(x["open"]), float(x["high"]),
                float(x["low"]), float(x["close"])) for x in rows]


async def warm(autotrader, contracts: list[Contract], angel, delta_base: str, transport=None) -> dict[str, int]:
    from .bars import BarSeries
    done = {}
    for c in contracts:
        try:
            bars = await (delta_bars(delta_base, c, transport=transport) if c.exchange is Exchange.DELTA
                          else angel_bars(angel, c))
        except Exception as e:
            log.info("warmup %s skipped: %s", c.key, e)
            continue
        if bars:
            s = autotrader.series.setdefault(c.key, BarSeries(autotrader.s.timeframe_min))
            if len(s.bars) < len(bars):
                s.seed(bars)
            done[c.key] = len(bars)
    return done
