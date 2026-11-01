"""Multi-agent AI trading system.

A universe of symbols is passed through a funnel of specialist agents. Each agent has one job,
publishes its findings on a shared message bus and either lets a candidate through or rejects it
with a reason. Whatever survives every layer becomes a risk-managed trade plan that can be paper
traded or sent to a live broker.
"""

__version__ = "1.0.0"
