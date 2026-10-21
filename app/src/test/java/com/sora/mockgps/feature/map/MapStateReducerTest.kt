package com.sora.mockgps.feature.map

import org.maplibre.compose.camera.CameraPosition
import org.maplibre.spatialk.geojson.Position
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import com.sora.mockgps.core.model.Coordinate
import com.sora.mockgps.route.PlannedRoute
import com.sora.mockgps.route.RouteTransportMode

class MapStateReducerTest {
    @Test
    fun `camera idle saves camera without changing selected coordinate`() {
        val state = MapUiState()
        val position = CameraPosition(
            target = Position(latitude = 35.681236, longitude = 139.767125),
            zoom = 17.5,
            tilt = 32.0,
            bearing = 91.0,
        )

        val result = MapStateReducer.cameraIdle(state, position)

        assertEquals(state.pendingCoordinate, result.pendingCoordinate)
        assertEquals(35.681236, result.camera.coordinate.latitude, 0.0)
        assertEquals(139.767125, result.camera.coordinate.longitude, 0.0)
        assertEquals(17.5f, result.camera.zoom)
        assertEquals(32f, result.camera.tilt)
        assertEquals(91f, result.camera.bearing)
    }

    @Test
    fun `explicit selection changes pending coordinate without moving camera`() {
        val state = MapUiState()
        val coordinate = com.sora.mockgps.core.model.Coordinate(35.681236, 139.767125)

        val result = MapStateReducer.selectCoordinate(state, coordinate)

        assertEquals(coordinate, result.pendingCoordinate)
        assertEquals(state.camera, result.camera)
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
        assertEquals(RouteTransportMode.Bicycle, result.routeTransportMode)
        assertEquals(true, result.showRouteControlPoints)
    }

    @Test
    fun `back clears a shape preview instead of opening endpoint selection`() {
        val start = Coordinate(25.03, 121.50)
        val end = Coordinate(25.05, 121.52)
        val state = MapUiState(
            isRoutePlanningMode = true,
            routeOrigin = start,
            routeDestination = end,
            plannedRoute = PlannedRoute(listOf(start, end), 1_000.0, 0.0),
            showRouteControlPoints = false,
            activeRouteName = "Heart",
        )
        assertEquals(RoutePlanningStep.Preview, state.routePlanningStep)
        assertTrue(routeBackClearsPreview(state))
    }

    @Test
    fun `back clears a saved route preview`() {
        val points = listOf(
            Coordinate(25.033964, 121.564468),
            Coordinate(25.047675, 121.517055),
        )
        val state = MapStateReducer.loadedRoutePreview(
            state = MapUiState(),
            points = points,
            distanceMeters = 5_000.0,
            name = "Morning ride",
            savedRouteId = 42,
        )
        assertTrue(routeBackClearsPreview(state))
    }

    @Test
    fun `back keeps a manual preview on endpoint editing`() {
        val start = Coordinate(25.03, 121.50)
        val end = Coordinate(25.05, 121.52)
        val state = MapUiState(
            isRoutePlanningMode = true,
            routeOrigin = start,
            routeDestination = end,
            plannedRoute = PlannedRoute(listOf(start, end), 1_000.0, 0.0),
            showRouteControlPoints = true,
        )
        assertFalse(routeBackClearsPreview(state))
    }

    @Test
    fun `route point labels span A through Z`() {
        assertEquals("A", routePointLabel(0))
        assertEquals("B", routePointLabel(1))
        assertEquals("Z", routePointLabel(25))
        assertThrows(IllegalArgumentException::class.java) { routePointLabel(26) }
    }
}
