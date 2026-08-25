package com.sora.mockgps.feature.map

import com.sora.mockgps.route.PlannedRoute
import com.sora.mockgps.route.RouteTransportMode
import com.sora.mockgps.route.RoutingRepository
import kotlin.random.Random

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

    fun success(state: MapUiState, route: PlannedRoute): MapUiState = state.copy(
        plannedRoute = route,
        isPlanningRoute = false,
        routeError = null,
        automaticJourneyRecoveryAvailable = false,
        automaticJourneyRecoveryKind = null,
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