package com.sora.mockgps.feature.search

import com.sora.mockgps.core.model.Coordinate
import com.sora.mockgps.core.io.readBoundedUtf8
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray

data class PlaceSearchResult(val name: String, val coordinate: Coordinate)

sealed class PlaceSearchException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Network(cause: Throwable? = null) : PlaceSearchException("network", cause)
    class RateLimited : PlaceSearchException("rate_limited")
    class InvalidResponse(cause: Throwable? = null) : PlaceSearchException("invalid_response", cause)
}

interface PlaceSearchRepository {
    suspend fun search(query: String): List<PlaceSearchResult>
}

data class PlaceSearchProviderConfig(
    val baseUrl: String = "https://nominatim.openstreetmap.org/search",
    val userAgent: String = "MockGPS-Android/0.1 (https://github.com/poirotw66/MockGPS-Android)",
    val requestIntervalMillis: Long = 1_000L,
    val maxResponseBytes: Int = 256 * 1024,
) {
    init {
        val uri = URI(baseUrl)
        require(uri.scheme == "https" || uri.scheme == "http")
        require(!uri.host.isNullOrBlank())
        require(userAgent.isNotBlank())
        require(requestIntervalMillis >= 1_000L) { "Public OSM-compatible search must be limited to 1 request/s." }
        require(maxResponseBytes in 1_024..1_048_576)
    }
}

/** Process-wide serialized limiter; cancellation during wait never consumes a request slot. */
class RequestRateLimiter(private val minimumIntervalMillis: Long, private val now: () -> Long = System::currentTimeMillis) {
    private val mutex = Mutex()
    private var nextAllowedAt = 0L

    suspend fun awaitTurn() = mutex.withLock {
        val waitMillis = (nextAllowedAt - now()).coerceAtLeast(0L)
        if (waitMillis > 0) delay(waitMillis)
        nextAllowedAt = now() + minimumIntervalMillis
    }
}

class NominatimPlaceSearchRepository(
    private val config: PlaceSearchProviderConfig = PlaceSearchProviderConfig(),
    private val limiter: RequestRateLimiter = RequestRateLimiter(config.requestIntervalMillis),
) : PlaceSearchRepository {
    override suspend fun search(query: String): List<PlaceSearchResult> = withContext(Dispatchers.IO) {
        val normalized = query.trim()
        if (normalized.length < 2) return@withContext emptyList()
        limiter.awaitTurn()
        val endpoint = "${config.baseUrl}?format=jsonv2&limit=8&q=" +
            URLEncoder.encode(normalized, Charsets.UTF_8.name())
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 12_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", config.userAgent)
        }
        try {
            when (val status = connection.responseCode) {
                in 200..299 -> parseNominatimResults(connection.inputStream.readBoundedUtf8(config.maxResponseBytes))
                429 -> throw PlaceSearchException.RateLimited()
                else -> throw PlaceSearchException.Network(IOException("HTTP $status"))
            }
        } catch (failure: PlaceSearchException) {
            throw failure
        } catch (failure: IOException) {
            throw PlaceSearchException.Network(failure)
        } catch (failure: Exception) {
            throw PlaceSearchException.InvalidResponse(failure)
        } finally {
            connection.disconnect()
        }
    }
}

internal fun parseNominatimResults(body: String): List<PlaceSearchResult> = try {
    val items = JSONArray(body)
    buildList {
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val latitude = item.optString("lat").toDoubleOrNull() ?: continue
            val longitude = item.optString("lon").toDoubleOrNull() ?: continue
            val name = item.optString("display_name").trim()
            if (name.isNotEmpty() && latitude in -90.0..90.0 && longitude in -180.0..180.0) {
                add(PlaceSearchResult(name, Coordinate(latitude, longitude)))
            }
        }
    }
} catch (failure: Exception) {
    throw PlaceSearchException.InvalidResponse(failure)
}
