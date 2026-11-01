"""Layer 10 - Risk manager."""
from __future__ import annotations

import math

from ..config import is_crypto
from .base import Agent, clip


def round_price(x: float) -> float:
    return round(x, 2) if x >= 1 else round(x, 6)


def position_risk(p: dict, equity: float, rc) -> float:
    """Money lost if an open position hits its stop (a full risk unit when the stop is unknown)."""
    entry, stop, qty = p.get("entry"), p.get("stop"), p.get("qty")
    if entry and stop and qty:
        return max(0.0, (entry - stop) * qty if p.get("side", "long") == "long" else (stop - entry) * qty)
    return equity * rc.risk_per_trade


class RiskAgent(Agent):
    name = "risk"
    title = "Risk Manager"
    layer = 10
    role = ("Turns each surviving setup into an exact plan - entry, ATR stop, target and a position "
            "size from the risk budget - and enforces position, exposure and portfolio-heat limits.")

    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.risk_multiplier = 1.0
        self.bus.subscribe("market.regime", self._on_regime)  # smaller size in volatile markets

    def _on_regime(self, msg):
        self.risk_multiplier = float(msg.payload.get("risk_multiplier", 1.0))

    def prepare(self):
        rc = self.cfg.risk
        account = self.board.context.get("account") or {}
        self.equity = rc.account_equity or float(account.get("equity") or 0)
        self.cash = float(account.get("cash", self.equity) or 0)
        self.positions = self.board.context.get("positions") or []
        self.held = {p["symbol"] for p in self.positions}
        self.outside = set(self.board.context.get("held_symbols") or ()) - self.held
        self.open_risk = sum(position_risk(p, self.equity, rc) for p in self.positions)
        self.board.context["open_risk"] = self.open_risk

    def evaluate(self, cand):
        rc = self.cfg.risk
        if self.equity <= 0:
            return self.fail("account equity is unknown or zero")
        if cand.symbol in self.held:
            return self.fail("a position or order is already open")
        if cand.symbol in self.outside:
            return self.fail("you already hold this share outside the desk; not mixing the two")
        if len(self.positions) >= rc.max_open_positions:
            return self.fail(f"{len(self.positions)} positions open (max {rc.max_open_positions})")
        setup = cand.cache["setup"]
        d = cand.direction
        price, atr = float(cand.ind["close"].iloc[-1]), float(cand.ind["atr"].iloc[-1])
        stop_dist, rr = setup["stop_atr"] * atr, setup["reward_risk"]
        if rr < rc.min_reward_risk:
            return self.fail(f"reward:risk {rr} below the {rc.min_reward_risk} minimum")
        stop, target = price - d * stop_dist, price + d * rr * stop_dist
        if stop <= 0:
            return self.fail("stop would be at or below zero")
        heat_left = self.equity * rc.max_portfolio_heat - self.open_risk
        if heat_left <= 0:
            return self.fail("portfolio heat limit reached")

        budget = min(self.equity * rc.risk_per_trade * self.risk_multiplier, heat_left)
        caps = {"risk budget": budget / stop_dist,
                "max position size": self.equity * rc.max_position_pct / price,
                "max order value": self.cfg.execution.max_order_value / price}
        if d > 0:
            caps["available cash"] = max(self.cash, 0.0) / price
        limiter = min(caps, key=caps.get)
        qty = caps[limiter]
        qty = math.floor(qty * 1e6) / 1e6 if (rc.fractional or is_crypto(cand.symbol)) else float(math.floor(qty))
        if qty <= 0:
            return self.fail(f"position size rounds to zero (limited by {limiter})")

        risk_amount = qty * stop_dist
        cand.plan = {
            "symbol": cand.symbol, "side": cand.side, "qty": qty, "entry_ref": round_price(price),
            "stop": round_price(stop), "target": round_price(target), "atr": atr,
            "stop_atr": setup["stop_atr"], "reward_risk": rr, "max_hold": setup["max_hold"],
            "setup": setup["label"], "risk_amount": risk_amount, "risk_pct": risk_amount / self.equity,
            "notional": qty * price, "limited_by": limiter,
            "signal_date": cand.df.index[-1].date().isoformat(),
        }
        atr_pct = atr / price
        score = 0.5 * clip(rr / 3) + 0.5 * clip(1 - atr_pct / 0.06)
        return self.ok(score, [
            f"{cand.side} {qty:g} @ ~{price:.2f}, stop {stop:.2f}, target {target:.2f} ({rr}R)",
            f"risk {risk_amount:,.0f} = {risk_amount / self.equity:.2%} of equity "
            f"(size limited by {limiter}, regime risk x{self.risk_multiplier:.2f})",
            f"position value {qty * price:,.0f} ({qty * price / self.equity:.0%} of equity)",
        ], **cand.plan)
