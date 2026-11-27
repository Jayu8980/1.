class EngineError(Exception):
    """Base engine error."""


class OrderBlocked(EngineError):
    """An order was blocked before reaching the broker (kill switch, risk, IP, mode)."""

    def __init__(self, reason: str, code: str = "BLOCKED"):
        super().__init__(reason)
        self.reason = reason
        self.code = code


class BrokerError(EngineError):
    def __init__(self, message: str, errorcode: str | None = None):
        super().__init__(message)
        self.errorcode = errorcode


class AuthError(BrokerError):
    pass
