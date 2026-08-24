package com.sora.mockgps.feature.map

import com.sora.mockgps.core.model.Coordinate
import com.sora.mockgps.route.GeoMath
import com.sora.mockgps.route.RoutePolyline
import com.sora.mockgps.route.RouteTransportMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class JourneyPlannerTest {
    @Test
    fun `automatic journey is closed and scales with duration and transport`() {
        val shortWalk = JourneyPlanner.automaticJourney(
            AutoJourneyOptions(JourneyRegion.Taiwan, JourneyDuration.Short, RouteTransportMode.Walk),
            Random(1),
        ).points
        val longDrive = JourneyPlanner.automaticJourney(
            AutoJourneyOptions(JourneyRegion.Taiwan, JourneyDuration.Long, RouteTransportMode.Drive),
            Random(1),
        ).points

        assertEquals(shortWalk.first(), shortWalk.last())
        assertEquals(longDrive.first(), longDrive.last())
        assertTrue(RoutePolyline(longDrive).totalDistanceMeters > RoutePolyline(shortWalk).totalDistanceMeters)
        assertTrue(RoutePolyline(longDrive).totalDistanceMeters in 95_000.0..105_000.0)
    }

    @Test
    fun `regions center journeys in their selected countries`() {
        JourneyRegion.entries.forEach { region ->
            val journey = JourneyPlanner.automaticJourney(AutoJourneyOptions(region = region), Random(2))
            assertTrue(region.landmarks.any { GeoMath.distanceMeters(journey.center, it.coordinate) <= 1_000.0 })
            assertTrue(journey.points.all { GeoMath.distanceMeters(it, journey.center) < 20_000.0 })
        }
    }

    @Test
    fun `regions provide a large unique landmark pool`() {
        JourneyRegion.entries.forEach { region ->
            val (latitudeRange, longitudeRange) = when (region) {
                JourneyRegion.Taiwan -> 21.5..25.5 to 119.0..122.5
                JourneyRegion.Japan -> 25.0..46.0 to 127.0..146.0
                JourneyRegion.SouthKorea -> 33.0..39.0 to 124.0..130.0
            }
            assertTrue(region.landmarks.size >= 25)
            assertEquals(region.landmarks.size, region.landmarks.map { it.name }.toSet().size)
            assertEquals(region.landmarks.size, region.landmarks.map { it.coordinate }.toSet().size)
            assertTrue(region.landmarks.all {
                it.coordinate.latitude in latitudeRange && it.coordinate.longitude in longitudeRange
            })
        }
    }

    @Test
    fun `repeated automatic journeys use varied centers`() {
        val random = Random(7)
        val centers = List(10) {
            JourneyPlanner.automaticJourney(AutoJourneyOptions(), random).center
        }

        assertEquals(10, centers.toSet().size)
        assertTrue(centers.any { center ->
            GeoMath.distanceMeters(center, JourneyRegion.Taiwan.landmarks.first().coordinate) > 50_000.0
        })
    }

    @Test
    fun `all shapes are closed nonzero routes around the selected center`() {
        val center = Coordinate(25.0375, 121.5637)

        RouteShape.entries.forEach { shape ->
            val points = JourneyPlanner.shapePoints(center, shape)
            assertEquals(points.first(), points.last())
            assertTrue(points.size in 11..25)
            assertTrue(JourneyPlanner.shapeDistanceMeters(center, shape) > 1_000.0)
            assertTrue(points.all { GeoMath.distanceMeters(it, center) <= 1_300.0 })
        }
    }

    @Test
    fun `seeded automatic journeys vary across the expanded shape pool`() {
        val random = Random(42)
        val shapes = List(20) {
            JourneyPlanner.automaticJourney(AutoJourneyOptions(), random).shape
        }.toSet()

        assertTrue(shapes.size >= 4)
        assertTrue(shapes.any { it in setOf(RouteShape.Cat, RouteShape.Dog, RouteShape.Rabbit, RouteShape.Fish, RouteShape.Butterfly) })
    }
}