package com.sora.mockgps.feature.map

import com.sora.mockgps.core.model.Coordinate
import com.sora.mockgps.route.PlannedRoute
import com.sora.mockgps.route.RouteTransportMode
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.spatialk.geojson.Position

/** Pure state transitions kept separate from Android lifecycle code for deterministic JVM tests. */
internal object MapStateReducer {
    fun cameraIdle(state: MapUiState, position: CameraPosition): MapUiState {
        val coordinate = Coordinate(position.target.latitude, position.target.longitude)
        if (!coordinate.isValid()) return state
        if (
            coordinate == state.camera.coordinate &&
            position.zoom.toFloat() == state.camera.zoom &&
            position.bearing.toFloat() == state.camera.bearing &&
            position.tilt.toFloat() == state.camera.tilt
        ) return state
        return state.copy(
            camera = MapCamera(
                coordinate = coordinate,
                zoom = position.zoom.toFloat(),
                bearing = position.bearing.toFloat(),
                tilt = position.tilt.toFloat(),
            ),
        )
    }

    fun selectCoordinate(state: MapUiState, coordinate: Coordinate): MapUiState {
        if (!coordinate.isValid() || coordinate == state.pendingCoordinate) return state
        return state.copy(pendingCoordinate = coordinate)
    }

    fun mapLoaded(state: MapUiState): MapUiState =
        state.copy(loadingState = MapLoadingState.Ready)

    fun retry(state: MapUiState): MapUiState = state.copy(
        loadingState = MapLoadingState.Loading,
        mapRenderKey = state.mapRenderKey + 1,
    )

    fun toggleMapType(state: MapUiState): MapUiState = state.copy(
        mapType = when (state.mapType) {
            MapDisplayType.Light -> MapDisplayType.Dark
            MapDisplayType.Dark -> MapDisplayType.Light
        },
        loadingState = MapLoadingState.Loading,
    )

    fun loadedRoutePreview(
        state: MapUiState,
        points: List<Coordinate>,
        distanceMeters: Double,
        name: String,
        savedRouteId: Long?,
    ): MapUiState {
        require(points.size >= 2) { "A route preview needs at least two points." }
        return state.copy(
            isRoutePlanningMode = true,
            routeOrigin = points.first(),
            routeDestination = points.last(),
            routeWaypoints = listOf(points.first(), points.last()),
            plannedRoute = PlannedRoute(points, distanceMeters, providerDurationSeconds = 0.0),
            routeTransportMode = RouteTransportMode.Bicycle,
            showRouteControlPoints = true,
            isPlanningRoute = false,
            routeError = null,
            automaticJourneyRecoveryAvailable = false,
            automaticJourneyRecoveryKind = null,
            activeRouteName = name,
            activeSavedRouteId = savedRouteId,
        )
    }

    private fun Coordinate.isValid(): Boolean =
        latitude.isFinite() && latitude in -90.0..90.0 &&
            longitude.isFinite() && longitude in -180.0..180.0
}
