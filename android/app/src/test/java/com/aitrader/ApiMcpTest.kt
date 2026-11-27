package com.aitrader

import com.aitrader.data.demo.DemoEngine
import com.aitrader.ui.screens.mcpConfigs
import org.junit.Assert.*
import org.junit.Test

class ApiMcpTest {
    @Test fun mcp_configs_point_at_engine_with_key() {
        val (remote, stdio) = mcpConfigs("https://trader.example.com/", "atk_abc")
        assertTrue(remote.contains("\"url\": \"https://trader.example.com/mcp/\""))
        assertTrue(remote.contains("\"X-API-Key\": \"atk_abc\""))
        assertTrue(stdio.contains("\"app.mcp_server\"") && stdio.contains("\"AITRADER_API_KEY\": \"atk_abc\""))
        kotlinx.serialization.json.Json.parseToJsonElement(remote); kotlinx.serialization.json.Json.parseToJsonElement(stdio)
    }

    @Test fun demo_keys_and_approvals() {
        val e = DemoEngine(seed = 1, now = { 1_790_000_000_000 })
        assertFalse(e.createKey("x", emptyList()).ok)
        val k = e.createKey("Claude", listOf("trade", "read"))
        assertTrue(k.ok && k.key!!.startsWith("atk_") && k.info!!.scopes == listOf("read", "trade"))
        assertTrue(e.revokeKey(k.info!!.id).ok && e.apiKeys().single().revoked)
        val i = e.intents().single { it.status == "PENDING" }
        assertTrue(i.summary.startsWith("BUY 10 RELIANCE-EQ LIMIT @ 1410.5"))
        assertTrue(e.approveIntent(i.id).ok)
        assertFalse(e.approveIntent(i.id).ok)
    }
}
