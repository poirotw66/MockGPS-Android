package com.sora.mockgps.feature.map

import com.sora.mockgps.core.model.Coordinate
import com.sora.mockgps.route.GeoMath
import com.sora.mockgps.route.BicycleRouteRequest
import com.sora.mockgps.route.RoutePolyline
import com.sora.mockgps.route.RouteTransportMode
import org.json.JSONObject
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
        assertTrue(RoutePolyline(longDrive).totalDistanceMeters in 35_000.0..70_000.0)
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
    fun `map shares all unique journey landmarks`() {
        assertEquals(85, journeyLandmarks.size)
        assertEquals(journeyLandmarks.size, journeyLandmarks.map { it.name }.toSet().size)
        assertEquals(journeyLandmarks.size, journeyLandmarks.map { it.coordinate }.toSet().size)
    }

    @Test
    fun `landmark geojson preserves names and longitude latitude order`() {
        val features = JSONObject(journeyLandmarks.toFeatureCollectionGeoJson()).getJSONArray("features")

        assertEquals(journeyLandmarks.size, features.length())
        journeyLandmarks.forEachIndexed { index, landmark ->
            val feature = features.getJSONObject(index)
            val coordinates = feature.getJSONObject("geometry").getJSONArray("coordinates")
            assertEquals(landmark.name, feature.getJSONObject("properties").getString("name"))
            assertEquals(landmark.coordinate.longitude, coordinates.getDouble(0), 0.0)
            assertEquals(landmark.coordinate.latitude, coordinates.getDouble(1), 0.0)
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
    fun `automatic journey retains landmark and can exclude the previous selection`() {
        val random = Random(7)
        val first = JourneyPlanner.automaticJourney(AutoJourneyOptions(), random)
        val second = JourneyPlanner.automaticJourney(
            AutoJourneyOptions(),
            random,
            excludedLandmark = first.landmark,
        )

        assertTrue(first.landmark in JourneyRegion.Taiwan.landmarks)
        assertTrue(GeoMath.distanceMeters(first.center, first.landmark.coordinate) <= 1_000.0)
        assertTrue(second.landmark != first.landmark)
    }

    @Test
    fun `transport mode conservatively caps automatic journey radius`() {
        RouteTransportMode.entries.forEach { mode ->
            val journey = JourneyPlanner.automaticJourney(
                AutoJourneyOptions(duration = JourneyDuration.Long, transportMode = mode),
                Random(23),
            )
            val maximumRadius = journey.points.maxOf { GeoMath.distanceMeters(it, journey.center) }
            val expectedLimit = when (mode) {
                RouteTransportMode.Walk -> 1_500.0
                RouteTransportMode.Bicycle -> 4_000.0
                RouteTransportMode.Drive -> 8_000.0
            }

            assertTrue(maximumRadius <= expectedLimit + 1.0)
        }
    }

    @Test
    fun `coastal and mountain landmarks use tighter radius limits`() {
        val constrained = JourneyRegion.entries.flatMap { it.landmarks }
            .filter { it.maximumShapeRadiusMeters <= 1_500.0 }

        assertTrue(constrained.size >= 10)
        assertTrue(constrained.any { it.name == "Taroko National Park Gate" })
        assertTrue(constrained.any { it.name == "Yehliu Geopark" })
    }

    @Test
    fun `automatic journey control points satisfy the road routing contract`() {
        val random = Random(19)

        repeat(100) {
            val journey = JourneyPlanner.automaticJourney(
                AutoJourneyOptions(
                    region = JourneyRegion.entries[it % JourneyRegion.entries.size],
                    duration = JourneyDuration.entries[it % JourneyDuration.entries.size],
                    transportMode = RouteTransportMode.entries[it % RouteTransportMode.entries.size],
                ),
                random,
            )
            val request = BicycleRouteRequest(journey.points)

            assertEquals(journey.points, request.waypoints)
            assertEquals(journey.points.first(), journey.points.last())
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