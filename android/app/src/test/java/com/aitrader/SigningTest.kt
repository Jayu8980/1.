package com.aitrader.data.remote

import org.junit.Assert.*
import org.junit.Test

class SigningTest {
    @Test fun money_endpoints_require_signature() {
        assertTrue(requiresSignature("POST", "/api/v1/positions/exit"))
        assertTrue(requiresSignature("PATCH", "/api/v1/strategies/trend"))
        assertTrue(requiresSignature("PUT", "/api/v1/risk"))
        assertTrue(requiresSignature("POST", "/api/v1/mode"))
        assertTrue(requiresSignature("POST", "/api/v1/brokers/zerodha/connect"))
        assertTrue(requiresSignature("POST", "/api/v1/brokers/dhan/activate"))
        assertTrue(requiresSignature("POST", "/api/v1/autotrade/on"))
        assertTrue(requiresSignature("POST", "/api/v1/orders"))
        assertTrue(requiresSignature("POST", "/api/v1/strategies/trend_momentum/promote"))
    }

    @Test fun reads_and_kill_switch_do_not() {
        assertFalse(requiresSignature("GET", "/api/v1/risk"))
        assertFalse(requiresSignature("GET", "/api/v1/positions"))
        assertFalse(requiresSignature("POST", "/api/v1/kill-switch"))
        assertFalse(requiresSignature("GET", "/api/v1/brokers"))
        assertFalse(requiresSignature("POST", "/api/v1/brokers/zerodha/disconnect"))
        assertFalse(requiresSignature("POST", "/api/v1/autotrade/off"))
        assertFalse(requiresSignature("POST", "/api/v1/orders/123/cancel"))
        assertFalse(requiresSignature("GET", "/api/v1/orders"))
        assertFalse(requiresSignature("POST", "/api/v1/strategies/trend_momentum/demote"))
    }

    @Test fun payload_is_canonical() {
        val p = String(signingPayload("POST", "/api/v1/mode", 1700, "{}".toByteArray()))
        assertEquals("POST\n/api/v1/mode\n1700\n44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a", p)
    }

    @Test fun parses_multiple_pins() {
        val a = "sha256/C5+lpZ7tcVwmwQIMcRtPbsQtWLABXhQzejna0wHFr8M="
        val b = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        assertEquals(listOf(a, b), parsePins(" $a, $b ,junit "))
        assertTrue(parsePins("nope").isEmpty())
    }
}
