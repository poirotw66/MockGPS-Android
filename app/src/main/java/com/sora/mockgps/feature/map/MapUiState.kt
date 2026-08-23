package com.sora.mockgps.feature.map

import com.sora.mockgps.core.model.Coordinate
import com.sora.mockgps.service.MockLocationForegroundService
import com.sora.mockgps.route.PlannedRoute

/** Immutable state rendered by the map selection screen. */
data class MapUiState(
    /** The coordinate currently under the centre reticle, not necessarily the injected one. */
    val pendingCoordinate: Coordinate = MockLocationForegroundService.DEFAULT_COORDINATE,
    /** Full camera state is retained by the ViewModel across Activity recreation. */
    val camera: MapCamera = MapCamera.from(MockLocationForegroundService.DEFAULT_COORDINATE),
    val mapType: MapDisplayType = MapDisplayType.Light,
    val loadingState: MapLoadingState = MapLoadingState.Loading,
    // Changes when Retry is pressed so the Maps composable is recreated.
    val mapRenderKey: Int = 0,
    val routeOrigin: Coordinate? = null,
    val plannedRoute: PlannedRoute? = null,
    val isPlanningRoute: Boolean = false,
    val routeError: String? = null,
    val favoriteMessage: String? = null,
)

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
