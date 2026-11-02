"""Indian market universes, segment settings and index handling."""
import pytest

from trader import markets
from trader.config import PRESETS, Config
from trader.markets import is_index, parse_all_shares, parse_fo_list, parse_index_list

INDIA_PRESETS = ("nifty50", "nifty_next50", "nifty100", "nifty200", "nifty500", "nifty_midcap150",
                 "nifty_smallcap250", "nifty_total_market", "nse_all", "nse_fo", "india_etfs", "india_indices",
                 "nifty_bank", "nifty_it", "nifty_pharma", "nifty_auto", "nifty_fmcg", "nifty_metal",
                 "nifty_energy", "nifty_realty", "nifty_psu_bank", "nifty_fin_service")


def test_india_presets_cover_the_market():
    for name in INDIA_PRESETS:
        preset = PRESETS[name]
        assert preset["symbols"] and preset["benchmark"] == "^NSEI" and preset["volatility_index"] == "^INDIAVIX"
        assert len(set(preset["symbols"])) == len(preset["symbols"])
    assert len(PRESETS["nifty50"]["symbols"]) == 50
    assert all(s.endswith(".NS") for s in PRESETS["nifty_bank"]["symbols"])
    assert all(is_index(s) for s in PRESETS["india_indices"]["symbols"])


def test_nse_list_parsers():
    index_csv = ("Company Name,Industry,Symbol,Series,ISIN Code\n"
                 "Reliance Industries Ltd.,Oil Gas & Consumable Fuels,RELIANCE,EQ,INE002A01018\n"
                 "Mahindra & Mahindra Ltd.,Automobile and Auto Components,M&M,EQ,INE101A01026\n")
    assert parse_index_list(index_csv) == ["RELIANCE.NS", "M&M.NS"]
    fo_csv = ("UNDERLYING                    ,SYMBOL    ,NOV-26 ,DEC-26\n"
              "NIFTY 50 ,NIFTY ,65 ,65\nNIFTY BANK ,BANKNIFTY ,30 ,30\n"
              "Derivatives on Individual Securities,,,\nRELIANCE INDUSTRIES LTD ,RELIANCE ,500 ,500\n")
    assert parse_fo_list(fo_csv) == ["^NSEI", "^NSEBANK", "RELIANCE.NS"]
    all_csv = ("SYMBOL,NAME OF COMPANY, SERIES, DATE OF LISTING\n"
               "20MICRONS,20 Microns Limited,EQ,06-OCT-2008\nSOMEBE,Some Ltd,BE,01-JAN-2020\n")
    assert parse_all_shares(all_csv) == ["20MICRONS.NS"]


def test_live_lists_download_cache_and_fall_back(tmp_path, monkeypatch):
    calls = []
    monkeypatch.setattr(markets, "_download", lambda urls: calls.append(urls) or "Symbol\nTCS\nINFY\n")
    assert markets.live_list("nifty_it", tmp_path) == ["TCS.NS", "INFY.NS"]
    assert markets.live_list("nifty_it", tmp_path) == ["TCS.NS", "INFY.NS"] and len(calls) == 1  # cached
    assert "ind_niftyitlist.csv" in calls[0][0]
    monkeypatch.setattr(markets, "_download", lambda urls: None)
    assert markets.live_list("nifty500", tmp_path) is None   # the desk keeps the built-in list
    assert markets.live_list("india_etfs", tmp_path) is None  # no live source for this one


def test_segment_settings():
    cfg = Config()
    cfg.india.segment = "intraday"
    cfg.finalize()
    assert cfg.regime.allow_short and cfg.optimizer.max_hold == [1]  # trades open and close the same day
    cfg = Config()
    cfg.india.segment, cfg.execution.broker = "options", "zerodha"
    cfg.finalize()
    assert cfg.regime.allow_short and max(cfg.optimizer.max_hold) <= 10
    cfg = Config()
    cfg.india.segment = "futures"  # the paper simulator cannot trade futures
    with pytest.raises(ValueError, match="Indian broker"):
        cfg.finalize()
    for field, value in (("segment", "swing"), ("option_strike", "deep"), ("intraday_square_off", "3pm")):
        cfg = Config()
        setattr(cfg.india, field, value)
        with pytest.raises(ValueError):
            cfg.finalize()


def test_indices_pass_the_liquidity_screen_without_volume():
    from trader.agents.liquidity import LiquidityAgent
    from trader.bus import Blackboard, Candidate, MessageBus
    from trader.data import synthetic_ohlcv

    cfg = Config()
    cfg.universe.preset = "india_indices"
    cfg.finalize()
    board = Blackboard()
    for symbol in ("^NSEI", "RELIANCE.NS"):
        df = synthetic_ohlcv(symbol, bars=600)
        df["Volume"] = 0.0  # Yahoo reports no volume for indices
        board.candidates[symbol] = Candidate(symbol, df)
    LiquidityAgent(cfg, MessageBus(), board, {}).run()
    assert board.candidates["^NSEI"].alive
    assert board.candidates["RELIANCE.NS"].rejection.startswith("volume missing")  # a share needs volume
