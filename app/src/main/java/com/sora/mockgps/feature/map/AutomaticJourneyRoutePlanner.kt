package com.sora.mockgps.feature.map

import com.sora.mockgps.route.PlannedRoute
import com.sora.mockgps.route.RouteTransportMode
import com.sora.mockgps.route.RoutingRepository
import kotlin.random.Random

internal data class RoadAdaptedPlanResult(
    val journey: GeneratedJourney,
    val route: PlannedRoute,
)

internal class AutomaticJourneyRoutePlanner(
    private val routingRepository: RoutingRepository,
    private val random: Random = Random.Default,
) {
    private var previousLandmark: JourneyLandmark? = null

    fun generate(options: AutoJourneyOptions): GeneratedJourney = JourneyPlanner.automaticJourney(
        options = options,
        random = random,
        excludedLandmark = previousLandmark,
    ).also { previousLandmark = it.landmark }

    suspend fun plan(journey: GeneratedJourney, transportMode: RouteTransportMode): PlannedRoute =
        routingRepository.planRoute(journey.points, transportMode)

    /**
     * Plans a road-adapted route, shrinking the geometric shape and retrying when the
     * adapted distance exceeds the duration-based target.
     */
    suspend fun planWithinTargetDistance(
        journey: GeneratedJourney,
        transportMode: RouteTransportMode,
        targetDistanceMeters: Double,
        maxAttempts: Int = MAX_SHRINK_ATTEMPTS,
    ): RoadAdaptedPlanResult {
        var current = journey
        var best: RoadAdaptedPlanResult? = null

        repeat(maxAttempts.coerceAtLeast(1)) {
            val route = plan(current, transportMode)
            val candidate = RoadAdaptedPlanResult(current, route)
            best = when {
                best == null -> candidate
                route.distanceMeters < best!!.route.distanceMeters -> candidate
                else -> best
            }
            if (route.distanceMeters <= targetDistanceMeters * DISTANCE_OVERAGE_TOLERANCE) {
                return candidate
            }
            val scale = (targetDistanceMeters / route.distanceMeters).coerceIn(MIN_RADIUS_SCALE, MAX_RADIUS_SCALE)
            val nextRadius = JourneyPlanner.estimatedShapeRadiusMeters(current) * scale
            val shrunk = JourneyPlanner.withShapeRadius(current, nextRadius)
            if (shrunk.points == current.points) {
                return best!!
            }
            current = shrunk
        }
        return best!!
    }

    private companion object {
        const val MAX_SHRINK_ATTEMPTS = 4
        const val DISTANCE_OVERAGE_TOLERANCE = 1.05
        const val MIN_RADIUS_SCALE = 0.50
        const val MAX_RADIUS_SCALE = 0.92
    }
}

internal object AutomaticJourneyStateReducer {
    fun planning(
        state: MapUiState,
        journey: GeneratedJourney,
        transportMode: RouteTransportMode,
        routeName: String,
    ): MapUiState = state.copy(
        isRoutePlanningMode = true,
        routeOrigin = journey.points.first(),
        routeDestination = journey.points.last(),
        routeWaypoints = journey.points,
        plannedRoute = null,
        routeTransportMode = transportMode,
        showRouteControlPoints = false,
        isPlanningRoute = true,
        routeError = null,
        automaticJourneyRecoveryAvailable = false,
        automaticJourneyRecoveryKind = null,
        activeSavedRouteId = null,
        activeRouteName = routeName,
    )

    fun success(
        state: MapUiState,
        route: PlannedRoute,
        journey: GeneratedJourney? = null,
    ): MapUiState = state.copy(
        plannedRoute = route,
        isPlanningRoute = false,
        routeError = null,
        automaticJourneyRecoveryAvailable = false,
        automaticJourneyRecoveryKind = null,
        routeOrigin = journey?.points?.first() ?: state.routeOrigin,
        routeDestination = journey?.points?.last() ?: state.routeDestination,
        routeWaypoints = journey?.points ?: state.routeWaypoints,
    )

    fun failure(
        state: MapUiState,
        error: String,
        recoveryKind: AutomaticJourneyRecoveryKind,
    ): MapUiState = state.copy(
        isPlanningRoute = false,
        routeError = error,
        automaticJourneyRecoveryAvailable = true,
        automaticJourneyRecoveryKind = recoveryKind,
    )
}
