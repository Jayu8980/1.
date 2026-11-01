"""Broker adapters. ``make_broker`` picks one from the configuration."""
from __future__ import annotations

from .base import Broker, BrokerError
from .paper import PaperBroker

INDIAN_BROKERS = ("zerodha", "angelone", "dhan", "groww")
BROKERS = ("paper", "alpaca", "ccxt") + INDIAN_BROKERS


def make_broker(cfg) -> Broker:
    ex = cfg.execution
    live = ex.mode == "live"
    if ex.broker == "paper":
        return PaperBroker(ex.state_dir, ex.paper_equity, cfg.optimizer.fee_pct, ex.slippage_pct)
    if ex.broker == "alpaca":
        from .alpaca import AlpacaBroker

        return AlpacaBroker(paper=not live, state_dir=ex.state_dir)
    if ex.broker == "ccxt":
        from .ccxt_broker import CCXTBroker

        return CCXTBroker(ex.ccxt_exchange, sandbox=not live, quote=ex.ccxt_quote, state_dir=ex.state_dir)
    # Indian brokers have no paper environment: without --live they run as a dry run against
    # the real account (read-only) and report the orders they would have sent.
    if ex.broker == "zerodha":
        from .zerodha import ZerodhaBroker

        return ZerodhaBroker(ex, live, cfg.india)
    if ex.broker == "angelone":
        from .angelone import AngelOneBroker

        return AngelOneBroker(ex, live, cfg.india)
    if ex.broker == "dhan":
        from .dhan import DhanBroker

        return DhanBroker(ex, live, cfg.india)
    if ex.broker == "groww":
        from .groww import GrowwBroker

        return GrowwBroker(ex, live, cfg.india)
    raise BrokerError(f"unknown broker '{ex.broker}'")


__all__ = ["BROKERS", "INDIAN_BROKERS", "Broker", "BrokerError", "PaperBroker", "make_broker"]
