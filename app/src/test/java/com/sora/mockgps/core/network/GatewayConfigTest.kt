package com.sora.mockgps.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayConfigTest {
    @Test
    fun `parses public capabilities without inventing enabled flags`() {
        val capabilities = parseGatewayCapabilities(
            """
            {
              "schemaVersion": 1,
              "revision": "geoapify-v1",
              "searchEnabled": false,
              "routingEnabled": false,
              "purchasesEnabled": false,
              "adsEnabled": false,
              "routingMaxWaypoints": 25,
              "routingMaxInputDistanceMeters": 100000,
              "privacyPolicyUrl": "https://privacy.example.invalid",
              "supportUrl": "https://support.example.invalid"
            }
            """.trimIndent(),
            fetchedAtMillis = 10L,
        )
        assertEquals("geoapify-v1", capabilities.revision)
        assertFalse(capabilities.searchEnabled)
        assertFalse(capabilities.routingEnabled)
        assertFalse(capabilities.purchasesEnabled)
        assertFalse(capabilities.adsEnabled)
        assertTrue(isReleaseForbiddenUrl(capabilities.privacyPolicyUrl))
        assertTrue(isReleaseForbiddenUrl("https://privacy.example.invalid/path"))
        assertTrue(isReleaseForbiddenUrl("https://localhost/privacy"))
        assertFalse(isReleaseForbiddenUrl("https://bloss0m.com/privacy"))
    }

    @Test
    fun `maps typed error codes`() {
        val decoded = decodeError(
            GatewayHttpResponse(
                status = 429,
                body = """{"requestId":"abc","error":{"code":"GLOBAL_QUOTA_EXHAUSTED","retryAfterSeconds":3600}}""",
                requestId = "abc",
                retryAfterSeconds = 3600,
            ),
        )
        assertTrue(decoded is GatewayException.GlobalQuotaExhausted)
        assertEquals(3600, decoded.retryAfterSeconds)
    }

    @Test
    fun `non json errors become generic unavailable`() {
        val decoded = decodeError(GatewayHttpResponse(503, "error code: 1102", "x", null))
        assertTrue(decoded is GatewayException.Unavailable)
    }
}
