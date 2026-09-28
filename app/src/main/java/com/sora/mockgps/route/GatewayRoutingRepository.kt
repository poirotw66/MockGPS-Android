package com.sora.mockgps.route

import com.sora.mockgps.core.model.Coordinate
import com.sora.mockgps.core.network.AttributionLink
import com.sora.mockgps.core.network.GatewayClient
import com.sora.mockgps.core.network.GatewayClientConfig
import com.sora.mockgps.core.network.GatewayException
import com.sora.mockgps.core.network.parseAttributionArray
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class GatewayPlannedRoute(
    val route: PlannedRoute,
    val mode: RouteTransportMode,
    val attribution: List<AttributionLink>,
    val providerRevision: String,
)

class GatewayRoutingRepository(
    private val client: GatewayClient,
) : RoutingRepository {
    override suspend fun planRoute(
        waypoints: List<Coordinate>,
        transportMode: RouteTransportMode,
    ): PlannedRoute {
        val request = BicycleRouteRequest(waypoints)
        validateOnlineInputDistance(request.waypoints)
        val payload = JSONObject().apply {
            put("mode", transportMode.wireName)
            put("waypoints", JSONArray().apply {
                request.waypoints.forEach { coordinate ->
                    put(
                        JSONObject().apply {
                            put("latitude", coordinate.latitude)
                            put("longitude", coordinate.longitude)
                        },
                    )
                }
            })
        }
        val response = try {
            client.postJson(
                path = "/v1/route",
                payload = payload,
                maxRequestBytes = GatewayClientConfig.ROUTE_REQUEST_MAX_BYTES,
                maxResponseBytes = 512 * 1024,
            )
        } catch (error: GatewayException) {
            throw routingExceptionFromGateway(error)
        }
        return parseGatewayRoute(response.body, transportMode).route
    }
}

internal fun routingExceptionFromGateway(error: GatewayException): RoutingException = when (error) {
    is GatewayException.NoRoute -> RoutingNoRouteException()
    is GatewayException.RouteTooComplex -> RoutingTooComplexException()
    is GatewayException.RouteOutOfScope,
    is GatewayException.InvalidRequest,
    is GatewayException.RequestTooLarge,
    -> RoutingOutOfScopeException(error.code.name)
    is GatewayException.ClientRateLimited,
    is GatewayException.GlobalQuotaExhausted,
    -> RoutingQuotaException(error.code.name, error)
    is GatewayException.ProviderTimeout,
    is GatewayException.Unavailable,
    is GatewayException.ServiceDisabled,
    is GatewayException.BudgetUnavailable,
    is GatewayException.ProviderUnavailable,
    is GatewayException.InvalidProviderResponse,
    -> RoutingNetworkException(error.code.name, error)
    else -> RoutingNetworkException(error.code.name, error)
}

internal fun parseGatewayRoute(body: String, expectedMode: RouteTransportMode): GatewayPlannedRoute {
    val root = runCatching { JSONObject(body) }.getOrElse {
        throw RoutingException("Routing response is not valid JSON.", it)
    }
    val pointsJson = root.optJSONArray("points")
        ?: throw RoutingException("Routing response did not contain route geometry.")
    val points = buildList {
        for (index in 0 until pointsJson.length()) {
            val item = pointsJson.optJSONObject(index)
                ?: throw RoutingException("Routing response contained an invalid coordinate.")
            val latitude = item.optDouble("latitude", Double.NaN)
            val longitude = item.optDouble("longitude", Double.NaN)
            if (!latitude.isFinite() || latitude !in -90.0..90.0 ||
                !longitude.isFinite() || longitude !in -180.0..180.0
            ) {
                throw RoutingException("Routing response contained an invalid coordinate.")
            }
            add(Coordinate(latitude, longitude))
        }
    }
    if (points.size !in 2..MAX_GATEWAY_ROUTE_POINTS) {
        throw RoutingTooComplexException()
    }
    validateContinuousGeometry(points)
    val distance = root.optDouble("distanceMeters", Double.NaN)
    if (!distance.isFinite() || distance <= 0.0) {
        throw RoutingException("Route distance is invalid.")
    }
    val mode = root.optString("mode").ifBlank { expectedMode.wireName }
    if (mode != expectedMode.wireName) {
        throw RoutingException("Routing response mode did not match the request.")
    }
    return GatewayPlannedRoute(
        route = PlannedRoute(
            points = points,
            distanceMeters = distance,
            providerDurationSeconds = root.optDouble("providerDurationSeconds", 0.0).coerceAtLeast(0.0),
        ),
        mode = expectedMode,
        attribution = parseAttributionArray(root.optJSONArray("attribution")),
        providerRevision = root.optString("providerRevision").trim(),
    )
}

internal fun validateOnlineInputDistance(waypoints: List<Coordinate>) {
    val total = waypoints.zipWithNext().sumOf { (first, second) -> haversineMeters(first, second) }
    if (total > MAX_ONLINE_INPUT_DISTANCE_METERS) {
        throw RoutingOutOfScopeException()
    }
}

internal fun validateContinuousGeometry(points: List<Coordinate>) {
    points.zipWithNext().forEach { (first, second) ->
        if (!first.latitude.isFinite() || !second.latitude.isFinite()) {
            throw RoutingException("Routing geometry contained a gap.")
        }
    }
}

internal fun haversineMeters(first: Coordinate, second: Coordinate): Double {
    val lat1 = Math.toRadians(first.latitude)
    val lat2 = Math.toRadians(second.latitude)
    val dLat = lat2 - lat1
    val dLon = Math.toRadians(second.longitude - first.longitude)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * EARTH_RADIUS_METERS * atan2(sqrt(a), sqrt(1 - a))
}

private const val MAX_GATEWAY_ROUTE_POINTS = 10_000
private const val MAX_ONLINE_INPUT_DISTANCE_METERS = 100_000.0
private const val EARTH_RADIUS_METERS = 6_371_000.0
