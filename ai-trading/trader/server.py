"""Desk server: a small JSON API so the phone app can drive this desk over your network.

    python -m trader serve --preset nifty50 --broker zerodha           # paper / dry run
    python -m trader serve --broker zerodha --live                     # real orders (asks to confirm)

Every request except /api/health needs ``Authorization: Bearer <token>``. The token comes from
``--token`` or the ``TRADER_TOKEN`` environment variable; otherwise a random one is printed at start.
The server speaks plain HTTP: keep it on your home network or a private VPN (e.g. Tailscale), never
open it to the internet. Indian brokers also require orders to come from your registered static IP,
which is this machine, not the phone.

Endpoints (JSON in and out):
    GET  /api/health                  liveness, no token needed
    GET  /api/info                    broker, mode, presets
    GET  /api/status                  account, positions and closed trades
    GET  /api/quotes?symbols=A,B      live prices: the broker's real-time LTP, else Yahoo
    POST /api/scan     {preset, symbols, segment, synthetic, ai}  -> {job}
    POST /api/trade    same + {confirm: "LIVE"} on a live server -> {job}
    POST /api/monitor  {}                                        -> {job}
    GET  /api/jobs/<id>?since=<n>      progress lines, then the result
Only one job runs at a time; a second request gets HTTP 409.
"""
from __future__ import annotations

import hmac
import json
import logging
import os
import re
import secrets
import threading
import time
import uuid
from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

from . import __version__
from .brokers import BrokerError
from .config import PRESETS

log = logging.getLogger("trader.server")

SEGMENTS = ("delivery", "intraday", "futures", "options")
SYMBOL_RE = re.compile(r"^[A-Za-z0-9^&._:/=-]{1,40}$")
MAX_BODY = 64 * 1024


class Job:
    def __init__(self, kind: str) -> None:
        self.id = uuid.uuid4().hex[:12]
        self.kind = kind
        self.status = "running"
        self.started = time.time()
        self.finished: float | None = None
        self.lines: deque[str] = deque(maxlen=1000)
        self.count = 0  # total lines ever written, so clients can poll with ?since=
        self.result: dict | None = None
        self.error: str | None = None

    def write(self, line: str) -> None:
        self.lines.append(line)
        self.count += 1

    def view(self, since: int = 0) -> dict:
        first = self.count - len(self.lines)
        lines = list(self.lines)[max(0, since - first):]
        return {"id": self.id, "kind": self.kind, "status": self.status, "started": self.started,
                "finished": self.finished, "lines": lines, "next": self.count,
                "result": self.result, "error": self.error}


class _JobLog(logging.Handler):
    def __init__(self, job: Job) -> None:
        super().__init__(logging.INFO)
        self.job = job
        self.setFormatter(logging.Formatter("%(message)s"))

    def emit(self, record: logging.LogRecord) -> None:
        try:
            self.job.write(self.format(record))
        except Exception:  # never let logging break a job
            pass


class DeskServer:
    """Runs desk commands for the HTTP handler, one at a time, in a background thread."""

    def __init__(self, base_args: list[str], live: bool, token: str) -> None:
        self.base_args = base_args  # e.g. ["-c", "config.toml", "--broker", "zerodha"]
        self.live = live
        self.token = token
        self.lock = threading.Lock()
        self.jobs: dict[str, Job] = {}
        self.current: Job | None = None
        self._quotes = None

    # -- config ----------------------------------------------------------------------------------
    def config(self, command: str, req: dict | None = None):
        from .cli import build_parser, load_config

        req = req or {}
        argv = [command, *self.base_args]
        preset = req.get("preset")
        if preset:
            if preset not in PRESETS:
                raise ValueError(f"unknown preset {preset!r}")
            argv += ["--preset", preset]
        symbols = req.get("symbols")
        if symbols:
            if isinstance(symbols, str):
                symbols = [s.strip() for s in symbols.split(",") if s.strip()]
            if not isinstance(symbols, list) or len(symbols) > 600 or \
                    not all(isinstance(s, str) and SYMBOL_RE.match(s) for s in symbols):
                raise ValueError("symbols must be a list of ticker symbols")
            argv += ["--symbols", ",".join(symbols)]
        segment = req.get("segment")
        if segment:
            if segment not in SEGMENTS:
                raise ValueError(f"segment must be one of {', '.join(SEGMENTS)}")
            argv += ["--segment", segment]
        if req.get("synthetic"):
            argv.append("--synthetic")
        if req.get("ai") is False:
            argv.append("--no-ai")
        if self.live and command in ("trade", "monitor"):
            argv.append("--live")
        try:
            return load_config(build_parser().parse_args(argv))
        except SystemExit as exc:  # load_config refuses unsafe combinations this way
            raise ValueError(str(exc)) from None

    # -- jobs ------------------------------------------------------------------------------------
    def start(self, kind: str, req: dict) -> Job:
        cfg = self.config("monitor" if kind == "monitor" else kind, req)
        if not self.lock.acquire(blocking=False):
            raise RuntimeError("busy")
        job = Job(kind)
        self.jobs[job.id] = job
        self.current = job
        for old in sorted(self.jobs.values(), key=lambda j: j.started)[:-20]:
            self.jobs.pop(old.id, None)
        threading.Thread(target=self._run, args=(job, cfg), daemon=True).start()
        return job

    def _run(self, job: Job, cfg) -> None:
        handler = _JobLog(job)
        root = logging.getLogger("trader")
        root.addHandler(handler)
        if root.getEffectiveLevel() > logging.INFO:
            root.setLevel(logging.INFO)
        try:
            job.result = self._work(job.kind, cfg)
            job.status = "done"
        except BrokerError as exc:
            job.error, job.status = f"broker error: {exc}", "error"
        except Exception as exc:  # report every failure to the app instead of dying silently
            log.exception("job %s failed", job.id)
            job.error, job.status = f"{type(exc).__name__}: {exc}", "error"
        finally:
            root.removeHandler(handler)
            job.finished = time.time()
            self.lock.release()

    def _work(self, kind: str, cfg) -> dict:
        from .desk import TradingDesk
        from .report import save, scan_payload

        if kind == "monitor":
            desk = TradingDesk(cfg, use_llm=False)
            closed = desk.monitor()
            return {"closed": closed, "status": status_dict(desk.broker)}
        desk = TradingDesk(cfg)
        board = desk.scan()
        orders = desk.execute() if kind == "trade" else None
        save(board, cfg, orders)
        out = scan_payload(board, cfg, orders)
        for t in out["trades"]:  # recent closes so the app can draw a chart
            cand = board.candidates.get(t["symbol"])
            if cand is not None and cand.df is not None:
                t["closes"] = [round(float(x), 4) for x in cand.df["Close"].tail(120)]
        out.update(broker=cfg.execution.broker, mode=cfg.execution.mode, segment=cfg.india.segment)
        return out

    def status(self) -> dict:
        from .brokers import make_broker

        if not self.lock.acquire(blocking=False):
            raise RuntimeError("busy")
        try:
            return status_dict(make_broker(self.config("status")))
        finally:
            self.lock.release()

    def quotes(self, symbols: list[str]) -> dict:
        from .brokers import make_broker
        from .quotes import QuoteService

        if self._quotes is None:  # its own broker session, so quotes never wait for a scan
            try:
                broker = make_broker(self.config("status"))
            except Exception as exc:
                log.warning("live quotes: broker unavailable (%s); using Yahoo", exc)
                broker = None
            self._quotes = QuoteService(broker)
        q = self._quotes
        return {"quotes": q.quotes(symbols), "broker": q.broker.name if q.broker else None}

    def info(self) -> dict:
        cfg = self.config("status")
        return {"version": __version__, "broker": cfg.execution.broker, "mode": cfg.execution.mode,
                "live": self.live, "segment": cfg.india.segment, "preset": cfg.universe.preset,
                "ai": cfg.llm.enabled, "presets": sorted(PRESETS),
                "busy": self.current.id if self.current and self.current.status == "running" else None}


def status_dict(broker) -> dict:
    acct = broker.account()
    positions = broker.positions()
    closed = broker.closed_trades() if hasattr(broker, "closed_trades") else []
    managed = {p["symbol"] for p in positions}
    return {"broker": broker.name, "live": broker.live, "account": acct, "positions": positions,
            "closed": closed[-200:], "held_outside": sorted(broker.held_symbols() - managed)}


def make_handler(server: DeskServer):
    class Handler(BaseHTTPRequestHandler):
        server_version = f"trader/{__version__}"

        def log_message(self, fmt, *args):  # quieter than the default stderr access log
            log.debug("%s %s", self.address_string(), fmt % args)

        def _send(self, code: int, body: dict) -> None:
            data = json.dumps(body, default=str).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(data)

        def do_OPTIONS(self):  # CORS preflight for browser-based clients
            self.send_response(204)
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Access-Control-Allow-Headers", "Authorization, Content-Type")
            self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            self.end_headers()

        def _authorized(self) -> bool:
            got = self.headers.get("Authorization", "")
            ok = got.startswith("Bearer ") and hmac.compare_digest(got[7:].encode(), server.token.encode())
            if not ok:
                self._send(401, {"error": "missing or wrong token"})
            return ok

        def _body(self) -> dict:
            n = int(self.headers.get("Content-Length") or 0)
            if n > MAX_BODY:
                raise ValueError("request too large")
            raw = self.rfile.read(n) if n else b"{}"
            body = json.loads(raw or b"{}")
            if not isinstance(body, dict):
                raise ValueError("expected a JSON object")
            return body

        def do_GET(self):
            url = urlparse(self.path)
            if url.path == "/api/health":
                return self._send(200, {"ok": True, "app": "trader", "version": __version__})
            if not self._authorized():
                return None
            try:
                if url.path == "/api/info":
                    return self._send(200, server.info())
                if url.path == "/api/quotes":
                    raw = (parse_qs(url.query).get("symbols") or [""])[0]
                    symbols = [x for x in raw.split(",") if x]
                    if not symbols or not all(SYMBOL_RE.match(x) for x in symbols):
                        return self._send(400, {"error": "symbols must be comma-separated ticker symbols"})
                    return self._send(200, server.quotes(symbols))
                if url.path == "/api/status":
                    return self._send(200, server.status())
                m = re.fullmatch(r"/api/jobs/([0-9a-f]{12})", url.path)
                if m:
                    job = server.jobs.get(m.group(1))
                    if not job:
                        return self._send(404, {"error": "no such job"})
                    since = int((parse_qs(url.query).get("since") or ["0"])[0])
                    return self._send(200, job.view(since))
                return self._send(404, {"error": "not found"})
            except RuntimeError:
                return self._send(409, {"error": "the desk is busy with another job"})
            except Exception as exc:
                return self._send(500, {"error": f"{type(exc).__name__}: {exc}"})

        def do_POST(self):
            url = urlparse(self.path)
            if not self._authorized():
                return None
            kinds = {"/api/scan": "scan", "/api/trade": "trade", "/api/monitor": "monitor"}
            if url.path not in kinds:
                return self._send(404, {"error": "not found"})
            try:
                body = self._body()
                kind = kinds[url.path]
                if kind == "trade" and server.live and body.get("confirm") != "LIVE":
                    return self._send(400, {"error": 'live server: send {"confirm": "LIVE"} to place real orders'})
                job = server.start(kind, body)
                return self._send(202, {"job": job.id, "kind": kind, "live": server.live})
            except RuntimeError:
                return self._send(409, {"error": "the desk is busy with another job"})
            except (ValueError, json.JSONDecodeError) as exc:
                return self._send(400, {"error": str(exc)})

    return Handler


def add_serve_parser(sub, common) -> None:
    p = sub.add_parser("serve", parents=[common], help="run the JSON API for the phone app")
    p.add_argument("--host", default="0.0.0.0", help="address to listen on (default all interfaces)")
    p.add_argument("--port", type=int, default=8765)
    p.add_argument("--token", help="API token (default: $TRADER_TOKEN or a random one)")
    p.add_argument("--live", action="store_true", help="let the app place real-money orders")
    p.add_argument("--yes", action="store_true", help="skip the live confirmation prompt")


def serve(args) -> int:
    from .cli import confirm_live, load_config

    base: list[str] = []
    if args.config:
        base += ["-c", args.config]
    if args.broker:
        base += ["--broker", args.broker]
    if args.preset:
        base += ["--preset", args.preset]
    if args.segment:
        base += ["--segment", args.segment]
    if args.synthetic:
        base.append("--synthetic")
    if args.no_ai:
        base.append("--no-ai")
    token = args.token or os.environ.get("TRADER_TOKEN") or secrets.token_urlsafe(18)
    if len(token) < 12:
        print("The token must be at least 12 characters.")
        return 2
    desk = DeskServer(base, args.live, token)
    cfg = desk.config("trade" if args.live else "status")  # validates the config before listening
    if args.live and not confirm_live(cfg, args.yes):
        print("Cancelled.")
        return 1
    httpd = ThreadingHTTPServer((args.host, args.port), make_handler(desk))
    mode = "LIVE - the app can place real orders" if args.live else f"{cfg.execution.mode} (no real orders)"
    print(f"Desk server on http://{args.host}:{args.port}  broker {cfg.execution.broker}  mode {mode}")
    print(f"Token: {token}")
    print("In the app: Desk tab -> server address http://<this computer's LAN or VPN IP>:"
          f"{args.port} and the token above. Ctrl+C stops the server.")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        httpd.server_close()
    return 0

