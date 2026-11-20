"""Engine configuration from environment variables / an encrypted secrets file.

Secrets (API token, broker/Delta keys, relay token) are never hard-coded. They come from the environment or
from `secrets.enc`, a Fernet-encrypted JSON file unlocked with TRADELENS_MASTER_KEY (see `python -m tradelens secrets`).
"""
from __future__ import annotations

import json
import os
from dataclasses import dataclass, field
from pathlib import Path

from cryptography.fernet import Fernet

BASE_DIR = Path(os.environ.get("TRADELENS_HOME", Path(__file__).resolve().parent.parent))
SECRETS_FILE = BASE_DIR / "secrets.enc"


def load_secrets() -> dict:
    key = os.environ.get("TRADELENS_MASTER_KEY")
    if not key or not SECRETS_FILE.exists():
        return {}
    return json.loads(Fernet(key.encode()).decrypt(SECRETS_FILE.read_bytes()))


def save_secrets(values: dict, key: str) -> None:
    SECRETS_FILE.write_bytes(Fernet(key.encode()).encrypt(json.dumps(values).encode()))
    os.chmod(SECRETS_FILE, 0o600)


def _get(name: str, default: str = "") -> str:
    return os.environ.get(name) or _SECRETS.get(name, default)


_SECRETS = load_secrets()


@dataclass
class RiskLimits:
    dailyLossPct: float = 2.0
    maxDrawdownPct: float = 10.0
    maxOrdersPerDay: int = 50
    maxPositionPct: float = 10.0
    maxSectorPct: float = 25.0
    maxMarginUtilPct: float = 50.0
    riskPerTradePct: float = 1.0


@dataclass
class Config:
    api_token: str = field(default_factory=lambda: _get("TRADELENS_API_TOKEN"))
    mode: str = field(default_factory=lambda: _get("TRADELENS_MODE", "paper"))  # paper | live
    capital: float = field(default_factory=lambda: float(_get("TRADELENS_CAPITAL", "1000000")))
    target_vol: float = field(default_factory=lambda: float(_get("TRADELENS_TARGET_VOL", "0.13")))
    data_dir: Path = field(default_factory=lambda: Path(_get("TRADELENS_DATA_DIR", str(BASE_DIR / "data_cache"))))
    state_dir: Path = field(default_factory=lambda: Path(_get("TRADELENS_STATE_DIR", str(BASE_DIR / "state"))))
    relay_url: str = field(default_factory=lambda: _get("TRADELENS_RELAY_URL"))
    relay_token: str = field(default_factory=lambda: _get("TRADELENS_RELAY_TOKEN"))
    delta_key: str = field(default_factory=lambda: _get("DELTA_API_KEY"))
    delta_secret: str = field(default_factory=lambda: _get("DELTA_API_SECRET"))
    delta_testnet: bool = field(default_factory=lambda: _get("DELTA_TESTNET", "true").lower() == "true")
    delta_production_unlocked: bool = field(default_factory=lambda: _get("DELTA_PRODUCTION_UNLOCKED", "false").lower() == "true")
    broker: str = field(default_factory=lambda: _get("TRADELENS_BROKER", ""))
    broker_session: str = field(default_factory=lambda: _get("TRADELENS_BROKER_SESSION", ""))
    risk: RiskLimits = field(default_factory=RiskLimits)

    def ensure_dirs(self) -> None:
        self.data_dir.mkdir(parents=True, exist_ok=True)
        self.state_dir.mkdir(parents=True, exist_ok=True)


CONFIG = Config()
