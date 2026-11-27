import json

import pytest

from app.core.errors import OrderBlocked
from app.core.ipguard import StaticIPGuard
from app.core.ratelimit import SlidingWindowLimiter


class FakeClock:
    def __init__(self):
        self.t = 0.0

    def __call__(self):
        return self.t

    async def sleep(self, s):
        self.t += s


async def test_rate_limiter_never_exceeds_limit_in_any_window():
    clk = FakeClock()
    lim = SlidingWindowLimiter(8, 1.0, clock=clk, sleep=clk.sleep)
    stamps = []
    for _ in range(50):
        await lim.acquire()
        stamps.append(clk.t)
    for i, t in enumerate(stamps):
        assert sum(1 for s in stamps if t <= s < t + 1.0) <= 8, i
    assert stamps[-1] >= 5.0  # 50 orders at 8/s take > 5s


async def test_ip_guard_match_mismatch_and_fail_closed():
    ips = iter(["1.2.3.4", "9.9.9.9"])

    async def fetch():
        return next(ips)
    g = StaticIPGuard("1.2.3.4", fetch, ttl_s=0)
    assert await g.assert_ok() == "1.2.3.4"
    with pytest.raises(OrderBlocked) as e:
        await g.assert_ok()
    assert e.value.code == "IP_MISMATCH"

    async def boom():
        raise RuntimeError("dns")
    with pytest.raises(OrderBlocked) as e:
        await StaticIPGuard("1.2.3.4", boom).assert_ok()
    assert e.value.code == "IP_CHECK_FAILED"
    with pytest.raises(OrderBlocked):
        await StaticIPGuard("", fetch).assert_ok()


async def test_ip_guard_ttl_cache():
    calls = 0

    async def fetch():
        nonlocal calls
        calls += 1
        return "1.2.3.4"
    clk = FakeClock()
    g = StaticIPGuard("1.2.3.4", fetch, ttl_s=30, clock=clk)
    await g.assert_ok(); await g.assert_ok()
    assert calls == 1
    clk.t = 31
    await g.assert_ok()
    assert calls == 2


async def test_audit_chain_and_tamper_detection(audit):
    for i in range(5):
        await audit.record("order.submit", n=i)
    assert audit.verify() == (True, None)
    lines = audit.path.read_text().splitlines()
    rec = json.loads(lines[2]); rec["data"]["n"] = 99
    lines[2] = json.dumps(rec)
    audit.path.write_text("\n".join(lines) + "\n")
    assert audit.verify() == (False, 3)


async def test_audit_resumes_chain_after_restart(audit):
    from app.core.audit import AuditLog
    await audit.record("a")
    again = AuditLog(audit.path)
    await again.record("b")
    assert again.verify() == (True, None)
