package com.sora.mockgps.route

import com.sora.mockgps.core.model.Coordinate
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class PlannedRoute(
    val points: List<Coordinate>,
    val distanceMeters: Double,
    val providerDurationSeconds: Double,
) {
    init {
        require(points.size >= 2) { "A planned route needs at least two points." }
        require(distanceMeters.isFinite() && distanceMeters > 0.0) { "Route distance is invalid." }
    }

    val simulatedDurationSeconds: Double
        get() = distanceMeters / RoutePlayback.BICYCLE_SPEED_METERS_PER_SECOND
}

interface RoutingRepository {
    suspend fun planBicycleRoute(origin: Coordinate, destination: Coordinate): PlannedRoute
}

/**
 * Thin client for the public FOSSGIS OSRM bicycle demo endpoint. It performs one request for an
 * explicit user action; callers must not auto-retry or poll this community service.
 */
class FossgisBicycleRoutingRepository : RoutingRepository {
    override suspend fun planBicycleRoute(
        origin: Coordinate,
        destination: Coordinate,
    ): PlannedRoute = withContext(Dispatchers.IO) {
        val endpoint = buildString {
            append(BASE_URL)
            append(origin.longitude).append(',').append(origin.latitude)
            append(';')
            append(destination.longitude).append(',').append(destination.latitude)
            append("?overview=full&geometries=geojson&steps=false")
        }
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MILLIS
            readTimeout = TIMEOUT_MILLIS
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            val status = connection.responseCode
            if (status !in 200..299) {
                throw RoutingException("Routing service returned HTTP $status.")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parseOsrmRoute(body)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val BASE_URL = "https://routing.openstreetmap.de/routed-bike/route/v1/driving/"
        const val USER_AGENT = "MockGPS-Android/0.1 (https://github.com/poirotw66/MockGPS-Android)"
        const val TIMEOUT_MILLIS = 12_000
    }
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

class RoutingException(message: String, cause: Throwable? = null) : Exception(message, cause)

private const val MAX_ROUTE_POINTS = 1_000
private const val MAX_ROUTE_DISTANCE_METERS = 100_000.0
