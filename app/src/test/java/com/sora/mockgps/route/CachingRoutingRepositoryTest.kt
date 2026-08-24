package com.sora.mockgps.route

import com.sora.mockgps.core.model.Coordinate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CachingRoutingRepositoryTest {
    private val origin = Coordinate(25.0, 121.0)
    private val via = Coordinate(25.01, 121.01)
    private val destination = Coordinate(25.02, 121.02)
    private val route = PlannedRoute(listOf(origin, destination), 500.0, 100.0)

    @Test
    fun `fresh cache avoids a second provider call and reports cache age`() = runBlocking {
        val clock = MutableClock(1_000L)
        val delegate = FakeRoutingRepository { _ -> route }
        val repository = CachingRoutingRepository(
            delegate = delegate,
            policy = RoutingCachePolicy(freshTtlMillis = 1_000L, maximumEntries = 2),
            clock = clock,
        )

        assertEquals(RoutingCacheStatus.Network, repository.planBicycleRoute(origin, destination).cacheStatus)
        clock.now = 1_250L
        val cached = repository.planBicycleRoute(origin, destination)

        assertEquals(1, delegate.calls)
        assertEquals(250L, (cached.cacheStatus as RoutingCacheStatus.Fresh).ageMillis)
    }

    @Test
    fun `expired cache is returned only for a network failure with stale status`() = runBlocking {
        val clock = MutableClock(0L)
        val delegate = FakeRoutingRepository { _ -> route }
        val repository = CachingRoutingRepository(
            delegate = delegate,
            policy = RoutingCachePolicy(freshTtlMillis = 10L, maximumEntries = 2),
            clock = clock,
        )
        repository.planBicycleRoute(listOf(origin, via, destination))
        clock.now = 11L
        delegate.response = { throw RoutingNetworkException("offline") }

        val fallback = repository.planBicycleRoute(listOf(origin, via, destination))
        val status = fallback.cacheStatus as RoutingCacheStatus.StaleFallback

        assertEquals(11L, status.ageMillis)
        assertEquals("offline", status.networkFailure.message)
        assertEquals(2, delegate.calls)
    }

    @Test
    fun `network failure without cache exposes an unavailable routing error`() = runBlocking {
        val delegate = FakeRoutingRepository { _ -> throw RoutingNetworkException("offline") }
        val repository = CachingRoutingRepository(delegate)

        val failure = try {
            repository.planBicycleRoute(origin, destination)
            null
        } catch (expected: RoutingUnavailableException) {
            expected
        }

        requireNotNull(failure)
        assertTrue(failure.cause is RoutingNetworkException)
        assertEquals("offline", failure.cause?.message)
    }

    @Test
    fun `cache key keeps provider and waypoint order and bounded cache evicts least recently used`() {
        val provider = RoutingProviderConfig(baseUrl = "https://one.example/route/")
        val otherProvider = RoutingProviderConfig(baseUrl = "https://two.example/route/")
        val forward = RoutingCacheKey.create(provider, listOf(origin, via, destination))
        val reversed = RoutingCacheKey.create(provider, listOf(destination, via, origin))
        val other = RoutingCacheKey.create(otherProvider, listOf(origin, via, destination))
        assertFalse(forward == reversed)
        assertFalse(forward == other)

        val cache = InMemoryRoutingCache(maximumEntries = 2)
        val first = RoutingCacheKey.create(provider, listOf(origin, destination))
        val second = RoutingCacheKey.create(provider, listOf(origin, via))
        val third = RoutingCacheKey.create(provider, listOf(via, destination))
        cache.put(first, CachedRoute(route, 0L))
        cache.put(second, CachedRoute(route, 0L))
        cache.get(first) // first is now most recently used, so second should be evicted.
        cache.put(third, CachedRoute(route, 0L))

        assertTrue(cache.get(first) != null)
        assertEquals(null, cache.get(second))
        assertTrue(cache.get(third) != null)
    }

    private class MutableClock(var now: Long) : RoutingCacheClock {
        override fun currentTimeMillis(): Long = now
    }

    private class FakeRoutingRepository(
        var response: suspend (List<Coordinate>) -> PlannedRoute,
    ) : RoutingRepository {
        var calls = 0

        override suspend fun planRoute(
            waypoints: List<Coordinate>,
            transportMode: RouteTransportMode,
        ): PlannedRoute {
            calls++
            return response(waypoints)
        }
    }
}
