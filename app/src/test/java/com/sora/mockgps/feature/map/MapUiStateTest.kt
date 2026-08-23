package com.sora.mockgps.feature.map

import com.sora.mockgps.core.model.Coordinate
import com.sora.mockgps.route.PlannedRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.spatialk.geojson.Position

class MapUiStateTest {
    private val start = Coordinate(25.033964, 121.564468)
    private val destination = Coordinate(25.032691, 121.562697)

    @Test
    fun `route planning exposes one explicit step at a time`() {
        assertEquals(RoutePlanningStep.Inactive, MapUiState().routePlanningStep)
        assertEquals(
            RoutePlanningStep.SelectStart,
            MapUiState(isRoutePlanningMode = true).routePlanningStep,
        )
        assertEquals(
            RoutePlanningStep.SelectDestination,
            MapUiState(isRoutePlanningMode = true, routeOrigin = start).routePlanningStep,
        )
        assertEquals(
            RoutePlanningStep.ReadyToPreview,
            MapUiState(
                isRoutePlanningMode = true,
                routeOrigin = start,
                routeDestination = destination,
            ).routePlanningStep,
        )
    }

    @Test
    fun `planning and preview override endpoint selection steps`() {
        val draft = MapUiState(
            isRoutePlanningMode = true,
            routeOrigin = start,
            routeDestination = destination,
        )
        assertEquals(
            RoutePlanningStep.Planning,
            draft.copy(isPlanningRoute = true).routePlanningStep,
        )
        assertEquals(
            RoutePlanningStep.Preview,
            draft.copy(
                plannedRoute = PlannedRoute(
                    points = listOf(start, destination),
                    distanceMeters = 250.0,
                    providerDurationSeconds = 60.0,
                ),
            ).routePlanningStep,
        )
    }

    @Test
    fun `route preview camera centres and zooms out to show the route`() {
        val fallback = CameraPosition(
            target = Position(latitude = start.latitude, longitude = start.longitude),
            zoom = 17.0,
        )
        val camera = listOf(
            Coordinate(25.0, 121.5),
            Coordinate(25.1, 121.6),
        ).previewCameraPosition(fallback)

        assertEquals(25.05, camera.target.latitude, 0.000001)
        assertEquals(121.55, camera.target.longitude, 0.000001)
        assertTrue(camera.zoom < fallback.zoom)
    }
}
