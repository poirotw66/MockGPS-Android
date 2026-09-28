package com.sora.mockgps.route

import com.sora.mockgps.core.model.Coordinate

/** Online routing is closed unless GET /v1/config currently enables it. */
class RoutingServiceDisabledException(
    message: String = "Online routing is unavailable.",
) : RoutingException(message)

class FeatureGatedRoutingRepository(
    private val delegate: RoutingRepository,
    private val routingEnabled: () -> Boolean,
) : RoutingRepository {
    override suspend fun planRoute(
        waypoints: List<Coordinate>,
        transportMode: RouteTransportMode,
    ): PlannedRoute {
        if (!routingEnabled()) {
            throw RoutingServiceDisabledException()
        }
        return delegate.planRoute(waypoints, transportMode)
    }
}
