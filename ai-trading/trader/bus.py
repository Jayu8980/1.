"""How the agents talk to each other.

* ``MessageBus`` - publish/subscribe channel. Agents broadcast findings (market regime, rejections,
  final decisions, orders) and other agents subscribe to the topics they care about.
* ``Blackboard`` - shared memory holding the market context and one dossier (``Candidate``) per
  symbol. Every agent appends a ``Report`` to the dossier, so later layers can read what earlier
  layers concluded.
"""
from __future__ import annotations

import time
from collections import defaultdict
from dataclasses import dataclass, field
from typing import Any, Callable

import pandas as pd


@dataclass
class Message:
    topic: str
    sender: str
    payload: dict
    ts: float = field(default_factory=time.time)


class MessageBus:
    def __init__(self) -> None:
        self._subscribers: dict[str, list[Callable[[Message], None]]] = defaultdict(list)
        self.history: list[Message] = []

    def subscribe(self, topic: str, handler: Callable[[Message], None]) -> None:
        """Subscribe to one topic, or to every message with topic ``"*"``."""
        self._subscribers[topic].append(handler)

    def publish(self, topic: str, sender: str, **payload: Any) -> Message:
        msg = Message(topic, sender, payload)
        self.history.append(msg)
        for handler in self._subscribers.get(topic, []) + self._subscribers.get("*", []):
            handler(msg)
        return msg


@dataclass
class Report:
    """One agent's verdict on one candidate."""

    agent: str
    passed: bool
    score: float | None = 0.5      # 0..1, how favourable this agent finds the trade (0.5 = neutral)
    reasons: list[str] = field(default_factory=list)
    veto: str = ""                 # why the candidate was rejected, when passed is False
    data: dict = field(default_factory=dict)

    def summary(self) -> str:
        if not self.passed:
            return self.veto or "rejected"
        return "; ".join(self.reasons[:3])


@dataclass
class Candidate:
    """The dossier the agents build up for one symbol."""

    symbol: str
    df: pd.DataFrame | None
    bench_close: pd.Series | None = None
    side: str | None = None                 # "long" / "short", set by the regime agent
    reports: dict[str, Report] = field(default_factory=dict)
    rejected_by: str | None = None
    rejection: str = ""
    plan: dict = field(default_factory=dict)   # final trade plan, filled by the risk agent
    cache: dict = field(default_factory=dict)  # intermediate results shared between agents
    _ind: pd.DataFrame | None = field(default=None, repr=False)

    @property
    def alive(self) -> bool:
        return self.rejected_by is None

    @property
    def direction(self) -> int:
        return -1 if self.side == "short" else 1

    @property
    def ind(self) -> pd.DataFrame:
        """All indicators for this symbol, computed once on first use."""
        if self._ind is None:
            from .indicators import compute_all

            self._ind = compute_all(self.df, self.bench_close)
        return self._ind


class Blackboard:
    def __init__(self) -> None:
        self.context: dict[str, Any] = {}
        self.candidates: dict[str, Candidate] = {}
        self.funnel: list[dict] = []

    def alive(self) -> list[Candidate]:
        return [c for c in self.candidates.values() if c.alive]

    def rejected(self) -> list[Candidate]:
        return [c for c in self.candidates.values() if not c.alive]
