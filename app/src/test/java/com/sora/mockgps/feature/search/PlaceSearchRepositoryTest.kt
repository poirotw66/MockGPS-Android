package com.sora.mockgps.feature.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceSearchRepositoryTest {
    @Test fun `parser keeps only valid OSM compatible results`() {
        val results = parseNominatimResults(
            """[{"display_name":"Taipei 101","lat":"25.033964","lon":"121.564468"},{"display_name":"bad","lat":"99","lon":"1"}]""",
        )
        assertEquals(1, results.size)
        assertEquals("Taipei 101", results.single().name)
    }

    @Test fun `public provider enforces one request per second`() {
        val result = runCatching { PlaceSearchProviderConfig(requestIntervalMillis = 999) }
        assertTrue(result.isFailure)
    }
}
