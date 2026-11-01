"""Layer 11 - Chief decision maker."""
from __future__ import annotations

import pandas as pd

from .base import Agent
from .risk import position_risk


class DecisionAgent(Agent):
    name = "decision"
    title = "Chief Decision Maker"
    layer = 11
    role = ("Weighs every agent's score into one conviction, combines the backtest and ML edge into "
            "an expected value, ranks the survivors and keeps the best few that are not too "
            "correlated with each other or with open positions.")

    def evaluate(self, cand):
        dc = self.cfg.decision
        num = den = 0.0
        contributions = {}
        for agent, weight in dc.weights.items():
            rep = cand.reports.get(agent)
            if rep is None or rep.score is None or weight <= 0:
                continue
            num += weight * rep.score
            den += weight
            contributions[agent] = round(rep.score, 3)
        conviction = num / den if den else 0.0
        oos_ev = cand.cache["setup"]["out_of_sample"]["expectancy"]
        ml_ev = cand.reports["ml"].data.get("expected_r") if "ml" in cand.reports else None
        ev = oos_ev if ml_ev is None else 0.5 * oos_ev + 0.5 * ml_ev
        cand.cache.update(conviction=conviction, expected_r=ev)
        data = {"conviction": conviction, "expected_r": ev, "rank_score": conviction * ev,
                "contributions": contributions}
        if conviction < dc.min_conviction:
            return self.fail(f"conviction {conviction:.2f} below {dc.min_conviction}", score=conviction, **data)
        if ev <= 0:
            return self.fail(f"expected value {ev:+.2f}R is not positive", score=conviction, **data)
        return self.ok(conviction, [f"conviction {conviction:.2f} across {len(contributions)} agents",
                                    f"expected value {ev:+.2f}R per trade"], **data)

    def finalize(self, alive):
        dc, rc = self.cfg.decision, self.cfg.risk
        ranked = sorted(alive, key=lambda c: c.cache["conviction"] * c.cache["expected_r"], reverse=True)
        positions = self.board.context.get("positions") or []
        account = self.board.context.get("account") or {}
        equity = rc.account_equity or float(account.get("equity") or 0)
        heat = sum(position_risk(p, equity, rc) for p in positions)
        heat_limit = equity * rc.max_portfolio_heat
        # cash not yet spent but already promised to pending buy orders
        cash_left = float(account.get("cash", equity) or 0) - sum(
            p.get("qty", 0) * (p.get("last_price") or p.get("entry") or p.get("entry_ref") or 0)
            for p in positions if p.get("status") in ("pending", "queued") and p.get("side", "long") == "long")
        slots = min(dc.top_n, rc.max_open_positions - len(positions))
        held = [p["symbol"] for p in positions]
        returns = self._returns(ranked, held)
        chosen = []
        for cand in ranked:
            if len(chosen) >= slots:
                self.reject(cand, "no free position slots" if slots <= 0 else f"ranked below the top {slots}")
                continue
            clash = None
            for other in [c.symbol for c in chosen] + held:
                rho = _corr(returns.get(cand.symbol), returns.get(other))
                if rho is not None and rho > rc.max_correlation:
                    clash = (other, rho)
                    break
            if clash:
                self.reject(cand, f"{clash[1]:.2f} correlated with {clash[0]} (max {rc.max_correlation})")
                continue
            if heat + cand.plan["risk_amount"] > heat_limit + 1e-9:
                self.reject(cand, "would push total open risk over the portfolio heat limit")
                continue
            if cand.side == "long" and cand.plan["notional"] > cash_left + 1e-9:
                self.reject(cand, "not enough free cash left after the higher-ranked picks")
                continue
            heat += cand.plan["risk_amount"]
            if cand.side == "long":
                cash_left -= cand.plan["notional"]
            chosen.append(cand)
        for rank, cand in enumerate(chosen, 1):
            cand.cache["rank"] = rank
            cand.reports[self.name].data["rank"] = rank
        self.bus.publish("decision.final", self.name, trades=[
            {"rank": c.cache["rank"], "symbol": c.symbol, "side": c.side, "setup": c.plan["setup"],
             "conviction": round(c.cache["conviction"], 3), "expected_r": round(c.cache["expected_r"], 3)}
            for c in chosen])

    def _returns(self, ranked, held) -> dict[str, pd.Series]:
        out = {c.symbol: c.df["Close"].pct_change().tail(120) for c in ranked}
        missing = [s for s in held if s not in out]
        if missing and "data" in self.services:
            try:
                for s, df in self.services["data"].get(missing).items():
                    out[s] = df["Close"].pct_change().tail(120)
            except Exception as exc:
                self.log.debug("could not load held symbols for correlation: %s", exc)
        return out


def _corr(a: pd.Series | None, b: pd.Series | None) -> float | None:
    if a is None or b is None:
        return None
    joined = pd.concat([a, b], axis=1, join="inner").dropna()
    if len(joined) < 30:
        return None
    rho = joined.iloc[:, 0].corr(joined.iloc[:, 1])
    return None if pd.isna(rho) else float(rho)
