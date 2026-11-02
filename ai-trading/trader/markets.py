"""Indian market universes: NSE index lists, all NSE shares, F&O stocks, sectors, ETFs and indices.

Index constituents change twice a year, so the desk downloads the current lists published by NSE
(cached for a week) and falls back to the built-in lists below when it can't. Yahoo symbols are
used throughout: RELIANCE.NS for NSE shares, ^NSEI for the NIFTY 50 index.
"""
from __future__ import annotations

import csv
import io
import logging
import time
from pathlib import Path

log = logging.getLogger("trader.markets")

# Yahoo index symbol -> (F&O underlying name, cash exchange)
INDEX_UNDERLYINGS = {
    "^NSEI": ("NIFTY", "NSE"),
    "^NSEBANK": ("BANKNIFTY", "NSE"),
    "NIFTY_FIN_SERVICE.NS": ("FINNIFTY", "NSE"),
    "NIFTY_MID_SELECT.NS": ("MIDCPNIFTY", "NSE"),
    "^NSMIDCP": ("NIFTYNXT50", "NSE"),
    "^BSESN": ("SENSEX", "BSE"),
}
_FO_INDEX_SYMBOLS = {name: yahoo for yahoo, (name, _) in INDEX_UNDERLYINGS.items()}


def is_index(symbol: str) -> bool:
    return symbol.startswith("^") or symbol in INDEX_UNDERLYINGS


def _ns(names: str) -> list[str]:
    return [f"{n}.NS" for n in names.split()]


# ---- built-in lists (fallbacks; the live NSE lists take precedence) ------------------------------
NIFTY50 = _ns("""ADANIENT ADANIPORTS APOLLOHOSP ASIANPAINT AXISBANK BAJAJ-AUTO BAJFINANCE BAJAJFINSV BEL
BHARTIARTL CIPLA COALINDIA DRREDDY EICHERMOT ETERNAL GRASIM HCLTECH HDFCBANK HDFCLIFE HINDALCO HINDUNILVR
ICICIBANK INDIGO INFY ITC JIOFIN JSWSTEEL KOTAKBANK LT M&M MARUTI MAXHEALTH NESTLEIND NTPC ONGC POWERGRID
RELIANCE SBILIFE SBIN SHRIRAMFIN SUNPHARMA TATACONSUM TMPV TATASTEEL TCS TECHM TITAN TRENT ULTRACEMCO WIPRO""")
NIFTY_NEXT50 = _ns("""ABB ADANIENSOL ADANIGREEN ADANIPOWER AMBUJACEM BAJAJHLDNG BANKBARODA BOSCHLTD BPCL
BRITANNIA CANBK CGPOWER CHOLAFIN DABUR DIVISLAB DLF DMART GAIL GODREJCP HAL HAVELLS HEROMOTOCO HINDZINC
ICICIGI ICICIPRULI INDHOTEL INDUSINDBK IOC IRFC JINDALSTEL JSWENERGY LICI LODHA LTIM MOTHERSON NAUKRI PFC
PIDILITIND PNB RECLTD SHREECEM SIEMENS TATAPOWER TORNTPHARM TVSMOTOR UNITDSPR VBL VEDL ZYDUSLIFE HYUNDAI""")
MIDCAPS = _ns("""PERSISTENT COFORGE DIXON POLYCAB CUMMINSIND PIIND ASTRAL AUROPHARMA LUPIN FEDERALBNK
IDFCFIRSTB AUBANK MPHASIS VOLTAS GODREJPROP PHOENIXLTD OBEROIRLTY BHARATFORG ASHOKLEY MFSL BSE SUZLON
YESBANK PAYTM POLICYBZR KALYANKJIL MAZDOCK BDL HDFCAMC MUTHOOTFIN SBICARD TATAELXSI TORNTPOWER ABCAPITAL
CONCOR NHPC SAIL NMDC OIL PETRONET LICHSGFIN IRCTC INDUSTOWER TATACOMM ALKEM BIOCON PRESTIGE SRF UPL
COLPAL MARICO BALKRISIND MRF APLAPOLLO KEI SUPREMEIND JUBLFOOD PAGEIND BERGEPAINT""")
SMALLCAPS = _ns("""CDSL ANGELONE CAMS IEX MCX RBLBANK MANAPPURAM NATIONALUM HINDCOPPER NBCC IRCON HUDCO
CESC RADICO AMBER KAYNES CYIENT BSOFT SONATSOFTW ZENSARTECH NAVINFLUOR DEEPAKNTR GLENMARK LAURUSLABS
GRANULES NCC CHAMBLFERT RCF""")
SECTORS = {
    "nifty_bank": _ns("HDFCBANK ICICIBANK SBIN KOTAKBANK AXISBANK INDUSINDBK BANKBARODA PNB CANBK FEDERALBNK "
                      "IDFCFIRSTB AUBANK"),
    "nifty_it": _ns("TCS INFY HCLTECH WIPRO TECHM LTIM PERSISTENT COFORGE MPHASIS OFSS"),
    "nifty_pharma": _ns("SUNPHARMA CIPLA DRREDDY DIVISLAB LUPIN AUROPHARMA ZYDUSLIFE TORNTPHARM ALKEM GLENMARK "
                        "BIOCON MANKIND ABBOTINDIA IPCALAB LAURUSLABS"),
    "nifty_auto": _ns("M&M MARUTI TMPV BAJAJ-AUTO EICHERMOT HEROMOTOCO TVSMOTOR ASHOKLEY BOSCHLTD MOTHERSON "
                      "BHARATFORG BALKRISIND MRF TIINDIA EXIDEIND SONACOMS"),
    "nifty_fmcg": _ns("HINDUNILVR ITC NESTLEIND BRITANNIA TATACONSUM GODREJCP DABUR MARICO COLPAL UNITDSPR VBL "
                      "PGHH EMAMILTD RADICO PATANJALI"),
    "nifty_metal": _ns("TATASTEEL JSWSTEEL HINDALCO VEDL JINDALSTEL SAIL NMDC HINDZINC NATIONALUM APLAPOLLO JSL "
                       "HINDCOPPER ADANIENT WELCORP"),
    "nifty_energy": _ns("RELIANCE ONGC NTPC POWERGRID COALINDIA BPCL IOC GAIL TATAPOWER ADANIGREEN ADANIPOWER "
                        "JSWENERGY NHPC"),
    "nifty_realty": _ns("DLF LODHA GODREJPROP OBEROIRLTY PRESTIGE PHOENIXLTD BRIGADE SOBHA ANANTRAJ"),
    "nifty_psu_bank": _ns("SBIN BANKBARODA PNB CANBK UNIONBANK INDIANB BANKINDIA IOB UCOBANK CENTRALBK MAHABANK"),
    "nifty_fin_service": _ns("HDFCBANK ICICIBANK SBIN KOTAKBANK AXISBANK BAJFINANCE BAJAJFINSV SBILIFE HDFCLIFE "
                             "SHRIRAMFIN CHOLAFIN ICICIGI PFC RECLTD JIOFIN MUTHOOTFIN SBICARD ICICIPRULI"),
}
ETFS = _ns("""NIFTYBEES BANKBEES JUNIORBEES GOLDBEES SILVERBEES ITBEES PSUBNKBEES CPSEETF MON100 MAFANG
PHARMABEES AUTOBEES SETFNIF50""")
INDICES = list(INDEX_UNDERLYINGS)

# preset -> where NSE publishes the live list ("<csv>" index list, "fo" F&O stocks, "all" every share)
LIVE_LISTS = {
    "nifty50": "ind_nifty50list.csv", "nifty_next50": "ind_niftynext50list.csv",
    "nifty100": "ind_nifty100list.csv", "nifty200": "ind_nifty200list.csv", "nifty500": "ind_nifty500list.csv",
    "nifty_midcap150": "ind_niftymidcap150list.csv", "nifty_smallcap250": "ind_niftysmallcap250list.csv",
    "nifty_total_market": "ind_niftytotalmarket_list.csv",
    "nifty_bank": "ind_niftybanklist.csv", "nifty_it": "ind_niftyitlist.csv",
    "nifty_pharma": "ind_niftypharmalist.csv", "nifty_auto": "ind_niftyautolist.csv",
    "nifty_fmcg": "ind_niftyfmcglist.csv", "nifty_metal": "ind_niftymetallist.csv",
    "nifty_energy": "ind_niftyenergylist.csv", "nifty_realty": "ind_niftyrealtylist.csv",
    "nifty_psu_bank": "ind_niftypsubanklist.csv", "nifty_fin_service": "ind_niftyfinancelist.csv",
    "nse_fo": "fo", "nse_all": "all",
}
INDEX_LIST_URLS = ("https://www.niftyindices.com/IndexConstituent/{name}",
                   "https://archives.nseindia.com/content/indices/{name}")
FO_LIST_URL = "https://archives.nseindia.com/content/fo/fo_mktlots.csv"
ALL_SHARES_URL = "https://archives.nseindia.com/content/equities/EQUITY_L.csv"
_HEADERS = {"User-Agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0",
            "Accept": "text/csv,*/*"}


def india_presets() -> dict[str, dict]:
    """Universe presets for the Indian market, merged into config.PRESETS."""
    nse = {"benchmark": "^NSEI", "volatility_index": "^INDIAVIX"}
    nifty100 = NIFTY50 + NIFTY_NEXT50
    presets = {
        "nifty50": (NIFTY50, 500e6), "nifty_next50": (NIFTY_NEXT50, 200e6), "nifty100": (nifty100, 200e6),
        "nifty200": (nifty100 + MIDCAPS, 100e6), "nifty500": (nifty100 + MIDCAPS + SMALLCAPS, 50e6),
        "nifty_midcap150": (MIDCAPS, 50e6), "nifty_smallcap250": (SMALLCAPS, 20e6),
        "nifty_total_market": (nifty100 + MIDCAPS + SMALLCAPS, 20e6),
        "nse_all": (nifty100 + MIDCAPS + SMALLCAPS, 20e6),
        "nse_fo": (INDICES + NIFTY50 + NIFTY_NEXT50, 200e6),
        "india_etfs": (ETFS, 20e6), "india_indices": (INDICES, 0.0),
        **{name: (symbols, 100e6) for name, symbols in SECTORS.items()},
    }
    return {name: {"symbols": list(dict.fromkeys(symbols)), "min_turnover": turnover, "live": LIVE_LISTS.get(name),
                   **nse} for name, (symbols, turnover) in presets.items()}


# ---- live lists ----------------------------------------------------------------------------------
def parse_index_list(text: str) -> list[str]:
    """NSE index constituents CSV (Company Name, Industry, Symbol, Series, ISIN Code)."""
    rows = csv.DictReader(io.StringIO(text))
    out = []
    for row in rows:
        row = {(k or "").strip().lower(): (v or "").strip() for k, v in row.items()}
        if row.get("symbol"):
            out.append(f"{row['symbol']}.NS")
    return out


def parse_fo_list(text: str) -> list[str]:
    """NSE fo_mktlots.csv: the underlyings that have futures and options (indices mapped to Yahoo)."""
    reader = csv.reader(io.StringIO(text))
    header = [c.strip().upper() for c in next(reader, [])]
    if "SYMBOL" not in header:
        return []
    col = header.index("SYMBOL")
    out = []
    for row in reader:
        if len(row) <= col:
            continue
        symbol = row[col].strip().upper()
        if not symbol or symbol == "SYMBOL":
            continue
        out.append(_FO_INDEX_SYMBOLS.get(symbol, f"{symbol}.NS"))
    return list(dict.fromkeys(out))


def parse_all_shares(text: str) -> list[str]:
    """NSE EQUITY_L.csv: every listed share; keeps the regular EQ series."""
    out = []
    for row in csv.DictReader(io.StringIO(text)):
        row = {(k or "").strip().upper(): (v or "").strip() for k, v in row.items()}
        if row.get("SYMBOL") and row.get("SERIES", "EQ") == "EQ":
            out.append(f"{row['SYMBOL']}.NS")
    return out


def _download(urls) -> str | None:
    import requests

    for url in urls:
        try:
            r = requests.get(url, headers=_HEADERS, timeout=30)
            if r.status_code == 200 and r.text.strip():
                return r.text
            log.debug("%s -> HTTP %s", url, r.status_code)
        except Exception as exc:
            log.debug("%s failed: %s", url, exc)
    return None


def live_list(preset: str, cache_dir: str | Path, max_age_days: float = 7.0) -> list[str] | None:
    """The current NSE list for a preset, from cache or NSE; None when unavailable."""
    source = LIVE_LISTS.get(preset)
    if not source:
        return None
    if source == "fo":
        urls, parser, cache_name = (FO_LIST_URL,), parse_fo_list, "nse_fo.csv"
    elif source == "all":
        urls, parser, cache_name = (ALL_SHARES_URL,), parse_all_shares, "nse_all.csv"
    else:
        urls, parser, cache_name = tuple(u.format(name=source) for u in INDEX_LIST_URLS), parse_index_list, source
    path = Path(cache_dir) / "nse_lists" / cache_name
    if path.exists() and time.time() - path.stat().st_mtime < max_age_days * 86400:
        symbols = parser(path.read_text())
        if symbols:
            return symbols
    text = _download(urls)
    symbols = parser(text) if text else []
    if not symbols:
        return None
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text)
    return symbols
