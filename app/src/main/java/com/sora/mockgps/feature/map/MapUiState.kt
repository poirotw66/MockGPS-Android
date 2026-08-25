package com.sora.mockgps.feature.map

import com.sora.mockgps.core.model.Coordinate
import com.sora.mockgps.service.MockLocationForegroundService
import com.sora.mockgps.route.PlannedRoute
import com.sora.mockgps.route.RouteTransportMode
import com.sora.mockgps.feature.search.PlaceSearchResult

/** Immutable state rendered by the map selection screen. */
data class MapUiState(
    /** The explicitly selected coordinate, not necessarily the injected or camera-centre coordinate. */
    val pendingCoordinate: Coordinate = MockLocationForegroundService.DEFAULT_COORDINATE,
    /** Full camera state is retained by the ViewModel across Activity recreation. */
    val camera: MapCamera = MapCamera.from(MockLocationForegroundService.DEFAULT_COORDINATE),
    val mapType: MapDisplayType = MapDisplayType.Light,
    val loadingState: MapLoadingState = MapLoadingState.Loading,
    // Changes when Retry is pressed so the Maps composable is recreated.
    val mapRenderKey: Int = 0,
    val isRoutePlanningMode: Boolean = false,
    val routeOrigin: Coordinate? = null,
    val routeDestination: Coordinate? = null,
    /** Ordered routing controls: start, optional intermediate stops, destination. */
    val routeWaypoints: List<Coordinate> = emptyList(),
    val plannedRoute: PlannedRoute? = null,
    val routeTransportMode: RouteTransportMode = RouteTransportMode.Bicycle,
    val showRouteControlPoints: Boolean = true,
    val isPlanningRoute: Boolean = false,
    val routeError: String? = null,
    val automaticJourneyRecoveryAvailable: Boolean = false,
    val favoriteMessage: String? = null,
    /** The saved route that supplied [plannedRoute], if it was loaded from storage. */
    val activeSavedRouteId: Long? = null,
    /** A human-readable route name retained while it is being previewed. */
    val activeRouteName: String? = null,
    /** One-shot success/failure/export event for route persistence and interchange UI. */
    val routeOperationResult: RouteOperationResult? = null,
    val placeSearchQuery: String = "",
    val isPlaceSearching: Boolean = false,
    val placeSearchResults: List<PlaceSearchResult> = emptyList(),
    val placeSearchError: PlaceSearchError? = null,
    val showCoordinates: Boolean = true,
    val updateIntervalMillis: Long = 1_000L,
    val accuracyMeters: Float = 5f,
) {
    val routePlanningStep: RoutePlanningStep
        get() = when {
            !isRoutePlanningMode -> RoutePlanningStep.Inactive
            isPlanningRoute -> RoutePlanningStep.Planning
            plannedRoute != null -> RoutePlanningStep.Preview
            routeOrigin == null -> RoutePlanningStep.SelectStart
            routeDestination == null -> RoutePlanningStep.SelectDestination
            else -> RoutePlanningStep.ReadyToPreview
        }
}

enum class PlaceSearchError { Network, RateLimited, InvalidResponse }

data class RouteOperationResult(
    val message: String,
    val isError: Boolean = false,
    val export: RouteExport? = null,
)

data class RouteExport(
    val mimeType: String,
    val fileName: String,
    val content: String,
)

enum class RoutePlanningStep {
    Inactive,
    SelectStart,
    SelectDestination,
    ReadyToPreview,
    Planning,
    Preview,
}

data class MapCamera(
    val coordinate: Coordinate,
    val zoom: Float = DEFAULT_MAP_ZOOM,
    val bearing: Float = 0f,
    val tilt: Float = 0f,
) {
    companion object {
        fun from(coordinate: Coordinate) = MapCamera(coordinate = coordinate)
    }
}

enum class MapDisplayType {
    Light,
    Dark,
}

enum class MapLoadingState {
    Loading,
    Ready,
    Error,
}

const val DEFAULT_MAP_ZOOM = 15f
