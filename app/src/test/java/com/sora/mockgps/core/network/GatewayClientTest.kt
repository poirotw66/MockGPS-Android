package com.sora.mockgps.core.network

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

class GatewayClientTest {
    @Test
    fun `http endpoints are rejected at configuration time`() {
        val failure = runCatching {
            GatewayClientConfig(baseUrl = "http://gateway.example.test", clientVersion = "1")
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `oversized search posts never open a connection`() = runBlocking {
        var opened = false
        val client = GatewayClient(
            config = GatewayClientConfig(baseUrl = "https://gateway.example.test", clientVersion = "1"),
            installIdProvider = { "11111111-1111-4111-8111-111111111111" },
            openConnection = {
                opened = true
                error("must not open")
            },
        )
        val payload = JSONObject().put("query", "x".repeat(5_000))
        val failure = runCatching {
            client.postJson("/v1/search", payload, GatewayClientConfig.SEARCH_REQUEST_MAX_BYTES, 64 * 1024)
        }.exceptionOrNull()
        assertTrue(failure is GatewayException.RequestTooLarge)
        assertFalse(opened)
    }

    @Test
    fun `non json gateway errors become generic unavailable`() = runBlocking {
        val client = GatewayClient(
            config = GatewayClientConfig(baseUrl = "https://gateway.example.test", clientVersion = "1"),
            installIdProvider = { "11111111-1111-4111-8111-111111111111" },
            openConnection = { url -> FakeGatewayConnection(url, 503, "error code: 1102") },
        )
        val failure = runCatching {
            client.postJson(
                "/v1/search",
                JSONObject().put("query", "Taipei").put("language", "en"),
                GatewayClientConfig.SEARCH_REQUEST_MAX_BYTES,
                64 * 1024,
            )
        }.exceptionOrNull()
        assertTrue(failure is GatewayException.Unavailable)
    }

    @Test
    fun `json 429 client rate limit is typed and not shown as raw server text`() = runBlocking {
        val client = GatewayClient(
            config = GatewayClientConfig(baseUrl = "https://gateway.example.test", clientVersion = "1"),
            installIdProvider = { "11111111-1111-4111-8111-111111111111" },
            openConnection = { url ->
                FakeGatewayConnection(
                    url,
                    429,
                    """{"requestId":"abc","error":{"code":"CLIENT_RATE_LIMITED","retryAfterSeconds":30}}""",
                    retryAfter = "30",
                )
            },
        )
        val failure = runCatching {
            client.postJson(
                "/v1/search",
                JSONObject().put("query", "Taipei").put("language", "en"),
                GatewayClientConfig.SEARCH_REQUEST_MAX_BYTES,
                64 * 1024,
            )
        }.exceptionOrNull()
        assertTrue(failure is GatewayException.ClientRateLimited)
        assertEquals(30, (failure as GatewayException.ClientRateLimited).retryAfterSeconds)
    }

    private class FakeGatewayConnection(
        url: URL,
        private val status: Int,
        private val payload: String,
        private val retryAfter: String? = null,
    ) : HttpURLConnection(url) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int = status
        override fun getInputStream() = payload.byteInputStream()
        override fun getErrorStream() = payload.byteInputStream()
        override fun getHeaderField(name: String?): String? =
            if (name.equals("Retry-After", ignoreCase = true)) retryAfter else null
        override fun getOutputStream() = java.io.ByteArrayOutputStream()
    }
}
