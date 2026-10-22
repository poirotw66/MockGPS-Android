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
    fun `NO_ROUTE is not replaced by a stale cached route`() = runBlocking {
        val clock = MutableClock(0L)
        val delegate = FakeRoutingRepository { _ -> route }
        val repository = CachingRoutingRepository(
            delegate = delegate,
            policy = RoutingCachePolicy(freshTtlMillis = 10L, maximumEntries = 2),
            clock = clock,
        )
        repository.planBicycleRoute(origin, destination)
        clock.now = 11L
        delegate.response = { throw RoutingNoRouteException() }
        val failure = runCatching { repository.planBicycleRoute(origin, destination) }.exceptionOrNull()
        assertTrue(failure is RoutingNoRouteException)
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
    fun `quota exhaustion stays a quota failure instead of a network outage`() = runBlocking {
        val delegate = FakeRoutingRepository { _ -> throw RoutingQuotaException("CLIENT_RATE_LIMITED") }
        val repository = CachingRoutingRepository(delegate)
        val failure = runCatching { repository.planBicycleRoute(origin, destination) }.exceptionOrNull()
        assertTrue(failure is RoutingQuotaException)
        assertEquals("CLIENT_RATE_LIMITED", failure?.message)
    }

    @Test
    fun `cache key keeps provider revision mode and waypoint order and bounded cache evicts least recently used`() {
        val identity = RoutingCacheIdentity(contractRevision = "v1", providerRevision = "geoapify-v1")
        val otherIdentity = RoutingCacheIdentity(contractRevision = "v1", providerRevision = "other-v1")
        val forward = RoutingCacheKey.create(identity, RouteTransportMode.Bicycle, listOf(origin, via, destination))
        val reversed = RoutingCacheKey.create(identity, RouteTransportMode.Bicycle, listOf(destination, via, origin))
        val otherProvider = RoutingCacheKey.create(otherIdentity, RouteTransportMode.Bicycle, listOf(origin, via, destination))
        val otherMode = RoutingCacheKey.create(identity, RouteTransportMode.Walk, listOf(origin, via, destination))
        assertFalse(forward == reversed)
        assertFalse(forward == otherProvider)
        assertFalse(forward == otherMode)

        val cache = InMemoryRoutingCache(maximumEntries = 2)
        val first = RoutingCacheKey.create(identity, RouteTransportMode.Bicycle, listOf(origin, destination))
        val second = RoutingCacheKey.create(identity, RouteTransportMode.Bicycle, listOf(origin, via))
        val third = RoutingCacheKey.create(identity, RouteTransportMode.Bicycle, listOf(via, destination))
        cache.put(first, CachedRoute(route, 0L))
        cache.put(second, CachedRoute(route, 0L))
        cache.get(first) // first is now most recently used, so second should be evicted.
        cache.put(third, CachedRoute(route, 0L))

        assertTrue(cache.get(first) != null)
        assertEquals(null, cache.get(second))
        assertTrue(cache.get(third) != null)
    }

    @Test
    fun `negative clock age is treated as zero and stays fresh`() = runBlocking {
        val clock = MutableClock(5_000L)
        val delegate = FakeRoutingRepository { _ -> route }
        val repository = CachingRoutingRepository(
            delegate = delegate,
            policy = RoutingCachePolicy(freshTtlMillis = 1_000L, maximumEntries = 2),
            clock = clock,
        )
        repository.planBicycleRoute(origin, destination)
        clock.now = 0L
        repository.planBicycleRoute(origin, destination)
        assertEquals(1, delegate.calls)
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
