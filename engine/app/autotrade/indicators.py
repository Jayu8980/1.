"""Small, dependency-free indicators on lists of floats (latest value last)."""
from __future__ import annotations


def sma(x: list[float], n: int) -> float | None:
    return sum(x[-n:]) / n if len(x) >= n else None


def ema_series(x: list[float], n: int) -> list[float]:
    if len(x) < n:
        return []
    k = 2 / (n + 1)
    out = [sum(x[:n]) / n]
    for v in x[n:]:
        out.append(v * k + out[-1] * (1 - k))
    return out


def ema(x: list[float], n: int) -> float | None:
    s = ema_series(x, n)
    return s[-1] if s else None


def atr(high: list[float], low: list[float], close: list[float], n: int = 14) -> float | None:
    if len(close) < n + 1:
        return None
    trs = [max(high[i] - low[i], abs(high[i] - close[i - 1]), abs(low[i] - close[i - 1])) for i in range(1, len(close))]
    a = sum(trs[:n]) / n
    for tr in trs[n:]:            # Wilder smoothing
        a = (a * (n - 1) + tr) / n
    return a


def rsi(close: list[float], n: int = 14) -> float | None:
    if len(close) < n + 1:
        return None
    gains = [max(close[i] - close[i - 1], 0) for i in range(1, len(close))]
    losses = [max(close[i - 1] - close[i], 0) for i in range(1, len(close))]
    ag, al = sum(gains[:n]) / n, sum(losses[:n]) / n
    for g, l in zip(gains[n:], losses[n:]):
        ag, al = (ag * (n - 1) + g) / n, (al * (n - 1) + l) / n
    if al == 0:
        return 100.0
    return 100 - 100 / (1 + ag / al)


def efficiency_ratio(close: list[float], n: int = 20) -> float | None:
    """Kaufman ER: |net move| / sum of |moves|. ~1 = clean trend, ~0 = chop."""
    if len(close) < n + 1:
        return None
    w = close[-(n + 1):]
    path = sum(abs(w[i] - w[i - 1]) for i in range(1, len(w)))
    return abs(w[-1] - w[0]) / path if path else 0.0
