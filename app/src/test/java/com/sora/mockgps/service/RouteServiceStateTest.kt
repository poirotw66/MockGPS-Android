package com.sora.mockgps.service

import com.sora.mockgps.core.model.Coordinate
import com.sora.mockgps.route.MovementProfile
import com.sora.mockgps.route.RouteExecution
import com.sora.mockgps.route.RoutePolyline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteServiceStateTest {
    @Test
    fun `route progress exposes the current coordinate and movement metrics`() {
        val route = RoutePolyline(listOf(Coordinate(0.0, 0.0), Coordinate(0.0, 0.01)))
        val execution = RouteExecution(route, MovementProfile.Custom(36.0))

        execution.start(0)
        val progress = execution.advance(1_000).toRouteProgress()

        assertEquals(10.0, progress.travelledDistanceMeters, 0.000001)
        assertEquals(route.totalDistanceMeters - 10.0, progress.remainingDistanceMeters, 0.000001)
        assertEquals(10.0, progress.speedMetersPerSecond, 0.000001)
        assertTrue(progress.bearingDegrees >= 0f && progress.bearingDegrees < 360f)
        assertEquals(0.0, progress.coordinate.latitude, 0.000001)
    }

    @Test
    fun `completed route state retains final progress as an observable outcome`() {
        val progress = RouteProgress(
            coordinate = Coordinate(25.0, 121.0),
            travelledDistanceMeters = 100.0,
            remainingDistanceMeters = 0.0,
            totalDistanceMeters = 100.0,
            speedMetersPerSecond = 5.0,
            bearingDegrees = 90f,
        )

        val state: RouteServiceState = RouteCompleted(progress)

        assertEquals(progress, (state as RouteCompleted).progress)
    }
}
