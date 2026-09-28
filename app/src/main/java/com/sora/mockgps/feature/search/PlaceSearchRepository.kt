package com.sora.mockgps.feature.search

import com.sora.mockgps.core.io.readBoundedUtf8
import com.sora.mockgps.core.model.Coordinate
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

interface PlaceSearchRepository {
    suspend fun search(query: String, bias: PlaceSearchBias? = null): PlaceSearchPage
}

data class PlaceSearchProviderConfig(
    val baseUrl: String = "https://nominatim.openstreetmap.org/search",
    val userAgent: String = "BloomWalk-GPS/0.1 (https://github.com/poirotw66/MockGPS-Android)",
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

/**
 * Legacy public Nominatim adapter retained for fixture tests only.
 * Production composition must inject [GatewayPlaceSearchRepository].
 */
class NominatimPlaceSearchRepository(
    private val config: PlaceSearchProviderConfig = PlaceSearchProviderConfig(),
    private val limiter: RequestRateLimiter = RequestRateLimiter(config.requestIntervalMillis),
) : PlaceSearchRepository {
    override suspend fun search(query: String, bias: PlaceSearchBias?): PlaceSearchPage = withContext(Dispatchers.IO) {
        val normalized = normalizePlaceQuery(query)
        if (!isExplicitSearchQuerySubmittable(normalized)) {
            return@withContext PlaceSearchPage(emptyList())
        }
        limiter.awaitTurn()
        val endpoint = buildNominatimSearchUrl(config.baseUrl, normalized, bias)
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 12_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", config.userAgent)
            bias?.language?.takeIf { it.isNotBlank() }?.let { language ->
                setRequestProperty("Accept-Language", language)
            }
        }
        try {
            when (val status = connection.responseCode) {
                in 200..299 -> PlaceSearchPage(
                    results = parseNominatimResults(connection.inputStream.readBoundedUtf8(config.maxResponseBytes)),
                )
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

internal fun buildNominatimSearchUrl(
    baseUrl: String,
    query: String,
    bias: PlaceSearchBias? = null,
    limit: Int = 8,
): String {
    val encoded = URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
    val builder = StringBuilder("$baseUrl?format=jsonv2&limit=$limit&q=$encoded")
    bias?.let { searchBias ->
        val viewbox = viewboxAround(Coordinate(searchBias.latitude, searchBias.longitude))
        builder.append("&viewbox=").append(URLEncoder.encode(viewbox, Charsets.UTF_8.name()))
        builder.append("&bounded=0")
    }
    return builder.toString()
}

/** Soft viewbox around [center]; Nominatim order is left,top,right,bottom. */
internal fun viewboxAround(center: Coordinate, deltaDegrees: Double = 0.35): String {
    val left = center.longitude - deltaDegrees
    val right = center.longitude + deltaDegrees
    val top = center.latitude + deltaDegrees
    val bottom = center.latitude - deltaDegrees
    return "$left,$top,$right,$bottom"
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
                add(PlaceSearchResult(name, Coordinate(latitude, longitude), PlaceSearchSource.Remote))
            }
        }
    }
} catch (failure: Exception) {
    throw PlaceSearchException.InvalidResponse(failure)
}
