package com.sora.mockgps.feature.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CurrentLocationResolverTest {
    @Test
    fun `freshest real fix ignores a newer mock location`() {
        val now = 1_000_000L
        val real = CandidateFix(latitude = 25.033, longitude = 121.565, timeMillis = now - 5_000L, isMock = false)
        val mock = CandidateFix(latitude = 40.472, longitude = -80.012, timeMillis = now - 100L, isMock = true)

        val chosen = freshestRealFix(listOf(mock, real), now, CURRENT_LOCATION_FRESH_MAX_AGE_MILLIS)

        assertEquals(real, chosen)
    }

    @Test
    fun `freshest real fix rejects a real fix older than the requested limit`() {
        val now = 1_000_000L
        val stale = CandidateFix(latitude = 25.033, longitude = 121.565, timeMillis = now - 61_000L, isMock = false)

        assertNull(freshestRealFix(listOf(stale), now, CURRENT_LOCATION_FRESH_MAX_AGE_MILLIS))
    }

    @Test
    fun `freshest real fix keeps a recent real fix as the fallback and still rejects mocks`() {
        val now = 1_000_000L
        val recent = CandidateFix(latitude = 25.040, longitude = 121.561, timeMillis = now - 90_000L, isMock = false)
        val mock = CandidateFix(latitude = 40.472, longitude = -80.012, timeMillis = now - 1_000L, isMock = true)

        assertNull(freshestRealFix(listOf(recent, mock), now, CURRENT_LOCATION_FRESH_MAX_AGE_MILLIS))
        assertEquals(
            recent,
            freshestRealFix(listOf(recent, mock), now, CURRENT_LOCATION_FALLBACK_MAX_AGE_MILLIS),
        )
    }

    @Test
    fun `freshest real fix picks the newest real fix inside the limit`() {
        val now = 1_000_000L
        val older = CandidateFix(latitude = 25.0, longitude = 121.0, timeMillis = now - 20_000L, isMock = false)
        val newer = CandidateFix(latitude = 25.1, longitude = 121.1, timeMillis = now - 2_000L, isMock = false)

        assertEquals(newer, freshestRealFix(listOf(older, newer), now, CURRENT_LOCATION_FRESH_MAX_AGE_MILLIS))
    }
}
