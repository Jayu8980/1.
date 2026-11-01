"""Console and file reports for a scan."""
from __future__ import annotations

import json
from datetime import datetime
from pathlib import Path

from .llm import to_jsonable

WIDTH = 92
DISCLAIMER = ("Backtests and model probabilities describe the past, not the future. Paper trade first, "
              "risk only money you can afford to lose, and treat every output as a candidate, not advice.")
_REGIME_KEYS = ("regime", "trend_score", "volatility", "vol_measure", "breadth", "benchmark_return_63d",
                "benchmark_drawdown", "risk_multiplier", "notes")


def _layer_of(board) -> dict[str, int]:
    return {row["agent"]: row["layer"] for row in board.funnel}


def final_trades(board):
    return sorted(board.alive(), key=lambda c: c.cache.get("rank", 99))


def near_misses(board, n: int = 5):
    layers = _layer_of(board)
    rejected = [c for c in board.rejected() if c.rejected_by in layers]
    return sorted(rejected, key=lambda c: layers[c.rejected_by], reverse=True)[:n]


def trade_card(cand) -> list[str]:
    p, c, r = cand.plan, cand.cache, cand.reports
    setup = c["setup"]
    ins, oos = setup["in_sample"], setup["out_of_sample"]
    ml = r["ml"].data
    lines = [
        f"#{c['rank']}  {cand.symbol}  {cand.side.upper()}   setup: {p['setup']}",
        f"    entry ~{p['entry_ref']:g}   stop {p['stop']:g}   target {p['target']:g}   "
        f"({p['reward_risk']}R, exit after {p['max_hold']} bars at the latest)",
        (f"    traded as {p['segment']}: contract and lots are chosen at the broker; risk budget "
         f"{p['risk_amount']:,.0f} ({p['risk_pct']:.2%} of equity)" if p.get("sized_by_broker") else
         f"    size {p['qty']:g}   value {p['notional']:,.0f}   risk {p['risk_amount']:,.0f} "
         f"({p['risk_pct']:.2%} of equity)" + (f"   [{p['segment']}]" if p.get("segment") == "intraday" else "")),
        f"    conviction {c['conviction']:.2f}   expected value {c['expected_r']:+.2f}R   "
        f"ML P(profit) {ml['prob']:.0%} (walk-forward AUC {ml['auc']:.2f})",
        f"    backtest  in-sample {ins['trades']} trades PF {ins['profit_factor']:.2f} {ins['expectancy']:+.2f}R | "
        f"out-of-sample {oos['trades']} trades PF {oos['profit_factor']:.2f} {oos['expectancy']:+.2f}R | "
        f"robust {setup['robustness']:.0%}",
        "    agent scores: " + "  ".join(f"{name} {rep.score:.2f}" for name, rep in r.items()
                                         if rep.score is not None),
    ]
    for name in ("regime", "technical", "timeframe", "price_action", "sentiment", "fundamental", "ai_review"):
        rep = r.get(name)
        if rep and rep.reasons:
            lines.append(f"    {name:<12} {rep.reasons[0]}")
    return lines


def render(board, cfg, orders: list[dict] | None = None) -> str:
    ctx = board.context
    regime = ctx.get("regime") or {}
    total = max(1, len(board.candidates))
    out = ["=" * WIDTH,
           f"MULTI-AGENT TRADING DESK | {ctx.get('started', '')} | {len(board.candidates)} symbols | "
           f"data: {cfg.data.source} | broker: {cfg.execution.broker} ({cfg.execution.mode})",
           "=" * WIDTH]
    if regime:
        breadth = f"{regime['breadth']:.0%}" if regime.get("breadth") is not None else "n/a"
        out.append(f"Market: {regime.get('regime', '?').upper()} | volatility {regime.get('volatility')} "
                   f"{('(' + regime['vol_measure'] + ')') if regime.get('vol_measure') else ''} | "
                   f"breadth {breadth} | position-size multiplier x{regime.get('risk_multiplier', 1):.2f}")
    out += ["", "FILTRATION FUNNEL (each layer is one agent)"]
    for row in board.funnel:
        bar = "#" * round(40 * row["passed"] / total)
        out.append(f"  L{row['layer']:02d} {row['title']:<36} {row['entered']:>3} -> {row['passed']:<3} {bar}")

    trades = final_trades(board)
    out.append("")
    if trades:
        out.append(f"FINAL TRADES ({len(trades)})")
        for cand in trades:
            out += [""] + trade_card(cand)
    else:
        out.append("NO TRADE: nothing passed every layer. Staying flat is a valid decision.")
    misses = near_misses(board)
    if misses:
        out += ["", "Closest misses:"]
        for cand in misses:
            out.append(f"  {cand.symbol:<12} stopped at {cand.rejected_by}: {cand.rejection}")
    if orders:
        out += ["", "ORDERS"]
        for o in orders:
            out.append(f"  {o['symbol']:<12} {o['side']:<5} {o['qty']:g} -> {o.get('status')} {o.get('reason', '')}")
    out += ["", DISCLAIMER, "=" * WIDTH]
    return "\n".join(out)


def _trade_record(cand) -> dict:
    return {"rank": cand.cache.get("rank"), "symbol": cand.symbol, "side": cand.side, "plan": cand.plan,
            "conviction": cand.cache.get("conviction"), "expected_r": cand.cache.get("expected_r"),
            "reports": {name: {"passed": r.passed, "score": r.score, "reasons": r.reasons, "data": r.data}
                        for name, r in cand.reports.items()}}


def scan_payload(board, cfg, orders: list[dict] | None = None) -> dict:
    """The scan as plain JSON (saved next to the report and served to the phone app)."""
    regime = board.context.get("regime") or {}
    layers = _layer_of(board)
    return to_jsonable({
        "generated": board.context.get("started"),
        "data_source": cfg.data.source,
        "universe": {"preset": cfg.universe.preset, "benchmark": cfg.universe.benchmark,
                     "symbols": cfg.universe.symbols},
        "market_regime": {k: regime.get(k) for k in _REGIME_KEYS},
        "funnel": board.funnel,
        "trades": [_trade_record(c) for c in final_trades(board)],
        "rejected": [{"symbol": c.symbol, "agent": c.rejected_by, "layer": layers.get(c.rejected_by),
                      "reason": c.rejection} for c in board.rejected()],
        "orders": orders or [],
    })


def save(board, cfg, orders: list[dict] | None = None) -> tuple[Path, Path]:
    folder = Path(cfg.execution.reports_dir)
    folder.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    regime = board.context.get("regime") or {}
    payload = scan_payload(board, cfg, orders)
    json_path = folder / f"scan-{stamp}.json"
    json_path.write_text(json.dumps(payload, indent=2))

    md = [f"# Trading desk report - {payload['generated']}", "",
          f"Data: {cfg.data.source} | Broker: {cfg.execution.broker} ({cfg.execution.mode}) | "
          f"Universe: {len(cfg.universe.symbols)} symbols vs {cfg.universe.benchmark}", ""]
    if regime:
        md += [f"**Market regime:** {regime.get('regime')} | volatility {regime.get('volatility')} | "
               f"size multiplier x{regime.get('risk_multiplier', 1):.2f}", ""]
    md += ["## Filtration funnel", "", "| Layer | Agent | In | Passed | Rejected (reason) |", "|---|---|---|---|---|"]
    for row in board.funnel:
        why = "<br>".join(f"{s}: {r}" for s, r in row["rejected"][:8])
        if len(row["rejected"]) > 8:
            why += f"<br>... {len(row['rejected']) - 8} more"
        md.append(f"| L{row['layer']} | {row['title']} | {row['entered']} | {row['passed']} | {why} |")
    md += ["", "## Final trades", ""]
    trades = final_trades(board)
    if not trades:
        md.append("No trade passed every layer.")
    for cand in trades:
        md += ["```", *trade_card(cand), "```", ""]
        ai = cand.reports.get("ai_review")
        if ai and ai.data.get("bull_case"):
            md += [f"- **Bull case:** {ai.data['bull_case']}", f"- **Bear case:** {ai.data['bear_case']}",
                   f"- **Key risks:** {'; '.join(ai.data.get('key_risks', []))}", ""]
    if orders:
        md += ["## Orders", ""] + [f"- {o['symbol']} {o['side']} {o['qty']:g}: {o.get('status')} {o.get('reason', '')}"
                                   for o in orders] + [""]
    md += ["---", DISCLAIMER]
    md_path = folder / f"scan-{stamp}.md"
    md_path.write_text("\n".join(md))
    return json_path, md_path
