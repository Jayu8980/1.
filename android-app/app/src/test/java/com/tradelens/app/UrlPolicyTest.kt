package com.tradelens.app

import com.tradelens.app.data.net.UrlPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlPolicyTest {
    @Test fun normalizesUserInput() {
        assertEquals("http://192.168.1.20:8000", UrlPolicy.normalize(" 192.168.1.20:8000/ "))
        assertEquals("http://192.168.1.20:8000", UrlPolicy.normalize("http://192.168.1.20:8000/api"))
        assertEquals("https://engine.example.com", UrlPolicy.normalize("engine.example.com"))
    }

    @Test fun cleartextOnlyForLan() {
        assertTrue(UrlPolicy.isPrivateHost("192.168.0.5")); assertTrue(UrlPolicy.isPrivateHost("10.1.2.3"))
        assertTrue(UrlPolicy.isPrivateHost("172.20.0.1")); assertTrue(UrlPolicy.isPrivateHost("100.101.1.1"))
        assertFalse(UrlPolicy.isPrivateHost("8.8.8.8")); assertFalse(UrlPolicy.isPrivateHost("172.32.0.1"))
        UrlPolicy.check("http://192.168.1.20:8000", "Engine")
        assertTrue(runCatching { UrlPolicy.check("http://example.com", "Engine") }.isFailure)
    }
}
