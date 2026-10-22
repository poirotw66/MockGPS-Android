package com.sora.mockgps.route

import com.sora.mockgps.core.model.Coordinate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureGatedRoutingRepositoryTest {
    private val origin = Coordinate(25.0, 121.0)
    private val destination = Coordinate(25.02, 121.02)
    private val route = PlannedRoute(listOf(origin, destination), 500.0, 100.0)

    @Test
    fun `disabled routing never calls the delegate and is not a stale-cache network failure`() = runBlocking {
        var enabled = false
        val delegate = RecordingRoutingRepository(route)
        val repository = FeatureGatedRoutingRepository(delegate, routingEnabled = { enabled })
        val failure = runCatching { repository.planRoute(listOf(origin, destination), RouteTransportMode.Bicycle) }
            .exceptionOrNull()
        assertTrue(failure is RoutingServiceDisabledException)
        assertEquals(0, delegate.calls)

        enabled = true
        assertEquals(route, repository.planRoute(listOf(origin, destination), RouteTransportMode.Bicycle))
        assertEquals(1, delegate.calls)
    }

    @Test
    fun `unknown routing flag refreshes before the route and a failed refresh is a network error`() = runBlocking {
        var enabled = false
        var confirmedDisabled = false
        var refreshes = 0
        val delegate = RecordingRoutingRepository(route)
        val repository = FeatureGatedRoutingRepository(
            delegate = delegate,
            routingEnabled = { enabled },
            refreshRoutingAvailability = {
                refreshes += 1
                if (refreshes == 1) enabled = true
            },
            routingConfirmedDisabled = { confirmedDisabled },
        )
        assertEquals(route, repository.planRoute(listOf(origin, destination), RouteTransportMode.Bicycle))
        assertEquals(1, refreshes)
        assertEquals(1, delegate.calls)

        enabled = false
        val failure = runCatching {
            repository.planRoute(listOf(origin, destination), RouteTransportMode.Bicycle)
        }.exceptionOrNull()
        assertTrue(failure is RoutingNetworkException)
        assertEquals(2, refreshes)
        assertEquals(1, delegate.calls)
    }

    @Test
    fun `disabled routing is not replaced by a stale cached route`() = runBlocking {
        val clock = MutableClock(0L)
        val delegate = RecordingRoutingRepository(route)
        val cached = CachingRoutingRepository(
            delegate = FeatureGatedRoutingRepository(delegate, routingEnabled = { clock.now < 20L }),
            policy = RoutingCachePolicy(freshTtlMillis = 10L, maximumEntries = 2),
            clock = clock,
        )
        cached.planRoute(listOf(origin, destination), RouteTransportMode.Bicycle)
        clock.now = 21L
        val failure = runCatching {
            cached.planRoute(listOf(origin, destination), RouteTransportMode.Bicycle)
        }.exceptionOrNull()
        assertTrue(failure is RoutingServiceDisabledException)
        assertEquals(1, delegate.calls)
    }

    private class MutableClock(var now: Long) : RoutingCacheClock {
        override fun currentTimeMillis(): Long = now
    }

    private class RecordingRoutingRepository(
        private val route: PlannedRoute,
    ) : RoutingRepository {
        var calls = 0
        override suspend fun planRoute(
            waypoints: List<Coordinate>,
            transportMode: RouteTransportMode,
        ): PlannedRoute {
            calls += 1
            return route
        }
    }
}
