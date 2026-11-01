"""Configuration for the multi-agent trading system.

Every setting has a default below. A TOML file (copy ``config.example.toml``) can override any of
them, and a few command-line flags override the file.
"""
from __future__ import annotations

import re
import tomllib
from dataclasses import dataclass, field, fields, is_dataclass
from pathlib import Path

# Ready-made universes. `min_turnover` is the minimum 20-day average of close * volume, in the
# market's own currency.
PRESETS: dict[str, dict] = {
    "us_large_cap": {
        "symbols": [
            "AAPL", "MSFT", "NVDA", "AMZN", "GOOGL", "META", "AVGO", "TSLA", "JPM", "V",
            "MA", "UNH", "LLY", "XOM", "COST", "HD", "PG", "JNJ", "ABBV", "MRK",
            "CRM", "AMD", "NFLX", "ADBE", "ORCL", "PEP", "KO", "BAC", "WMT", "CVX",
        ],
        "benchmark": "SPY",
        "volatility_index": "^VIX",
        "min_turnover": 50e6,
    },
    "us_etfs": {
        "symbols": [
            "SPY", "QQQ", "IWM", "DIA", "XLK", "XLF", "XLE", "XLV", "XLY", "XLI", "XLP",
            "XLU", "XLB", "XLRE", "XLC", "SMH", "GLD", "SLV", "TLT", "HYG", "EEM", "EFA",
        ],
        "benchmark": "SPY",
        "volatility_index": "^VIX",
        "min_turnover": 20e6,
    },
    "nifty50": {
        "symbols": [
            "RELIANCE.NS", "TCS.NS", "HDFCBANK.NS", "ICICIBANK.NS", "INFY.NS", "BHARTIARTL.NS",
            "ITC.NS", "SBIN.NS", "LT.NS", "HINDUNILVR.NS", "BAJFINANCE.NS", "KOTAKBANK.NS",
            "AXISBANK.NS", "MARUTI.NS", "SUNPHARMA.NS", "TITAN.NS", "ULTRACEMCO.NS",
            "ASIANPAINT.NS", "NTPC.NS", "M&M.NS", "POWERGRID.NS", "HCLTECH.NS", "WIPRO.NS",
            "ONGC.NS", "TATASTEEL.NS",
        ],
        "benchmark": "^NSEI",
        "volatility_index": "^INDIAVIX",
        "min_turnover": 500e6,  # INR
    },
    "crypto": {
        "symbols": [
            "BTC-USD", "ETH-USD", "SOL-USD", "BNB-USD", "XRP-USD", "ADA-USD", "DOGE-USD",
            "AVAX-USD", "LINK-USD", "DOT-USD", "LTC-USD", "TRX-USD",
        ],
        "benchmark": "BTC-USD",
        "volatility_index": "",
        "min_turnover": 50e6,
    },
}

_CRYPTO = re.compile(r"-(USD|USDT|USDC|EUR|GBP|INR|JPY|BTC|ETH)$")


def is_crypto(symbol: str) -> bool:
    return "/" in symbol or bool(_CRYPTO.search(symbol.upper()))


def is_equity_like(symbol: str) -> bool:
    """True for symbols that can have company fundamentals (not crypto, FX, futures or indices)."""
    s = symbol.upper()
    return not (is_crypto(s) or s.startswith("^") or s.endswith("=X") or s.endswith("=F"))


@dataclass
class UniverseConfig:
    preset: str = "us_large_cap"
    symbols: list = field(default_factory=list)  # non-empty -> replaces the preset's list
    benchmark: str = ""                          # "" -> the preset's benchmark
    volatility_index: str = ""                   # "" -> the preset's; "none" disables it
    history_years: int = 6


@dataclass
class DataConfig:
    source: str = "yahoo"      # "yahoo" or "synthetic" (offline demo data, never traded live)
    cache_dir: str = ".cache"
    cache_hours: float = 12.0


@dataclass
class LiquidityConfig:
    min_bars: int = 500           # history needed for the 200-day averages, backtests and ML
    min_price: float = 2.0
    min_turnover: float = 0.0     # 0 -> the preset's value
    max_stale_days: int = 7
    max_daily_move: float = 0.6   # a bigger one-bar move in the last year suggests bad data


@dataclass
class RegimeConfig:
    allow_short: bool = False
    halt_on_crisis: bool = True
    high_vol_index: float = 28.0   # volatility-index level (e.g. VIX) that counts as high
    crisis_vol_index: float = 40.0
    bear_longs_need_rs: bool = True  # in a bear market a long must be beating the benchmark


@dataclass
class TechnicalConfig:
    min_score: float = 0.10   # net indicator confluence in the trade's direction (-1..1)


@dataclass
class TimeframeConfig:
    min_agreement: float = 0.6   # share of weekly/monthly trend votes that must agree


@dataclass
class PriceActionConfig:
    min_room_atr: float = 1.0       # distance to the next resistance (support for shorts) in ATRs
    max_extension_atr: float = 3.0  # distance from the 20-day EMA beyond which we'd be chasing


@dataclass
class OptimizerConfig:
    in_sample_frac: float = 0.7
    stop_atr: list = field(default_factory=lambda: [1.0, 1.5, 2.0, 2.5])
    reward_risk: list = field(default_factory=lambda: [1.5, 2.0, 3.0])
    max_hold: list = field(default_factory=lambda: [5, 10, 20])
    fee_pct: float = 0.0005          # per side: commission + slippage
    min_trades_is: int = 15
    min_trades_oos: int = 5
    min_expectancy_oos: float = 0.05  # average R per trade out of sample
    min_profit_factor_oos: float = 1.15
    max_drawdown_r: float = 12.0
    min_robustness: float = 0.5       # share of exit settings that are profitable in sample
    signal_freshness: int = 1         # the setup must have fired within the last N bars


@dataclass
class MLConfig:
    models: list = field(default_factory=lambda: ["gbm", "forest", "logit"])
    n_splits: int = 4
    min_train: int = 400
    min_auc: float = 0.52     # out-of-sample ranking skill the model must show
    min_edge: float = 0.02    # probability needed above the break-even win rate


@dataclass
class NewsConfig:
    enabled: bool = True
    max_headlines: int = 12
    max_age_days: int = 10
    veto_below: float = -0.4  # sentiment against the trade below this vetoes it


@dataclass
class FundamentalConfig:
    enabled: bool = True
    earnings_blackout_days: int = 5
    min_quality: float = 0.25   # equities below this quality score are not bought


@dataclass
class RiskConfig:
    account_equity: float = 0.0     # 0 -> ask the broker
    risk_per_trade: float = 0.01    # share of equity lost if the stop is hit
    max_position_pct: float = 0.25
    max_open_positions: int = 5
    max_portfolio_heat: float = 0.06  # total open risk across positions
    min_reward_risk: float = 1.5
    max_correlation: float = 0.8
    fractional: bool = False        # fractional quantities (always on for crypto)


@dataclass
class DecisionConfig:
    top_n: int = 3
    min_conviction: float = 0.55
    weights: dict = field(default_factory=lambda: {
        "regime": 0.05, "technical": 0.15, "timeframe": 0.10, "price_action": 0.10,
        "optimizer": 0.25, "ml": 0.20, "sentiment": 0.075, "fundamental": 0.075,
    })


@dataclass
class LLMConfig:
    enabled: str = "auto"        # "auto" (on when Claude credentials are found), "on" or "off"
    model: str = "claude-opus-5-5"
    review_effort: str = "high"  # low | medium | high | xhigh | max
    sentiment_effort: str = "low"
    required: bool = False       # True -> a trade the AI reviewer could not check is dropped


@dataclass
class ExecutionConfig:
    mode: str = "paper"            # "paper" or "live"
    broker: str = "paper"          # "paper" (built-in simulator), "alpaca" or "ccxt"
    paper_equity: float = 100_000.0
    slippage_pct: float = 0.0005
    state_dir: str = "state"
    reports_dir: str = "reports"
    max_order_value: float = 25_000.0
    max_daily_loss_pct: float = 0.03   # breach -> kill switch, no new orders
    trail_to_breakeven: bool = True    # move the stop to entry once a trade is 1R in profit
    ccxt_exchange: str = "binance"
    ccxt_quote: str = "USDT"


@dataclass
class Config:
    universe: UniverseConfig = field(default_factory=UniverseConfig)
    data: DataConfig = field(default_factory=DataConfig)
    liquidity: LiquidityConfig = field(default_factory=LiquidityConfig)
    regime: RegimeConfig = field(default_factory=RegimeConfig)
    technical: TechnicalConfig = field(default_factory=TechnicalConfig)
    timeframe: TimeframeConfig = field(default_factory=TimeframeConfig)
    price_action: PriceActionConfig = field(default_factory=PriceActionConfig)
    optimizer: OptimizerConfig = field(default_factory=OptimizerConfig)
    ml: MLConfig = field(default_factory=MLConfig)
    news: NewsConfig = field(default_factory=NewsConfig)
    fundamental: FundamentalConfig = field(default_factory=FundamentalConfig)
    risk: RiskConfig = field(default_factory=RiskConfig)
    decision: DecisionConfig = field(default_factory=DecisionConfig)
    llm: LLMConfig = field(default_factory=LLMConfig)
    execution: ExecutionConfig = field(default_factory=ExecutionConfig)

    @classmethod
    def load(cls, path: str | Path | None = None) -> "Config":
        cfg = cls()
        if path:
            with open(path, "rb") as fh:
                _merge(cfg, tomllib.load(fh))
        return cfg

    def finalize(self) -> "Config":
        """Fill preset values and validate. Call after all overrides are applied."""
        u = self.universe
        preset = PRESETS.get(u.preset)
        if preset is None and not u.symbols:
            raise ValueError(f"Unknown preset '{u.preset}'. Choose one of {sorted(PRESETS)} or list symbols.")
        if preset:
            u.symbols = list(u.symbols) or list(preset["symbols"])
            u.benchmark = u.benchmark or preset["benchmark"]
            u.volatility_index = u.volatility_index or preset["volatility_index"]
            self.liquidity.min_turnover = self.liquidity.min_turnover or preset["min_turnover"]
        u.benchmark = u.benchmark or "SPY"
        if u.volatility_index.lower() == "none":
            u.volatility_index = ""
        u.symbols = list(dict.fromkeys(s.strip().upper() for s in u.symbols if s.strip()))

        ex = self.execution
        _check(ex.mode in ("paper", "live"), "execution.mode must be 'paper' or 'live'")
        _check(ex.broker in ("paper", "alpaca", "ccxt"), "execution.broker must be paper, alpaca or ccxt")
        _check(not (ex.mode == "live" and ex.broker == "paper"),
               "Live mode needs a real broker: set execution.broker to 'alpaca' or 'ccxt'")
        _check(self.data.source in ("yahoo", "synthetic"), "data.source must be 'yahoo' or 'synthetic'")
        _check(not (ex.mode == "live" and self.data.source == "synthetic"),
               "Refusing live trading on synthetic data")
        _check(0 < self.risk.risk_per_trade <= 0.05, "risk.risk_per_trade must be in (0, 0.05]")
        _check(0 < self.risk.max_position_pct <= 1, "risk.max_position_pct must be in (0, 1]")
        _check(self.llm.enabled in ("auto", "on", "off"), "llm.enabled must be auto, on or off")
        _check(0.3 <= self.optimizer.in_sample_frac <= 0.9, "optimizer.in_sample_frac must be in [0.3, 0.9]")
        return self


def _check(ok: bool, message: str) -> None:
    if not ok:
        raise ValueError(message)


def _merge(obj, overrides: dict, path: str = "") -> None:
    known = {f.name for f in fields(obj)}
    for key, value in overrides.items():
        if key not in known:
            raise ValueError(f"Unknown config key '{path}{key}'")
        current = getattr(obj, key)
        if is_dataclass(current):
            if not isinstance(value, dict):
                raise ValueError(f"'{path}{key}' must be a table")
            _merge(current, value, f"{path}{key}.")
        elif isinstance(current, dict) and isinstance(value, dict):
            setattr(obj, key, {**current, **value})
        else:
            setattr(obj, key, value)
