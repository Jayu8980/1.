"""The specialist agents, in funnel order."""
from .ai_review import AIReviewAgent
from .decision import DecisionAgent
from .execution import ExecutionAgent
from .fundamental import FundamentalAgent
from .liquidity import LiquidityAgent
from .ml import MLAgent
from .monitor import MonitorAgent
from .optimizer import OptimizerAgent
from .price_action import PriceActionAgent
from .regime import RegimeAgent
from .risk import RiskAgent
from .sentiment import SentimentAgent
from .technical import TechnicalAgent
from .timeframe import TimeframeAgent

# Cheap, broad filters first; expensive analysis (backtests, ML, news, Claude) only on survivors.
FUNNEL = [
    LiquidityAgent,     # L1  data quality, history, price, turnover
    RegimeAgent,        # L2  market regime -> broadcast; trade direction per symbol
    TechnicalAgent,     # L3  19-indicator confluence
    TimeframeAgent,     # L4  weekly / monthly trend agreement
    PriceActionAgent,   # L5  support/resistance room, extension, candles
    OptimizerAgent,     # L6  setup x parameter grid, out-of-sample validation, setup firing now
    MLAgent,            # L7  ensemble probability for this exact trade
    SentimentAgent,     # L8  news flow
    FundamentalAgent,   # L9  business quality, earnings blackout
    RiskAgent,          # L10 exact plan and position size
    DecisionAgent,      # L11 conviction, expected value, ranking, correlation
    AIReviewAgent,      # L12 Claude's final review
]

ALL_AGENTS = FUNNEL + [ExecutionAgent, MonitorAgent]

__all__ = ["FUNNEL", "ALL_AGENTS", "ExecutionAgent", "MonitorAgent"]
