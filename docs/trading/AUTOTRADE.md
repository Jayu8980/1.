# Auto-trading

Two independent switches on the app's Home screen:

| Switch | ON requires | OFF |
|---|---|---|
| **LIVE trading** | typed phrase + biometric + server `ENGINE_ALLOW_LIVE=true` + static IP + connected broker | confirm; drops to PAPER |
| **Auto-trade** | biometric; kill switch must be released | instant, no biometric — stops new entries, open trades stay protected |

## Where orders go
| Strategy stage | Engine PAPER | Engine LIVE |
|---|---|---|
| PAPER | paper | **paper** |
| LIVE_10 | paper | live, 10% of its allocation |
| LIVE | paper | live, full allocation |

Promotion (app → Strategies → Promote, biometric): PAPER → LIVE_10 needs ≥28 days in PAPER,
≥30 closed paper trades and positive expectancy; LIVE_10 → LIVE needs ≥28 days, ≥15 live trades
and live expectancy ≥50% of paper. Live drawdown >1.5× paper max, or rolling 20-trade live Sharpe
< 0, auto-disables the strategy. "Back to PAPER" is always one tap.

## Pipeline per 5-minute bar
1. Regime (efficiency ratio + ATR%): TREND / RANGE / HIGH_VOL / UNCERTAIN — sit out UNCERTAIN.
2. Strategies: `trend_momentum` (EMA20/50 cross, TREND), `mean_reversion` (RSI(2) extreme, RANGE).
3. Confidence ≥ `ENGINE_AUTO_MIN_CONFIDENCE` (heuristic until the Phase-3 meta-model).
4. Size = min(max-risk-per-trade, ¼-Kelly) of equity ÷ stop distance; capped by strategy
   allocation and max position %. Lot-rounded.
5. Cost filter: expected edge − (brokerage, STT, exchange, SEBI, stamp, GST, slippage) must be > 0
   and ≥ 5 bps of notional.
6. Gateway: kill switch → risk veto (daily loss, drawdown, position size) → static IP (LIVE) → broker.
7. Entry: MARKET on paper; marketable LIMIT (±`ENGINE_LIVE_MARKET_PROTECTION_PCT`) live.
8. On fill: protective **SL-limit stop at the broker**. The kill switch never cancels these.
9. Target hit → book half, stop to breakeven, then ATR trail. Time stop after
   `ENGINE_AUTO_TIME_STOP_BARS`; INTRADAY squared off at 15:15 IST. If the stop gaps through its
   limit, the engine market-exits itself.
10. Every signal (taken or skipped, with the reason) shows in the app's Signals screen; every
    order and trade is in the audit log; closed trades go to the journal → per-strategy stats.

Limits: max 3 open trades, 5 entries per strategy per day, NSE entries 09:30–14:45 IST Mon–Fri,
crypto off unless `ENGINE_AUTO_ALLOW_CRYPTO=true`.

## ⚠ Not yet
- Strategies are **not backtested** (Phase 2) — that is why everything starts in PAPER.
- Confidence is a heuristic, not a calibrated probability (Phase 3 meta-model).
- NSE holiday calendar not loaded: on holidays no ticks arrive, so nothing trades.
- Lot sizes for F&O need the instrument master (Phase 2): keep auto-trading to cash equities.
