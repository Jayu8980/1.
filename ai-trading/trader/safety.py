"""Account-level safety rails shared by the execution and monitor agents, plus the trade journal."""
from __future__ import annotations

import csv
import json
from datetime import date, datetime
from pathlib import Path

JOURNAL_FIELDS = ["time", "event", "mode", "broker", "symbol", "side", "qty", "entry_ref", "stop",
                  "target", "setup", "status", "order_id", "price", "pnl", "r_multiple", "reason"]


def kill_switch_path(cfg) -> Path:
    return Path(cfg.execution.state_dir) / "KILL_SWITCH"


def check_daily_loss(cfg, equity: float) -> str | None:
    """Remember the day's starting equity; engage the kill switch once the daily loss limit is hit."""
    path = Path(cfg.execution.state_dir) / "day_start.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    today = date.today().isoformat()
    state = json.loads(path.read_text()) if path.exists() else {}
    if state.get("date") != today:
        path.write_text(json.dumps({"date": today, "equity": equity}))
        return None
    start = float(state.get("equity") or 0)
    if start > 0 and (start - equity) / start >= cfg.execution.max_daily_loss_pct:
        kill_switch_path(cfg).write_text(
            f"Daily loss limit hit on {today}: equity {equity:,.2f} vs {start:,.2f} at the start of the day.\n"
            "Delete this file to allow new orders again.\n")
        return f"daily loss limit of {cfg.execution.max_daily_loss_pct:.0%} hit - kill switch engaged"
    return None


def safety_block(cfg, broker) -> str | None:
    """Return why no new orders may be sent, or None when trading is allowed."""
    switch = kill_switch_path(cfg)
    if switch.exists():
        return f"kill switch is on ({switch}); delete the file to resume trading"
    if broker.live and cfg.data.source == "synthetic":
        return "synthetic data cannot be traded live"
    try:
        equity = float(broker.account().get("equity") or 0)
    except Exception as exc:
        return f"could not read the account: {exc}"
    return check_daily_loss(cfg, equity)


def journal(cfg, event: str, **row) -> None:
    path = Path(cfg.execution.state_dir) / "journal.csv"
    path.parent.mkdir(parents=True, exist_ok=True)
    new = not path.exists()
    with path.open("a", newline="") as fh:
        writer = csv.DictWriter(fh, fieldnames=JOURNAL_FIELDS, extrasaction="ignore")
        if new:
            writer.writeheader()
        writer.writerow({"time": datetime.now().isoformat(timespec="seconds"), "event": event,
                         "mode": cfg.execution.mode, "broker": cfg.execution.broker, **row})
