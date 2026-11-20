"""Leaderboard and exportable PDF/CSV reports."""
from __future__ import annotations

import csv
import io
import json
import math

from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import getSampleStyleSheet
from reportlab.platypus import Paragraph, SimpleDocTemplate, Spacer, Table, TableStyle

from .config import CONFIG

COLS = ["name", "cagr", "sharpe", "sortino", "calmar", "maxDd", "winRate", "profitFactor", "expectancy", "alpha", "beta", "ir", "turnover", "trades", "stage"]


def _finite(v):
    """JSON has no Infinity/NaN (e.g. profit factor with no losing trades) – send null instead."""
    if isinstance(v, float) and not math.isfinite(v):
        return None
    if isinstance(v, dict):
        return {k: _finite(x) for k, x in v.items()}
    if isinstance(v, list):
        return [_finite(x) for x in v]
    return v


def leaderboard() -> list[dict]:
    d = CONFIG.state_dir / "reports"
    out = []
    if d.exists():
        for f in sorted(d.glob("*.json")):
            r = json.loads(f.read_text())
            out.append(_finite({k: r.get(k) for k in COLS + ["equity", "benchmarkEquity", "drawdown", "monthly", "benchmark"]}))
    return sorted(out, key=lambda r: -(r.get("sharpe") or 0))


def to_csv(rows: list[dict]) -> str:
    buf = io.StringIO()
    w = csv.DictWriter(buf, fieldnames=COLS, extrasaction="ignore")
    w.writeheader()
    for r in rows:
        w.writerow(r)
    return buf.getvalue()


def to_pdf(rows: list[dict], trades: list[dict] | None = None) -> bytes:
    buf = io.BytesIO()
    doc = SimpleDocTemplate(buf, pagesize=A4, title="TradeLens strategy report")
    st = getSampleStyleSheet()
    story = [Paragraph("TradeLens – strategy leaderboard", st["Title"]),
             Paragraph("Metrics after all charges, slippage and impact. Not financial advice.", st["Normal"]), Spacer(1, 12)]
    data = [["Strategy", "CAGR%", "Sharpe", "Calmar", "MaxDD%", "Win%", "PF", "Alpha%", "Beta", "Trades", "Stage"]]
    for r in rows:
        f = lambda k, n=2: f"{(r.get(k) or 0):.{n}f}"
        data.append([r["name"], f("cagr", 1), f("sharpe"), f("calmar"), f("maxDd", 1), f("winRate", 1), f("profitFactor"), f("alpha", 1), f("beta"), str(r.get("trades", 0)), r.get("stage", "")])
    t = Table(data, repeatRows=1)
    t.setStyle(TableStyle([("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#1A2230")), ("TEXTCOLOR", (0, 0), (-1, 0), colors.white),
                           ("FONTSIZE", (0, 0), (-1, -1), 7), ("GRID", (0, 0), (-1, -1), 0.25, colors.grey)]))
    story.append(t)
    if trades:
        story += [Spacer(1, 16), Paragraph("Trade journal (latest 100)", st["Heading2"])]
        tj = [["Closed", "Strategy", "Symbol", "Side", "Qty", "Entry", "Exit", "P&L", "Reason"]]
        for x in trades[-100:]:
            tj.append([str(x.get("closed", ""))[:16], x.get("strategy", ""), x.get("symbol", ""), x.get("side", ""), f"{x.get('qty', 0):.0f}",
                       f"{x.get('entry', 0):.2f}", f"{x.get('exit', 0):.2f}", f"{x.get('pnl', 0):,.0f}", str(x.get("reason", ""))[:30]])
        t2 = Table(tj, repeatRows=1); t2.setStyle(TableStyle([("FONTSIZE", (0, 0), (-1, -1), 6), ("GRID", (0, 0), (-1, -1), 0.25, colors.grey)]))
        story.append(t2)
    doc.build(story)
    return buf.getvalue()
