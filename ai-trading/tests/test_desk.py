"""End-to-end runs of the agent funnel on synthetic data."""
import copy
import json
from pathlib import Path

import pytest

from trader.agents.ai_review import AIReviewAgent
from trader.bus import Blackboard, MessageBus
from trader.cli import main
from trader.config import Config
from trader.desk import TradingDesk
from trader.report import render, save


class FakeClaude:
    def __init__(self, approve: bool):
        self.approve = approve
        self.calls = []

    def json_call(self, system, user, schema, effort="high", max_tokens=16000):
        self.calls.append(json.loads(user))
        return {"approve": self.approve, "confidence": 0.8, "bull_case": "trend and edge agree",
                "bear_case": "small sample", "key_risks": ["gap risk"], "suggested_changes": "",
                "verdict": "approve" if self.approve else "reject"}


def make_cfg(tmp_path, n, relaxed=False):
    cfg = Config()
    cfg.data.source = "synthetic"
    cfg.llm.enabled = "off"
    cfg.universe.symbols = [f"SYN{i:03d}" for i in range(n)]
    cfg.execution.state_dir = str(tmp_path / "state")
    cfg.execution.reports_dir = str(tmp_path / "reports")
    cfg.ml.models = ["gbm", "logit"]
    if relaxed:  # loosen the filters so candidates reach the later layers
        cfg.regime.allow_short = True
        cfg.price_action.min_room_atr = 0.0
        cfg.optimizer.signal_freshness = 10
        cfg.ml.min_auc = 0.0
        cfg.ml.min_edge = -1.0
        cfg.decision.min_conviction = 0.0
    return cfg.finalize()


@pytest.fixture(scope="module")
def relaxed(tmp_path_factory):
    cfg = make_cfg(tmp_path_factory.mktemp("relaxed"), n=40, relaxed=True)
    desk = TradingDesk(cfg, use_llm=False)
    claude = FakeClaude(approve=True)
    desk.services["llm"] = claude
    board = desk.scan()
    return cfg, desk, board, claude


def test_funnel_only_narrows_and_explains_every_rejection(tmp_path):
    desk = TradingDesk(make_cfg(tmp_path, n=20))
    heard = []
    desk.bus.subscribe("market.regime", lambda msg: heard.append(msg.payload["regime"]))
    board = desk.scan()
    passed = [row["passed"] for row in board.funnel]
    assert passed == sorted(passed, reverse=True)
    assert [row["layer"] for row in board.funnel] == list(range(1, 13))
    assert all(c.rejection and c.rejected_by for c in board.rejected())
    # the regime broadcast reached the technical analyst, which tightened its threshold
    technical = next(a for a in desk.agents if a.name == "technical")
    assert heard == [board.context["regime"]["regime"]]
    assert technical.boost == board.context["regime"]["threshold_boost"]


def test_final_plans_are_consistent(relaxed):
    cfg, _, board, _ = relaxed
    trades = board.alive()
    assert 1 <= len(trades) <= cfg.decision.top_n
    for cand in trades:
        p = cand.plan
        d = 1 if p["side"] == "long" else -1
        assert d * (p["entry_ref"] - p["stop"]) > 0 and d * (p["target"] - p["entry_ref"]) > 0
        assert p["qty"] > 0
        assert p["risk_pct"] <= cfg.risk.risk_per_trade + 1e-9
        assert p["notional"] <= cfg.execution.max_order_value + 1e-6
        assert cand.cache["expected_r"] > 0
    ranks = sorted(c.cache["rank"] for c in trades)
    assert ranks == list(range(1, len(trades) + 1))


def test_ai_reviewer_reads_every_agents_report(relaxed):
    _, _, board, claude = relaxed
    assert len(claude.calls) == len(board.alive())
    dossier = claude.calls[0]
    assert {"trade_plan", "agent_reports", "market_regime"} <= set(dossier)
    assert {"technical", "optimizer", "ml", "risk", "decision"} <= set(dossier["agent_reports"])


def test_ai_reviewer_can_veto(relaxed):
    cfg, _, board, _ = relaxed
    review_board = Blackboard()
    review_board.context = board.context
    for cand in board.alive():
        clone = copy.copy(cand)
        clone.reports = dict(cand.reports)
        review_board.candidates[cand.symbol] = clone
    AIReviewAgent(cfg, MessageBus(), review_board, {"llm": FakeClaude(approve=False)}).run()
    assert review_board.alive() == []
    assert all(c.rejected_by == "ai_review" for c in review_board.candidates.values())


def test_reports_are_written(relaxed):
    cfg, _, board, _ = relaxed
    text = render(board, cfg)
    assert "FINAL TRADES" in text and "FILTRATION FUNNEL" in text
    json_path, md_path = save(board, cfg)
    data = json.loads(Path(json_path).read_text())
    assert len(data["trades"]) == len(board.alive()) and Path(md_path).exists()


def test_execution_places_orders_once(relaxed):
    cfg, desk, board, _ = relaxed
    orders = desk.execute()
    assert [o["status"] for o in orders] == ["pending"] * len(board.alive())
    assert {p["symbol"] for p in desk.broker.positions()} == {c.symbol for c in board.alive()}
    again = TradingDesk(cfg, use_llm=False).scan()  # same state dir: orders are now open
    assert not {c.symbol for c in again.alive()} & {c.symbol for c in board.alive()}


def test_kill_switch_blocks_new_orders(tmp_path):
    cfg = make_cfg(tmp_path, n=40, relaxed=True)
    Path(cfg.execution.state_dir).mkdir(parents=True)
    (Path(cfg.execution.state_dir) / "KILL_SWITCH").write_text("stop")
    desk = TradingDesk(cfg, use_llm=False)
    desk.scan()
    orders = desk.execute()
    assert orders and all(o["status"] == "blocked" for o in orders)
    assert desk.broker.positions() == []


def test_an_agent_that_crashes_fails_closed(tmp_path, monkeypatch):
    from trader.agents.regime import RegimeAgent

    def broken(self):
        raise RuntimeError("benchmark feed corrupted")

    monkeypatch.setattr(RegimeAgent, "prepare", broken)
    board = TradingDesk(make_cfg(tmp_path, n=6), use_llm=False).scan()
    assert board.alive() == []
    regime_row = next(row for row in board.funnel if row["agent"] == "regime")
    assert regime_row["passed"] == 0 and "benchmark feed corrupted" in regime_row["rejected"][0][1]


def test_cli_commands_run(tmp_path, monkeypatch, capsys):
    monkeypatch.chdir(tmp_path)
    assert main(["agents"]) == 0
    assert "Strategy Optimizer" in capsys.readouterr().out
    assert main(["scan", "--synthetic", "--no-ai", "--symbols", "SYN001,SYN002,SYN003"]) == 0
    assert "FILTRATION FUNNEL" in capsys.readouterr().out
    assert main(["status", "--synthetic"]) == 0


def test_claude_client_request_shape_and_refusal(monkeypatch):
    pytest.importorskip("anthropic")
    monkeypatch.setenv("ANTHROPIC_API_KEY", "test-key")
    from trader.llm import ClaudeClient

    sent = {}

    class Block:
        type = "text"
        text = '{"approve": true}'

    class Response:
        stop_reason = "end_turn"
        content = [Block()]

    class Messages:
        def create(self, **kwargs):
            sent.update(kwargs)
            return Response()

    class Beta:
        messages = Messages()

    class FakeAnthropic:
        beta = Beta()
        messages = Messages()

    client = ClaudeClient("claude-opus-5-5")
    client.client = FakeAnthropic()
    assert client.json_call("system", "user", {"type": "object"}, effort="low") == {"approve": True}
    assert sent["model"] == "claude-opus-5-5" and sent["fallbacks"] == "default"
    assert sent["betas"] == ["server-side-fallback-2026-07-01"]
    assert sent["output_config"] == {"effort": "low", "format": {"type": "json_schema", "schema": {"type": "object"}}}
    Response.stop_reason = "refusal"
    assert client.json_call("system", "user", {"type": "object"}) is None
