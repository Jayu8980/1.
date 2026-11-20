"""Strategy library (diversified, low-correlation). Registry used by the CLI/API."""
from .equity import CrossSectionalMomentum, MeanReversionRSI2, TrendFollowing, EarningsDrift
from .intraday import OpeningRangeBreakout
from .options_income import IronCondorIncome
from .stat_arb import PairsStatArb
from .crypto import FundingCarry, CryptoTrend, BasisTrade
from .ml_ensemble import MLEnsemble

REGISTRY = {c.name: c for c in (CrossSectionalMomentum, TrendFollowing, MeanReversionRSI2, OpeningRangeBreakout, IronCondorIncome,
                                PairsStatArb, EarningsDrift, FundingCarry, BasisTrade, CryptoTrend, MLEnsemble)}
