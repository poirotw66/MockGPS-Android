package com.sora.mockgps.feature.map

import org.maplibre.compose.camera.CameraPosition
import org.maplibre.spatialk.geojson.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class MapStateReducerTest {
    @Test
    fun `camera idle saves coordinate and full camera position`() {
        val position = CameraPosition(
            target = Position(latitude = 35.681236, longitude = 139.767125),
            zoom = 17.5,
            tilt = 32.0,
            bearing = 91.0,
        )

        val result = MapStateReducer.cameraIdle(MapUiState(), position)

        assertEquals(35.681236, result.pendingCoordinate.latitude, 0.0)
        assertEquals(139.767125, result.pendingCoordinate.longitude, 0.0)
        assertEquals(17.5f, result.camera.zoom)
        assertEquals(32f, result.camera.tilt)
        assertEquals(91f, result.camera.bearing)
    }

    @Test
    fun `map loaded moves loading state to ready`() {
        val state = MapUiState(loadingState = MapLoadingState.Loading)

        assertEquals(MapLoadingState.Ready, MapStateReducer.mapLoaded(state).loadingState)
    }

    @Test
    fun `map type toggles in both directions`() {
        val dark = MapStateReducer.toggleMapType(MapUiState(loadingState = MapLoadingState.Ready))
        val light = MapStateReducer.toggleMapType(dark)

        assertEquals(MapDisplayType.Dark, dark.mapType)
        assertEquals(MapDisplayType.Light, light.mapType)
        assertEquals(MapLoadingState.Loading, dark.loadingState)
        assertEquals(MapLoadingState.Loading, light.loadingState)
    }

    @Test
    fun `camera idle ignores an unchanged camera`() {
        val state = MapUiState()
        val position = CameraPosition(
            target = Position(
                latitude = state.camera.coordinate.latitude,
                longitude = state.camera.coordinate.longitude,
            ),
            zoom = state.camera.zoom.toDouble(),
            tilt = state.camera.tilt.toDouble(),
            bearing = state.camera.bearing.toDouble(),
        )

        assertSame(state, MapStateReducer.cameraIdle(state, position))
    }

    @Test
    fun `retry resets loading and increments render key`() {
        val state = MapUiState(loadingState = MapLoadingState.Error, mapRenderKey = 4)

        val result = MapStateReducer.retry(state)

        assertEquals(MapLoadingState.Loading, result.loadingState)
        assertEquals(5, result.mapRenderKey)
    }

    @Test
    fun `loaded saved route becomes a ready preview with endpoint metadata`() {
        val points = listOf(
            com.sora.mockgps.core.model.Coordinate(25.033964, 121.564468),
            com.sora.mockgps.core.model.Coordinate(25.047675, 121.517055),
        )

        val result = MapStateReducer.loadedRoutePreview(
            state = MapUiState(),
            points = points,
            distanceMeters = 5_000.0,
            name = "Morning ride",
            savedRouteId = 42,
        )

        assertEquals(RoutePlanningStep.Preview, result.routePlanningStep)
        assertEquals(points.first(), result.routeOrigin)
        assertEquals(points.last(), result.routeDestination)
        assertEquals("Morning ride", result.activeRouteName)
        assertEquals(42L, result.activeSavedRouteId)
        assertEquals(points, result.plannedRoute?.points)
    }
}
