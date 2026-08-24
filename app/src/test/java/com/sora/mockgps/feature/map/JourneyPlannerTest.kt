package com.sora.mockgps.feature.map

import com.sora.mockgps.core.model.Coordinate
import com.sora.mockgps.route.GeoMath
import com.sora.mockgps.route.RoutePolyline
import com.sora.mockgps.route.RouteTransportMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JourneyPlannerTest {
    @Test
    fun `automatic journey is closed and scales with duration and transport`() {
        val shortWalk = JourneyPlanner.automaticControlPoints(
            AutoJourneyOptions(JourneyRegion.Taiwan, JourneyDuration.Short, RouteTransportMode.Walk),
        )
        val longDrive = JourneyPlanner.automaticControlPoints(
            AutoJourneyOptions(JourneyRegion.Taiwan, JourneyDuration.Long, RouteTransportMode.Drive),
        )

        assertEquals(shortWalk.first(), shortWalk.last())
        assertEquals(longDrive.first(), longDrive.last())
        assertTrue(RoutePolyline(longDrive).totalDistanceMeters > RoutePolyline(shortWalk).totalDistanceMeters)
        assertTrue(RoutePolyline(longDrive).totalDistanceMeters < 75_000.0)
    }

    @Test
    fun `regions center journeys in their selected countries`() {
        JourneyRegion.entries.forEach { region ->
            val points = JourneyPlanner.automaticControlPoints(AutoJourneyOptions(region = region))
            assertTrue(points.all { GeoMath.distanceMeters(it, region.center) < 15_000.0 })
        }
    }

    @Test
    fun `all shapes are closed nonzero routes around the selected center`() {
        val center = Coordinate(25.0375, 121.5637)

        RouteShape.entries.forEach { shape ->
            val points = JourneyPlanner.shapePoints(center, shape)
            assertEquals(points.first(), points.last())
            assertTrue(points.size in 11..25)
            assertTrue(JourneyPlanner.shapeDistanceMeters(center, shape) > 1_000.0)
            assertTrue(points.all { GeoMath.distanceMeters(it, center) <= 1_100.0 })
        }
    }
}