package com.sora.mockgps.feature.search

import com.sora.mockgps.core.model.Coordinate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CachingPlaceSearchRepositoryTest {
    @Test
    fun `fresh cache avoids a second remote call`() = runBlocking {
        val clock = MutableClock(1_000L)
        val delegate = RecordingPlaceSearchRepository()
        val repository = CachingPlaceSearchRepository(delegate, clock = clock)
        val first = repository.search("Taipei", bias(25.0, 121.0))
        clock.now = 2_000L
        val second = repository.search("Taipei", bias(25.0, 121.0))
        assertEquals(1, delegate.calls)
        assertEquals(first.results, second.results)
    }

    @Test
    fun `stale fallback is only used for network or quota failures`() = runBlocking {
        val clock = MutableClock(0L)
        val delegate = RecordingPlaceSearchRepository()
        val repository = CachingPlaceSearchRepository(
            delegate,
            policy = PlaceSearchCachePolicy(freshTtlMillis = 10L, maximumEntries = 4),
            clock = clock,
        )
        repository.search("Taipei", bias(25.0, 121.0))
        clock.now = 11L
        delegate.failure = PlaceSearchException.GlobalQuotaExhausted(3600)
        val stale = repository.search("Taipei", bias(25.0, 121.0))
        assertEquals("Taipei 101", stale.results.single().name)

        delegate.failure = PlaceSearchException.InvalidRequest()
        val invalid = runCatching { repository.search("Taipei", bias(25.0, 121.0)) }.exceptionOrNull()
        assertTrue(invalid is PlaceSearchException.InvalidRequest)
    }

    @Test
    fun `language and bias are isolated in the cache key`() = runBlocking {
        val delegate = RecordingPlaceSearchRepository()
        val repository = CachingPlaceSearchRepository(delegate)
        repository.search("Taipei", bias(25.0, 121.0, "en"))
        repository.search("Taipei", bias(25.0, 121.0, "zh-TW"))
        repository.search("Taipei", bias(24.0, 121.0, "en"))
        assertEquals(3, delegate.calls)
    }

    @Test
    fun `provider revision is part of the cache key`() {
        val sameQuery = PlaceSearchCacheKey("taipei", "en", 25.0, 121.0, "geoapify-v1")
        val otherRevision = PlaceSearchCacheKey("taipei", "en", 25.0, 121.0, "other-v1")
        assertTrue(sameQuery != otherRevision)
    }

    @Test
    fun `negative clock age is treated as zero and stays fresh`() = runBlocking {
        val clock = MutableClock(5_000L)
        val delegate = RecordingPlaceSearchRepository()
        val repository = CachingPlaceSearchRepository(delegate, clock = clock)
        repository.search("Taipei", bias(25.0, 121.0))
        clock.now = 0L
        repository.search("Taipei", bias(25.0, 121.0))
        assertEquals(1, delegate.calls)
    }

    private fun bias(latitude: Double, longitude: Double, language: String = "en") =
        PlaceSearchBias(latitude, longitude, language)

    private class MutableClock(var now: Long) : PlaceSearchCacheClock {
        override fun currentTimeMillis(): Long = now
    }

    private class RecordingPlaceSearchRepository : PlaceSearchRepository {
        var calls = 0
        var failure: PlaceSearchException? = null
        private val resultCoordinate = Coordinate(25.033964, 121.564468)

        override suspend fun search(query: String, bias: PlaceSearchBias?): PlaceSearchPage {
            calls += 1
            failure?.let { throw it }
            return PlaceSearchPage(
                results = listOf(PlaceSearchResult("Taipei 101", resultCoordinate, PlaceSearchSource.Remote)),
                providerRevision = "geoapify-v1",
            )
        }
    }
}
