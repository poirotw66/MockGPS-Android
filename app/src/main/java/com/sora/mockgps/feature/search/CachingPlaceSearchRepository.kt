package com.sora.mockgps.feature.search

import com.sora.mockgps.core.network.GatewayLanguages
import java.util.LinkedHashMap

fun interface PlaceSearchCacheClock {
    fun currentTimeMillis(): Long
}

data class PlaceSearchCachePolicy(
    val freshTtlMillis: Long = DEFAULT_FRESH_TTL_MILLIS,
    val maximumEntries: Int = DEFAULT_MAXIMUM_ENTRIES,
    val maxStaleAgeMillis: Long = DEFAULT_MAX_STALE_AGE_MILLIS,
) {
    init {
        require(freshTtlMillis >= 0L)
        require(maximumEntries > 0)
        require(maxStaleAgeMillis >= freshTtlMillis)
    }

    companion object {
        const val DEFAULT_FRESH_TTL_MILLIS = 5 * 60 * 1_000L
        const val DEFAULT_MAXIMUM_ENTRIES = 50
        const val DEFAULT_MAX_STALE_AGE_MILLIS = 24 * 60 * 60 * 1_000L
    }
}

data class PlaceSearchCacheKey(
    val query: String,
    val language: String,
    val biasLatitude: Double?,
    val biasLongitude: Double?,
    val providerRevision: String,
)

class CachingPlaceSearchRepository(
    private val delegate: PlaceSearchRepository,
    private val policy: PlaceSearchCachePolicy = PlaceSearchCachePolicy(),
    private val clock: PlaceSearchCacheClock = PlaceSearchCacheClock(System::currentTimeMillis),
    private val defaultProviderRevision: String = "geoapify-v1",
) : PlaceSearchRepository {
    private val entries = LinkedHashMap<PlaceSearchCacheKey, CachedPage>(policy.maximumEntries, 0.75f, true)

    override suspend fun search(query: String, bias: PlaceSearchBias?): PlaceSearchPage {
        val key = cacheKey(query, bias, defaultProviderRevision)
        val now = clock.currentTimeMillis()
        val cached = synchronized(entries) { entries[key] }
        if (cached != null && ageMillis(now, cached) <= policy.freshTtlMillis) {
            return cached.page
        }
        return try {
            val page = delegate.search(query, bias)
            val storedKey = cacheKey(query, bias, page.providerRevision ?: defaultProviderRevision)
            synchronized(entries) {
                entries[storedKey] = CachedPage(page, now)
                while (entries.size > policy.maximumEntries) {
                    entries.entries.iterator().run { next(); remove() }
                }
            }
            page
        } catch (failure: PlaceSearchException) {
            if (cached != null &&
                canUseStale(failure) &&
                ageMillis(now, cached) <= policy.maxStaleAgeMillis
            ) {
                cached.page
            } else {
                throw failure
            }
        }
    }

    private fun cacheKey(query: String, bias: PlaceSearchBias?, revision: String): PlaceSearchCacheKey =
        PlaceSearchCacheKey(
            query = normalizePlaceQuery(query),
            language = GatewayLanguages.wireLanguage(bias?.language),
            biasLatitude = bias?.latitude,
            biasLongitude = bias?.longitude,
            providerRevision = revision,
        )

    private fun canUseStale(failure: PlaceSearchException): Boolean = when (failure) {
        is PlaceSearchException.Network,
        is PlaceSearchException.RateLimited,
        is PlaceSearchException.GlobalQuotaExhausted,
        is PlaceSearchException.ServiceUnavailable,
        -> true
        else -> false
    }

    private fun ageMillis(now: Long, cached: CachedPage): Long =
        (now - cached.storedAtMillis).coerceAtLeast(0L)

    private data class CachedPage(val page: PlaceSearchPage, val storedAtMillis: Long)
}
