package com.sora.mockgps.route

import com.sora.mockgps.core.model.Coordinate

/** Online routing is closed unless GET /v1/config currently enables it. */
class RoutingServiceDisabledException(
    message: String = "Online routing is unavailable.",
) : RoutingException(message)

class FeatureGatedRoutingRepository(
    private val delegate: RoutingRepository,
    private val routingEnabled: () -> Boolean,
    private val refreshRoutingAvailability: suspend () -> Unit = {},
    private val routingConfirmedDisabled: () -> Boolean = { !routingEnabled() },
) : RoutingRepository {
    override suspend fun planRoute(
        waypoints: List<Coordinate>,
        transportMode: RouteTransportMode,
    ): PlannedRoute {
        if (!routingEnabled()) {
            refreshRoutingAvailability()
        }
        if (routingEnabled()) {
            return delegate.planRoute(waypoints, transportMode)
        }
        if (routingConfirmedDisabled()) {
            throw RoutingServiceDisabledException()
        }
        throw RoutingNetworkException("Routing configuration is unavailable.")
    }
}
