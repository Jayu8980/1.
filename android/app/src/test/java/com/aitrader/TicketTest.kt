package com.aitrader

import com.aitrader.data.demo.DemoEngine
import com.aitrader.data.model.OrderTicket
import com.aitrader.ui.screens.validateTicket
import org.junit.Assert.*
import org.junit.Test

class TicketTest {
    @Test fun validation() {
        assertNotNull(validateTicket("", "LIMIT", "100", ""))
        assertNotNull(validateTicket("0", "MARKET", "", ""))
        assertNotNull(validateTicket("5", "LIMIT", "", ""))
        assertNotNull(validateTicket("5", "STOPLOSS_MARKET", "", ""))
        assertNull(validateTicket("5", "MARKET", "", ""))
        assertNull(validateTicket("5", "STOPLOSS_LIMIT", "99", "100"))
    }

    @Test fun demo_never_places_real_orders() {
        val e = DemoEngine(seed = 2, now = { 1_790_000_000_000 })
        val r = e.placeOrder(OrderTicket("SBIN-EQ", "NSE", "BUY", 10, "MARKET", expectLive = true))
        assertFalse(r.ok); assertTrue(r.message.contains("cannot place real orders"))
    }

    @Test fun demo_paper_order_fills_and_limit_can_be_cancelled() {
        val e = DemoEngine(seed = 2, now = { 1_790_000_000_000 })
        assertTrue(e.placeOrder(OrderTicket("SBIN-EQ", "NSE", "BUY", 10, "MARKET")).ok)
        assertEquals(10, e.positions().single { it.symbol == "SBIN-EQ" }.qty)
        val r = e.placeOrder(OrderTicket("SBIN-EQ", "NSE", "BUY", 5, "LIMIT", price = 1.0))
        assertTrue(r.message.contains("OPEN"))
        val id = e.orders().first { it.status == "OPEN" }.orderId
        assertTrue(e.cancelOrder(id).ok)
        assertFalse(e.cancelOrder(id).ok)
    }
}
