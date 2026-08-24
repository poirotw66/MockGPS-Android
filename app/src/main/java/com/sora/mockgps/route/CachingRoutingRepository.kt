package com.sora.mockgps.route

import com.sora.mockgps.core.model.Coordinate
import java.util.LinkedHashMap

/** Injectable monotonic-or-wall clock for deterministic cache tests. */
fun interface RoutingCacheClock {
    fun currentTimeMillis(): Long
}

data class RoutingCachePolicy(
    val freshTtlMillis: Long = DEFAULT_FRESH_TTL_MILLIS,
    val maximumEntries: Int = DEFAULT_MAXIMUM_ENTRIES,
    val allowStaleOnNetworkFailure: Boolean = true,
) {
    init {
        require(freshTtlMillis >= 0L) { "Fresh route cache TTL cannot be negative." }
        require(maximumEntries > 0) { "Route cache must allow at least one entry." }
    }

    companion object {
        const val DEFAULT_FRESH_TTL_MILLIS = 5 * 60 * 1_000L
        const val DEFAULT_MAXIMUM_ENTRIES = 32
    }
}

/**
 * Provider and ordered waypoint identity. A reversed route deliberately has a different key.
 * The private constructor forces callers to snapshot the waypoint list before hashing it.
 */
class RoutingCacheKey private constructor(
    val providerBaseUrl: String,
    val orderedWaypoints: List<Coordinate>,
) {
    override fun equals(other: Any?): Boolean = other is RoutingCacheKey &&
        providerBaseUrl == other.providerBaseUrl && orderedWaypoints == other.orderedWaypoints

    override fun hashCode(): Int = 31 * providerBaseUrl.hashCode() + orderedWaypoints.hashCode()

    companion object {
        fun create(providerConfig: RoutingProviderConfig, waypoints: List<Coordinate>): RoutingCacheKey =
            RoutingCacheKey(providerConfig.normalizedBaseUrl, waypoints.toList())
    }
}

data class CachedRoute(
    val route: PlannedRoute,
    val storedAtMillis: Long,
)

interface RoutingCache {
    fun get(key: RoutingCacheKey): CachedRoute?
    fun put(key: RoutingCacheKey, value: CachedRoute)
}

/** Bounded, access-ordered in-memory cache. Expiry is a decorator policy, not a destructive read. */
class InMemoryRoutingCache(
    private val maximumEntries: Int,
) : RoutingCache {
    private val entries = LinkedHashMap<RoutingCacheKey, CachedRoute>(maximumEntries, 0.75f, true)

    init {
        require(maximumEntries > 0) { "Route cache must allow at least one entry." }
    }

    override fun get(key: RoutingCacheKey): CachedRoute? = synchronized(entries) { entries[key] }

    override fun put(key: RoutingCacheKey, value: CachedRoute) {
        synchronized(entries) {
            entries[key] = value
            while (entries.size > maximumEntries) entries.entries.iterator().run { next(); remove() }
        }
    }

    val size: Int get() = synchronized(entries) { entries.size }
}

/**
 * Adds bounded cache and offline fallback behaviour to any [RoutingRepository]. It only catches
 * [RoutingNetworkException]: a valid provider response such as `NoRoute` must not be silently
 * replaced by an old route for a different routing result.
 */
class CachingRoutingRepository(
    private val delegate: RoutingRepository,
    private val providerConfig: RoutingProviderConfig = RoutingProviderConfig(),
    private val policy: RoutingCachePolicy = RoutingCachePolicy(),
    private val clock: RoutingCacheClock = RoutingCacheClock(System::currentTimeMillis),
    private val cache: RoutingCache = InMemoryRoutingCache(policy.maximumEntries),
) : RoutingRepository {
    override suspend fun planRoute(
        waypoints: List<Coordinate>,
        transportMode: RouteTransportMode,
    ): PlannedRoute {
        val request = BicycleRouteRequest(waypoints)
        val modeProviderConfig = providerConfig.copy(baseUrl = transportMode.providerBaseUrl)
        val key = RoutingCacheKey.create(modeProviderConfig, request.waypoints)
        val now = clock.currentTimeMillis()
        val cached = cache.get(key)
        if (cached != null && ageMillis(now, cached) <= policy.freshTtlMillis) {
            return cached.route.withCacheStatus(RoutingCacheStatus.Fresh(ageMillis(now, cached)))
        }

        return try {
            val planned = delegate.planRoute(request.waypoints, transportMode).withoutCacheStatus()
            cache.put(key, CachedRoute(planned, now))
            planned
        } catch (failure: RoutingNetworkException) {
            if (cached != null && policy.allowStaleOnNetworkFailure) {
                cached.route.withCacheStatus(
                    RoutingCacheStatus.StaleFallback(ageMillis(now, cached), failure),
                )
            } else {
                throw RoutingUnavailableException(
                    "Routing is unavailable and no cached route can be used.",
                    failure,
                )
            }
        }
    }

    private fun ageMillis(now: Long, cached: CachedRoute): Long = (now - cached.storedAtMillis).coerceAtLeast(0L)
}

private fun PlannedRoute.withCacheStatus(status: RoutingCacheStatus): PlannedRoute = copy(cacheStatus = status)

/** Never persist a previous cache/fallback status inside a new cache entry. */
private fun PlannedRoute.withoutCacheStatus(): PlannedRoute = copy(cacheStatus = RoutingCacheStatus.Network)
