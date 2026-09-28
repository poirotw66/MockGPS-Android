package com.sora.mockgps.core.network

import com.sora.mockgps.core.io.readBoundedUtf8
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

fun interface RequestIdFactory {
    fun next(): String
}

class DefaultRequestIdFactory : RequestIdFactory {
    override fun next(): String = UUID.randomUUID().toString()
}

data class GatewayHttpResponse(
    val status: Int,
    val body: String,
    val requestId: String,
    val retryAfterSeconds: Int?,
)

/**
 * Bounded HTTPS client for the BloomWalk gateway. v1 POSTs are not retried here;
 * callers create a new request ID on an explicit user retry.
 */
class GatewayClient(
    private val config: GatewayClientConfig,
    private val installIdProvider: () -> String,
    private val requestIds: RequestIdFactory = DefaultRequestIdFactory(),
    private val openConnection: (URL) -> HttpURLConnection = { url ->
        url.openConnection() as HttpURLConnection
    },
) {
    suspend fun getConfig(): GatewayCapabilities = withContext(Dispatchers.IO) {
        val response = execute(
            method = "GET",
            path = "/v1/config",
            body = null,
            maxResponseBytes = config.maxConfigResponseBytes,
        )
        if (response.status !in 200..299) throw decodeError(response)
        parseGatewayCapabilities(response.body, System.currentTimeMillis())
    }

    suspend fun postJson(
        path: String,
        payload: JSONObject,
        maxRequestBytes: Int,
        maxResponseBytes: Int,
    ): GatewayHttpResponse = withContext(Dispatchers.IO) {
        val encoded = payload.toString().toByteArray(Charsets.UTF_8)
        if (encoded.size > maxRequestBytes) {
            throw GatewayException.RequestTooLarge()
        }
        val response = execute(
            method = "POST",
            path = path,
            body = encoded,
            maxResponseBytes = maxResponseBytes,
        )
        if (response.status !in 200..299) throw decodeError(response)
        response
    }

    private fun execute(
        method: String,
        path: String,
        body: ByteArray?,
        maxResponseBytes: Int,
    ): GatewayHttpResponse {
        val requestId = requestIds.next()
        val url = URL("${config.normalizedBaseUrl}$path")
        val connection = openConnection(url).apply {
            requestMethod = method
            connectTimeout = config.connectTimeoutMillis
            readTimeout = config.readTimeoutMillis
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", config.userAgent)
            setRequestProperty("X-Install-ID", installIdProvider())
            setRequestProperty("X-Client-Version", config.clientVersion)
            setRequestProperty("X-Request-ID", requestId)
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setFixedLengthStreamingMode(body.size)
            }
        }
        try {
            if (body != null) {
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val raw = try {
                stream?.readBoundedUtf8(maxResponseBytes).orEmpty()
            } catch (failure: IllegalArgumentException) {
                throw GatewayException.InvalidProviderResponse(requestId, failure)
            }
            return GatewayHttpResponse(
                status = status,
                body = raw,
                requestId = connection.getHeaderField("X-Request-ID")?.ifBlank { null } ?: requestId,
                retryAfterSeconds = parseRetryAfter(connection.getHeaderField("Retry-After")),
            )
        } catch (failure: GatewayException) {
            throw failure
        } catch (failure: IOException) {
            throw GatewayException.Unavailable(requestId, failure)
        } finally {
            connection.disconnect()
        }
    }
}

fun decodeError(response: GatewayHttpResponse): GatewayException {
    if (response.body.isBlank()) {
        return gatewayExceptionFromCode(null, response.status, response.requestId, response.retryAfterSeconds)
    }
    val parsed = runCatching { JSONObject(response.body) }.getOrNull()
        ?: return GatewayException.Unavailable(response.requestId)
    val error = parsed.optJSONObject("error")
    val code = error?.optString("code")?.ifBlank { null }
    val retryAfter = error?.optInt("retryAfterSeconds", -1)?.takeIf { it >= 0 }
        ?: response.retryAfterSeconds
    return gatewayExceptionFromCode(code, response.status, parsed.optString("requestId").ifBlank {
        response.requestId
    }, retryAfter)
}

internal fun parseRetryAfter(raw: String?): Int? {
    val value = raw?.trim()?.toIntOrNull() ?: return null
    return value.takeIf { it >= 0 }
}
