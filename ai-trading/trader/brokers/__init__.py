"""Broker adapters. ``make_broker`` picks one from the configuration."""
from __future__ import annotations

from .base import Broker, BrokerError
from .paper import PaperBroker


def make_broker(cfg) -> Broker:
    ex = cfg.execution
    if ex.broker == "paper":
        return PaperBroker(ex.state_dir, ex.paper_equity, cfg.optimizer.fee_pct, ex.slippage_pct)
    if ex.broker == "alpaca":
        from .alpaca import AlpacaBroker

        return AlpacaBroker(paper=ex.mode != "live", state_dir=ex.state_dir)
    if ex.broker == "ccxt":
        from .ccxt_broker import CCXTBroker

        return CCXTBroker(ex.ccxt_exchange, sandbox=ex.mode != "live", quote=ex.ccxt_quote,
                          state_dir=ex.state_dir)
    raise BrokerError(f"unknown broker '{ex.broker}'")


__all__ = ["Broker", "BrokerError", "PaperBroker", "make_broker"]
