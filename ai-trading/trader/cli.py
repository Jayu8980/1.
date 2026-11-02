"""Command line: python -m trader {agents,scan,trade,monitor,status}."""
from __future__ import annotations

import argparse
import logging
import sys
from pathlib import Path

from . import __version__
from .agents import ALL_AGENTS
from .brokers import BROKERS, INDIAN_BROKERS, BrokerError
from .config import PRESETS, Config
from .server import add_serve_parser, serve

log = logging.getLogger("trader")


def build_parser() -> argparse.ArgumentParser:
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("-c", "--config", help="TOML config file (start from config.example.toml)")
    common.add_argument("--preset", choices=sorted(PRESETS), help="ready-made universe")
    common.add_argument("--symbols", help="comma-separated symbols, e.g. AAPL,MSFT,BTC-USD")
    common.add_argument("--broker", choices=BROKERS, help="override execution.broker")
    common.add_argument("--segment", choices=["delivery", "intraday", "futures", "options"],
                        help="how trades go through an Indian broker (override india.segment)")
    common.add_argument("--synthetic", action="store_true", help="offline demo on generated data")
    common.add_argument("--no-ai", action="store_true", help="don't call Claude")
    common.add_argument("-v", "--verbose", action="store_true", help="show every rejection as it happens")

    parser = argparse.ArgumentParser(prog="python -m trader", description="Multi-agent AI trading desk")
    parser.add_argument("--version", action="version", version=__version__)
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("agents", parents=[common], help="list the agents and what each one does")
    sub.add_parser("scan", parents=[common], help="run the funnel and report the best trades")
    trade = sub.add_parser("trade", parents=[common], help="scan, then place orders (paper unless --live)")
    trade.add_argument("--live", action="store_true", help="send orders to the real-money account")
    trade.add_argument("--yes", action="store_true", help="skip the confirmation prompt (for schedulers)")
    monitor = sub.add_parser("monitor", parents=[common], help="manage open positions and pending orders")
    monitor.add_argument("--live", action="store_true", help="manage the real-money account")
    login = sub.add_parser("login", parents=[common], help="log in to the broker (Zerodha: once a day)")
    login.add_argument("--live", action="store_true", help="check the real-money account (Alpaca, CCXT)")
    sub.add_parser("status", parents=[common], help="account, positions and closed trades")
    add_serve_parser(sub, common)
    return parser


def load_config(args) -> Config:
    cfg = Config.load(args.config)
    if args.preset:
        cfg.universe.preset = args.preset
        cfg.universe.symbols, cfg.universe.benchmark, cfg.universe.volatility_index = [], "", ""
        cfg.liquidity.min_turnover = 0.0
    if args.symbols:
        cfg.universe.symbols = args.symbols.split(",")
    if args.broker:
        cfg.execution.broker = args.broker
    if args.segment:
        cfg.india.segment = args.segment
    if args.synthetic:
        cfg.data.source = "synthetic"
    if args.no_ai:
        cfg.llm.enabled = "off"
    live = getattr(args, "live", False)
    if live:
        cfg.execution.mode = "live"
    elif args.command in ("trade", "monitor") and cfg.execution.mode == "live":
        raise SystemExit("execution.mode is 'live' in the config: add --live to confirm real-money trading")
    if cfg.data.source == "synthetic":  # keep demo state away from the real paper account
        cfg.execution.state_dir = str(Path(cfg.execution.state_dir) / "synthetic")
        cfg.execution.reports_dir = str(Path(cfg.execution.reports_dir) / "synthetic")
    return cfg.finalize()


def confirm_live(cfg, assume_yes: bool) -> bool:
    if cfg.execution.mode != "live":
        return True
    ex, rc = cfg.execution, cfg.risk
    print("\n" + "!" * 78)
    print(f"LIVE TRADING: orders go to your real {ex.broker} account.")
    print(f"Risk per trade {rc.risk_per_trade:.2%} of equity, max {rc.max_open_positions} positions, "
          f"max order value {ex.max_order_value:,.0f}, daily loss limit {ex.max_daily_loss_pct:.0%}.")
    if ex.broker in INDIAN_BROKERS:
        print(f"Segment: {cfg.india.segment}.", {
            "delivery": "Delivery (CNC) buys only.",
            "intraday": "Intraday (MIS) longs and shorts, squared off by "
                        f"{cfg.india.intraday_square_off} IST.",
            "futures": "LEVERAGED futures: a gap can lose more than the planned risk.",
            "options": "Option buying: each trade can lose the whole premium.",
        }[cfg.india.segment])
    print("!" * 78)
    if assume_yes:
        return True
    if not sys.stdin.isatty():
        print("No terminal to confirm on. Re-run with --yes if you really mean it.")
        return False
    return input("Type LIVE to continue: ").strip() == "LIVE"


def cmd_agents(cfg) -> int:
    print("The desk's agents, in the order a candidate meets them:\n")
    for cls in ALL_AGENTS:
        print(f"L{cls.layer:02d}  {cls.title}")
        print(f"      {cls.role}\n")
    return 0


def cmd_scan(cfg, execute: bool) -> int:
    from .desk import TradingDesk
    from .report import render, save

    desk = TradingDesk(cfg)
    board = desk.scan()
    orders = desk.execute() if execute else None
    print("\n" + render(board, cfg, orders))
    json_path, md_path = save(board, cfg, orders)
    print(f"\nReport: {md_path}\nData:   {json_path}")
    return 0


def cmd_monitor(cfg) -> int:
    from .desk import TradingDesk

    desk = TradingDesk(cfg, use_llm=False)
    closed = desk.monitor()
    for t in closed:
        pnl = f" P&L {t['pnl']:+,.2f} ({t.get('r_multiple', 0):+.2f}R)" if "pnl" in t else ""
        print(f"{t['symbol']}: {t.get('status', 'closed')} ({t.get('reason')}){pnl}")
    if not closed:
        print("No exits or cancellations.")
    return cmd_status(cfg, broker=desk.broker)


def cmd_login(cfg) -> int:
    from .brokers import make_broker

    print(make_broker(cfg).login())
    return 0


def cmd_status(cfg, broker=None) -> int:
    from .brokers import make_broker

    broker = broker or make_broker(cfg)
    acct = broker.account()
    mode = "LIVE" if broker.live else ("dry run" if broker.name not in ("paper", "alpaca", "ccxt") else "paper")
    print(f"\nAccount ({broker.name}, {mode}): equity {acct['equity']:,.2f}  cash {acct['cash']:,.2f}")
    positions = broker.positions()
    print(f"\nPositions and orders managed by the desk ({len(positions)}):")
    for p in positions:
        entry = p.get("entry")
        print(f"  {p['symbol']:<12} {p.get('status', 'open'):<8} {p.get('side', ''):<5} qty {p.get('qty', 0):g}  "
              f"entry {entry if entry is not None else '-'}  stop {p.get('stop')}  target {p.get('target')}  "
              f"last {p.get('last_price', '-')}")
    outside = sorted(s for s in broker.held_symbols() - {p["symbol"] for p in positions}
                     if not s.endswith(".BO"))
    if outside:
        print(f"\nAlready in your account, so the desk will not trade them: {', '.join(outside)}")
    closed = broker.closed_trades() if hasattr(broker, "closed_trades") else []
    if closed:
        wins = [t for t in closed if t["pnl"] > 0]
        total = sum(t["pnl"] for t in closed)
        avg_r = sum(t["r_multiple"] for t in closed) / len(closed)
        print(f"\nClosed trades: {len(closed)} | win rate {len(wins) / len(closed):.0%} | "
              f"total P&L {total:+,.2f} | average {avg_r:+.2f}R")
        for t in closed[-10:]:
            print(f"  {t['closed']} {t['symbol']:<12} {t['side']:<5} {t['reason']:<7} "
                  f"{t['pnl']:+,.2f} ({t['r_multiple']:+.2f}R)")
    return 0


def setup_logging(verbose: bool) -> None:
    logging.basicConfig(level=logging.DEBUG if verbose else logging.INFO, format="%(message)s")
    for noisy in ("yfinance", "urllib3", "httpx", "httpcore", "anthropic", "peewee", "matplotlib", "requests"):
        logging.getLogger(noisy).setLevel(logging.WARNING)


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    setup_logging(args.verbose)
    if args.command == "serve":
        try:
            return serve(args)
        except (ValueError, OSError) as exc:
            print(f"Server error: {exc}", file=sys.stderr)
            return 2
    try:
        cfg = load_config(args)
    except (ValueError, OSError) as exc:
        print(f"Configuration error: {exc}", file=sys.stderr)
        return 2
    try:
        if args.command == "agents":
            return cmd_agents(cfg)
        if args.command == "scan":
            return cmd_scan(cfg, execute=False)
        if args.command == "trade":
            if not confirm_live(cfg, args.yes):
                print("Cancelled.")
                return 1
            return cmd_scan(cfg, execute=True)
        if args.command == "monitor":
            return cmd_monitor(cfg)
        if args.command == "login":
            return cmd_login(cfg)
        if args.command == "status":
            return cmd_status(cfg)
    except BrokerError as exc:
        print(f"Broker error: {exc}", file=sys.stderr)
        return 1
    return 2
