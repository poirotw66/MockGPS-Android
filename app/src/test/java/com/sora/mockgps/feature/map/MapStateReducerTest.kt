package com.sora.mockgps.feature.map

import org.maplibre.compose.camera.CameraPosition
import org.maplibre.spatialk.geojson.Position
import org.junit.Assert.assertEquals
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
        val dark = MapStateReducer.toggleMapType(MapUiState())
        val light = MapStateReducer.toggleMapType(dark)

        assertEquals(MapDisplayType.Dark, dark.mapType)
        assertEquals(MapDisplayType.Light, light.mapType)
    }

    @Test
    fun `retry resets loading and increments render key`() {
        val state = MapUiState(loadingState = MapLoadingState.Error, mapRenderKey = 4)

        val result = MapStateReducer.retry(state)

        assertEquals(MapLoadingState.Loading, result.loadingState)
        assertEquals(5, result.mapRenderKey)
    }
}
