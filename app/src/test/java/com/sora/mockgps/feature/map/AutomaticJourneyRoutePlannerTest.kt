package com.sora.mockgps.feature.map

import com.sora.mockgps.core.model.Coordinate
import com.sora.mockgps.route.PlannedRoute
import com.sora.mockgps.route.RouteTransportMode
import com.sora.mockgps.route.RoutingRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class AutomaticJourneyRoutePlannerTest {
    @Test
    fun `routing repository receives ordered generated controls and selected transport mode`() = runBlocking {
        val repository = RecordingRoutingRepository()
        val planner = AutomaticJourneyRoutePlanner(repository, Random(11))
        val options = AutoJourneyOptions(transportMode = RouteTransportMode.Drive)
        val journey = planner.generate(options)

        planner.plan(journey, options.transportMode)

        assertEquals(journey.points, repository.waypoints)
        assertEquals(RouteTransportMode.Drive, repository.transportMode)
    }

    @Test
    fun `successful provider geometry becomes planned route and exits loading`() = runBlocking {
        val providerRoute = PlannedRoute(
            points = listOf(Coordinate(25.0, 121.0), Coordinate(25.1, 121.1)),
            distanceMeters = 1_000.0,
            providerDurationSeconds = 300.0,
        )
        val repository = RecordingRoutingRepository(providerRoute)
        val planner = AutomaticJourneyRoutePlanner(repository, Random(12))
        val options = AutoJourneyOptions()
        val journey = planner.generate(options)
        val loading = AutomaticJourneyStateReducer.planning(
            MapUiState(), journey, options.transportMode, "Taipei 101 journey",
        )

        val result = AutomaticJourneyStateReducer.success(
            loading,
            planner.plan(journey, options.transportMode),
        )

        assertEquals(providerRoute, result.plannedRoute)
        assertFalse(result.isPlanningRoute)
        assertEquals(null, result.routeError)
        assertFalse(result.automaticJourneyRecoveryAvailable)
    }

    @Test
    fun `failure remains recoverable and regeneration changes landmark`() = runBlocking {
        val repository = RecordingRoutingRepository(failure = IllegalStateException("unavailable"))
        val planner = AutomaticJourneyRoutePlanner(repository, Random(13))
        val options = AutoJourneyOptions()
        val first = planner.generate(options)
        val loading = AutomaticJourneyStateReducer.planning(MapUiState(), first, options.transportMode, "Journey")

        val failure = runCatching { planner.plan(first, options.transportMode) }.exceptionOrNull()
        val failed = AutomaticJourneyStateReducer.failure(loading, "Try another landmark")
        val regenerated = planner.generate(options)

        assertTrue(failure is IllegalStateException)
        assertFalse(failed.isPlanningRoute)
        assertTrue(failed.automaticJourneyRecoveryAvailable)
        assertEquals("Try another landmark", failed.routeError)
        assertNotEquals(first.landmark, regenerated.landmark)
    }

    private class RecordingRoutingRepository(
        private val route: PlannedRoute = PlannedRoute(
            listOf(Coordinate(25.0, 121.0), Coordinate(25.01, 121.01)),
            1_000.0,
            300.0,
        ),
        private val failure: Throwable? = null,
    ) : RoutingRepository {
        var waypoints: List<Coordinate>? = null
        var transportMode: RouteTransportMode? = null

        override suspend fun planRoute(
            waypoints: List<Coordinate>,
            transportMode: RouteTransportMode,
        ): PlannedRoute {
            this.waypoints = waypoints
            this.transportMode = transportMode
            failure?.let { throw it }
            return route
        }
    }
}