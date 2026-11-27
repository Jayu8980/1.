from datetime import datetime, timezone
from decimal import Decimal

import pytest

from app.broker.paper import PaperBroker
from app.core.audit import AuditLog
from app.core.enums import Exchange, Instrument
from app.core.models import Contract

D = Decimal


@pytest.fixture
def audit(tmp_path):
    return AuditLog(tmp_path / "audit.jsonl")


@pytest.fixture
def sbin():
    return Contract(symbol="SBIN-EQ", token="3045", exchange=Exchange.NSE)


@pytest.fixture
def nifty_fut():
    return Contract(symbol="NIFTY26DECFUT", token="35001", exchange=Exchange.NFO,
                    instrument=Instrument.FUTURE, lot_size=75, tick_size=D("0.10"))


@pytest.fixture
def paper():
    return PaperBroker(starting_capital=D("100000"), slippage_bps=D("10"),
                       clock=lambda: datetime(2026, 11, 27, 4, 0, tzinfo=timezone.utc))
