"""Layer 6 - Strategy optimizer & backtester."""
from __future__ import annotations

from ..strategies import SETUPS, expand, optimize
from .base import Agent, clip
from .technical import composite, indicator_votes


class OptimizerAgent(Agent):
    name = "optimizer"
    title = "Strategy Optimizer & Backtester"
    layer = 6
    role = ("Backtests every setup variant against every stop/target/holding-period combination, "
            "picks the best on in-sample data, validates it out of sample, and keeps symbols where "
            "a validated setup is firing right now.")

    def evaluate(self, cand):
        oc = self.cfg.optimizer
        score = cand.cache.get("tech_score")
        if score is None:
            score = composite(indicator_votes(cand.ind))
        variants = optimize(cand.ind, score, cand.direction, oc)
        n_variants = sum(len(expand(g)) for g in SETUPS.values())
        backtests = n_variants * len(oc.stop_atr) * len(oc.reward_risk) * len(oc.max_hold)
        validated = [v for v in variants if v["validated"]]
        firing = [v for v in validated if v["active"]]
        data = {"backtests": backtests, "validated": [v["label"] for v in validated]}
        if not validated:
            why = f"best was {variants[0]['label']}: {variants[0]['why']}" if variants else "too few trades"
            return self.fail(f"no setup survived out-of-sample validation ({why})", **data)
        if not firing:
            names = ", ".join(v["label"] for v in validated[:3])
            return self.fail(f"{len(validated)} validated setup(s) ({names}) but none is firing now", **data)

        best = max(firing, key=lambda v: v["all"]["sqn"])
        cand.cache["setup"] = best
        ins, oos = best["in_sample"], best["out_of_sample"]
        data.update(setup=best["label"], stop_atr=best["stop_atr"], reward_risk=best["reward_risk"],
                    max_hold=best["max_hold"], in_sample=ins, out_of_sample=oos,
                    robustness=best["robustness"])
        quality = (0.4 * clip(oos["expectancy"] / 0.6) + 0.3 * clip((oos["profit_factor"] - 1) / 2)
                   + 0.3 * best["robustness"])
        return self.ok(clip(quality), [
            f"{best['label']} is firing; exits: stop {best['stop_atr']}xATR, target {best['reward_risk']}R, "
            f"max {best['max_hold']} bars (best of {backtests} backtests)",
            f"in-sample {ins['trades']} trades, PF {ins['profit_factor']:.2f}, {ins['expectancy']:+.2f}R/trade",
            f"out-of-sample {oos['trades']} trades, PF {oos['profit_factor']:.2f}, "
            f"{oos['expectancy']:+.2f}R/trade, win rate {oos['win_rate']:.0%}",
            f"profitable across {best['robustness']:.0%} of exit settings",
        ], **data)
