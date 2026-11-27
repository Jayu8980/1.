from decimal import Decimal
from functools import lru_cache

from pydantic import Field, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict

from app.core.enums import Mode


class Settings(BaseSettings):
    """All config comes from env / secrets manager. Never commit real values."""

    model_config = SettingsConfigDict(env_prefix="ENGINE_", env_file=".env", extra="ignore")

    # --- mode & safety -------------------------------------------------------
    mode: Mode = Mode.PAPER
    # Second lock: LIVE can only be armed if the operator also set this on the server.
    allow_live: bool = False
    live_confirm_phrase: str = "I UNDERSTAND THIS USES REAL MONEY"

    # --- Angel One SmartAPI --------------------------------------------------
    smartapi_base_url: str = "https://apiconnect.angelone.in"
    smartapi_api_key: SecretStr = SecretStr("")
    smartapi_client_code: str = ""
    smartapi_mpin: SecretStr = SecretStr("")
    smartapi_totp_secret: SecretStr = SecretStr("")   # never leaves the server
    smartapi_local_ip: str = "127.0.0.1"
    smartapi_mac: str = "00:00:00:00:00:00"
    smartapi_timeout_s: float = 10.0

    # --- broker selection & other brokers (see docs/trading/BROKERS.md) ------
    live_broker: str = "angelone"           # angelone | zerodha | upstox | dhan | fyers
    public_base_url: str = ""               # https://your-vm  (OAuth redirect base)
    token_store_path: str = "data/broker_tokens.json"
    zerodha_api_key: str = ""
    zerodha_api_secret: SecretStr = SecretStr("")
    upstox_client_id: str = ""
    upstox_client_secret: SecretStr = SecretStr("")
    dhan_client_id: str = ""
    dhan_access_token: SecretStr = SecretStr("")
    fyers_app_id: str = ""                  # e.g. "XA1234-100"
    fyers_secret: SecretStr = SecretStr("")

    # --- compliance ----------------------------------------------------------
    static_ip: str = ""                     # IP registered with SmartAPI
    ip_check_url: str = "https://api.ipify.org"
    ip_check_ttl_s: float = 30.0            # 0 = check before every single order
    orders_per_sec: int = Field(default=8, ge=1, le=9)  # stays under exchange 10 OPS
    daily_login_time_ist: str = "08:30"
    daily_logout_time_ist: str = "23:45"
    audit_log_path: str = "data/audit.jsonl"

    # --- paper broker --------------------------------------------------------
    paper_starting_capital: Decimal = Decimal("1000000")
    paper_slippage_bps: Decimal = Decimal("5")

    # --- api -----------------------------------------------------------------
    jwt_secret: SecretStr = SecretStr("change-me")
    jwt_ttl_min: int = 30
    app_user: str = ""
    app_password_hash: str = ""             # python -m app.api.security
    device_keys_path: str = "data/device_keys.json"


@lru_cache
def get_settings() -> Settings:
    return Settings()
