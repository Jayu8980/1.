package com.aitrader

import com.aitrader.data.demo.DemoEngine
import com.aitrader.data.model.ContractDto
import com.aitrader.data.repo.StreamParser
import org.junit.Assert.*
import org.junit.Test

class StreamParserTest {
    @Test fun parses_tick() {
        val m = StreamParser.parse("""{"type":"tick","key":"DELTA:BTCUSD","symbol":"BTCUSD","exchange":"DELTA",
            "ltp":61371.55,"ts":1790000000000,"source":"delta-ws"}""") as StreamParser.TickMsg
        assertEquals("DELTA:BTCUSD", m.key); assertEquals(61371.55, m.ltp, 1e-9); assertEquals("delta-ws", m.source)
    }

    @Test fun parses_status() {
        val m = StreamParser.parse("""{"type":"status","mode":"PAPER","killSwitch":false,"activeBroker":"angelone",
            "feed":{"sources":{"delta-ws":["DELTA:BTCUSD"]},"unassigned":["NSE:SBIN-EQ"]}}""") as StreamParser.StatusMsg
        assertEquals(listOf("DELTA:BTCUSD"), m.sources["delta-ws"]); assertEquals(listOf("NSE:SBIN-EQ"), m.unassigned)
        assertEquals("angelone", m.activeBroker)
    }

    @Test fun ignores_garbage_and_unknown() {
        assertNull(StreamParser.parse("not json"))
        assertNull(StreamParser.parse("""{"type":"other"}"""))
        assertNull(StreamParser.parse("""{"type":"tick"}"""))
    }

    @Test fun demo_watchlist_streams_and_edits() {
        val e = DemoEngine(seed = 3, now = { 1_790_000_000_000 })
        val q = e.quotes()
        assertTrue(q.any { it.key == "DELTA:BTCUSD" } && q.any { it.key == "NSE:Nifty 50" })
        val hit = e.search("WIP", "NSE").single()
        assertTrue(e.addWatch(hit.toContract()).ok)
        assertFalse(e.addWatch(hit.toContract()).ok)
        assertTrue(e.quotes().any { it.symbol == "WIPRO-EQ" })
        assertTrue(e.removeWatch(ContractDto("WIPRO-EQ", "x", "NSE").key).ok)
        assertEquals("0.001", e.search("BTC", "DELTA").single().multiplier)
    }
}
