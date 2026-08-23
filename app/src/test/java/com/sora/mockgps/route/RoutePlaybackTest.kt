package com.sora.mockgps.route

import com.sora.mockgps.core.model.Coordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutePlaybackTest {
    @Test
    fun `bicycle default is 18 kilometres per hour`() {
        assertEquals(18.0, RoutePlayback.BICYCLE_SPEED_KILOMETERS_PER_HOUR, 0.0)
        assertEquals(5.0, RoutePlayback.BICYCLE_SPEED_METERS_PER_SECOND, 0.0)
    }

    @Test
    fun `polyline measures segments and interpolates across a waypoint`() {
        val route = RoutePolyline(
            listOf(
                Coordinate(0.0, 0.0),
                Coordinate(0.0, 0.01),
                Coordinate(0.01, 0.01),
            ),
        )

        val firstSegment = route.positionAt(route.totalDistanceMeters / 4)
        val secondSegment = route.positionAt(route.totalDistanceMeters * 0.75)

        assertEquals(0.0, firstSegment.coordinate.latitude, 0.00001)
        assertTrue(firstSegment.coordinate.longitude in 0.0049..0.0051)
        assertTrue(secondSegment.coordinate.latitude in 0.0049..0.0051)
        assertEquals(0.01, secondSegment.coordinate.longitude, 0.00001)
    }

    @Test
    fun `bicycle playback advances at 18 kilometres per hour then reaches the end`() {
        val route = RoutePolyline(listOf(Coordinate(0.0, 0.0), Coordinate(0.0, 0.001)))
        val playback = RoutePlayback(route)

        playback.start(timestampMillis = 100)
        val afterTenSeconds = playback.advance(timestampMillis = 10_100)
        val reachedEnd = playback.advance(timestampMillis = 1_000_000)

        assertEquals(50.0, afterTenSeconds.position.distanceMeters, 0.0001)
        assertEquals(RoutePlaybackMode.RUNNING, afterTenSeconds.mode)
        assertEquals(RoutePlaybackMode.REACHED_END, reachedEnd.mode)
        assertEquals(route.totalDistanceMeters, reachedEnd.position.distanceMeters, 0.0)
    }

    @Test
    fun `paused time does not advance the route and stop is terminal`() {
        val route = RoutePolyline(listOf(Coordinate(0.0, 0.0), Coordinate(0.0, 0.01)))
        val playback = RoutePlayback(route, speedMetersPerSecond = 10.0)

        playback.start(0)
        playback.pause(1_000)
        val paused = playback.advance(10_000)
        val resumed = playback.resume(10_000)
        val running = playback.advance(11_000)
        val stopped = playback.stop()

        assertEquals(RoutePlaybackMode.PAUSED, paused.mode)
        assertEquals(10.0, paused.position.distanceMeters, 0.0)
        assertEquals(RoutePlaybackMode.RUNNING, resumed.mode)
        assertEquals(20.0, running.position.distanceMeters, 0.0)
        assertEquals(RoutePlaybackMode.STOPPED, stopped.mode)
        assertEquals(20.0, stopped.position.distanceMeters, 0.0)
    }
}
