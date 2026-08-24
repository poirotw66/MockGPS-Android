package com.sora.mockgps.route

import com.sora.mockgps.core.model.Coordinate
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class PlannedRoute(
    val points: List<Coordinate>,
    val distanceMeters: Double,
    val providerDurationSeconds: Double,
    val cacheStatus: RoutingCacheStatus = RoutingCacheStatus.Network,
) {
    init {
        require(points.size >= 2) { "A planned route needs at least two points." }
        require(distanceMeters.isFinite() && distanceMeters > 0.0) { "Route distance is invalid." }
    }

    val simulatedDurationSeconds: Double
        get() = distanceMeters / RoutePlayback.BICYCLE_SPEED_METERS_PER_SECOND
}

/** Identifies whether a route came directly from a provider or from a local cache. */
sealed interface RoutingCacheStatus {
    data object Network : RoutingCacheStatus
    data class Fresh(val ageMillis: Long) : RoutingCacheStatus
    data class StaleFallback(
        val ageMillis: Long,
        val networkFailure: RoutingNetworkException,
    ) : RoutingCacheStatus
}

interface RoutingRepository {
    /** Plans one ordered route: origin, zero or more intermediate waypoints, then destination. */
    suspend fun planBicycleRoute(waypoints: List<Coordinate>): PlannedRoute

    /** Compatibility convenience for the existing two-point flow. */
    suspend fun planBicycleRoute(origin: Coordinate, destination: Coordinate): PlannedRoute =
        planBicycleRoute(listOf(origin, destination))
}

/**
 * Pure, injectable provider settings. [baseUrl] must point at an OSRM route profile endpoint,
 * for example `https://host/routed-bike/route/v1/driving/`; no API key is embedded here.
 */
data class RoutingProviderConfig(
    val baseUrl: String = FOSSGIS_BICYCLE_BASE_URL,
    val userAgent: String = DEFAULT_USER_AGENT,
    val connectTimeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    val readTimeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
) {
    init {
        val uri = runCatching { URI(baseUrl) }.getOrElse {
            throw IllegalArgumentException("Routing provider base URL is invalid.", it)
        }
        require(uri.scheme == "https" || uri.scheme == "http") { "Routing provider must use HTTP(S)." }
        require(!uri.host.isNullOrBlank()) { "Routing provider must include a host." }
        require(uri.query == null && uri.fragment == null) { "Routing provider base URL cannot include a query or fragment." }
        require(userAgent.isNotBlank()) { "Routing provider user agent cannot be blank." }
        require(connectTimeoutMillis in 1..MAX_TIMEOUT_MILLIS) { "Connect timeout is out of range." }
        require(readTimeoutMillis in 1..MAX_TIMEOUT_MILLIS) { "Read timeout is out of range." }
    }

    internal val normalizedBaseUrl: String get() = baseUrl.trimEnd('/') + "/"

    companion object {
        const val FOSSGIS_BICYCLE_BASE_URL = "https://routing.openstreetmap.de/routed-bike/route/v1/driving/"
        const val DEFAULT_USER_AGENT = "MockGPS-Android/0.1 (https://github.com/poirotw66/MockGPS-Android)"
        const val DEFAULT_TIMEOUT_MILLIS = 12_000
        private const val MAX_TIMEOUT_MILLIS = 60_000
    }
}

/** Validated ordered inputs for an OSRM route request. */
class BicycleRouteRequest(waypoints: List<Coordinate>) {
    /** Snapshot caller input so validation cannot be invalidated by a later mutable-list edit. */
    val waypoints: List<Coordinate> = waypoints.toList()

    init {
        require(this.waypoints.size in MINIMUM_WAYPOINTS..MAXIMUM_WAYPOINTS) {
            "A bicycle route requires $MINIMUM_WAYPOINTS to $MAXIMUM_WAYPOINTS waypoints."
        }
        require(this.waypoints.all(::isRoutingCoordinateValid)) { "Waypoint contains an invalid coordinate." }
        require(this.waypoints.zipWithNext().none { (first, second) -> first == second }) {
            "Consecutive waypoints must be different."
        }
    }

    val origin: Coordinate get() = waypoints.first()
    val destination: Coordinate get() = waypoints.last()

    companion object {
        const val MINIMUM_WAYPOINTS = 2
        const val MAXIMUM_WAYPOINTS = 25
    }
}

/**
 * Thin client for the public FOSSGIS OSRM bicycle demo endpoint. It performs one request for an
 * explicit user action; callers must not auto-retry or poll this community service.
 */
class FossgisBicycleRoutingRepository(
    private val providerConfig: RoutingProviderConfig = RoutingProviderConfig(),
) : RoutingRepository {
    override suspend fun planBicycleRoute(
        waypoints: List<Coordinate>,
    ): PlannedRoute = withContext(Dispatchers.IO) {
        val endpoint = buildOsrmBicycleRouteUrl(BicycleRouteRequest(waypoints), providerConfig)
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = providerConfig.connectTimeoutMillis
            readTimeout = providerConfig.readTimeoutMillis
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", providerConfig.userAgent)
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                if (status == 408 || status == 429 || status >= 500) {
                    throw RoutingNetworkException("Routing service returned transient HTTP $status.")
                }
                throw RoutingException("Routing service returned HTTP $status.")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parseOsrmRoute(body)
        } catch (failure: RoutingException) {
            throw failure
        } catch (failure: IOException) {
            throw RoutingNetworkException("Routing service is unreachable.", failure)
        } finally {
            connection.disconnect()
        }
    }

}

/** Visible for JVM tests and future repository adapters; no network work is performed here. */
internal fun buildOsrmBicycleRouteUrl(
    request: BicycleRouteRequest,
    providerConfig: RoutingProviderConfig = RoutingProviderConfig(),
): String = buildString {
    append(providerConfig.normalizedBaseUrl)
    request.waypoints.joinTo(this, separator = ";") { coordinate ->
        "${coordinate.longitude},${coordinate.latitude}"
    }
    append("?overview=full&geometries=geojson&steps=false")
}

internal fun parseOsrmRoute(json: String): PlannedRoute {
    val root = runCatching { JSONObject(json) }
        .getOrElse { throw RoutingException("Routing response is not valid JSON.", it) }
    if (root.optString("code") != "Ok") throw RoutingException("No bicycle route was found.")
    val route = root.optJSONArray("routes")?.optJSONObject(0)
        ?: throw RoutingException("Routing response did not contain a route.")
    val coordinates = route.optJSONObject("geometry")?.optJSONArray("coordinates")
        ?: throw RoutingException("Routing response did not contain route geometry.")
    val points = buildList {
        for (index in 0 until coordinates.length()) {
            val pair = coordinates.optJSONArray(index)
                ?: throw RoutingException("Routing response contained an invalid coordinate.")
            if (pair.length() < 2) throw RoutingException("Routing response contained an invalid coordinate.")
            val longitude = pair.optDouble(0, Double.NaN)
            val latitude = pair.optDouble(1, Double.NaN)
            if (!latitude.isFinite() || latitude !in -90.0..90.0 ||
                !longitude.isFinite() || longitude !in -180.0..180.0
            ) throw RoutingException("Routing response contained an invalid coordinate.")
            add(Coordinate(latitude, longitude))
        }
    }
    if (points.size !in 2..MAX_ROUTE_POINTS) {
        throw RoutingException("The route is too complex for this preview.")
    }
    val distance = route.optDouble("distance", Double.NaN)
    if (!distance.isFinite() || distance <= 0.0 || distance > MAX_ROUTE_DISTANCE_METERS) {
        throw RoutingException("Choose two points within 100 km.")
    }
    return PlannedRoute(
        points = points,
        distanceMeters = distance,
        providerDurationSeconds = route.optDouble("duration", 0.0).coerceAtLeast(0.0),
    )
}

open class RoutingException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A transport-level failure for which a previously cached route may be safe to show. */
class RoutingNetworkException(message: String, cause: Throwable? = null) : RoutingException(message, cause)

/** No fresh provider result and no usable stale cached route were available. */
class RoutingUnavailableException(message: String, cause: RoutingNetworkException) : RoutingException(message, cause)

private const val MAX_ROUTE_POINTS = 1_000
private const val MAX_ROUTE_DISTANCE_METERS = 100_000.0

private fun isRoutingCoordinateValid(coordinate: Coordinate): Boolean =
    coordinate.latitude.isFinite() && coordinate.latitude in -90.0..90.0 &&
        coordinate.longitude.isFinite() && coordinate.longitude in -180.0..180.0
