package com.aitrader

import com.aitrader.ui.components.Fmt
import org.junit.Assert.assertEquals
import org.junit.Test

class FmtTest {
    @Test fun indian_compact_units() {
        assertEquals("₹1.50 L", Fmt.compact(150_000.0))
        assertEquals("−₹2.00 Cr", Fmt.compact(-20_000_000.0))
    }
    @Test fun signed_pct() {
        assertEquals("+1.25%", Fmt.pct(1.25))
        assertEquals("-0.50%", Fmt.pct(-0.5))
    }
}
