package com.sora.mockgps.feature.map

import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Test

class MapStateReducerTest {
    @Test
    fun `camera idle saves coordinate and full camera position`() {
        val position = CameraPosition(LatLng(35.681236, 139.767125), 17.5f, 32f, 91f)

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
        val satellite = MapStateReducer.toggleMapType(MapUiState())
        val normal = MapStateReducer.toggleMapType(satellite)

        assertEquals(MapDisplayType.Satellite, satellite.mapType)
        assertEquals(MapDisplayType.Normal, normal.mapType)
    }

    @Test
    fun `retry resets loading and increments render key`() {
        val state = MapUiState(loadingState = MapLoadingState.Error, mapRenderKey = 4)

        val result = MapStateReducer.retry(state)

        assertEquals(MapLoadingState.Loading, result.loadingState)
        assertEquals(5, result.mapRenderKey)
    }
}
