from enum import StrEnum


class Mode(StrEnum):
    PAPER = "PAPER"
    LIVE = "LIVE"


class Exchange(StrEnum):
    NSE = "NSE"
    BSE = "BSE"
    NFO = "NFO"   # NSE F&O
    BFO = "BFO"   # BSE F&O
    DELTA = "DELTA"  # Delta Exchange crypto derivatives (24x7, not an Indian stock exchange)


class Side(StrEnum):
    BUY = "BUY"
    SELL = "SELL"


class OrderType(StrEnum):
    MARKET = "MARKET"
    LIMIT = "LIMIT"
    SL_LIMIT = "STOPLOSS_LIMIT"
    SL_MARKET = "STOPLOSS_MARKET"


class Product(StrEnum):
    DELIVERY = "DELIVERY"        # cash, CNC
    INTRADAY = "INTRADAY"        # MIS
    CARRYFORWARD = "CARRYFORWARD"  # NRML for F&O


class Instrument(StrEnum):
    EQUITY = "EQUITY"
    FUTURE = "FUTURE"
    OPTION = "OPTION"


class OrderStatus(StrEnum):
    PENDING = "PENDING"        # accepted, waiting (limit/SL)
    OPEN = "OPEN"
    FILLED = "FILLED"
    CANCELLED = "CANCELLED"
    REJECTED = "REJECTED"
