package com.sora.mockgps.feature.search

import com.sora.mockgps.core.model.Coordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceSearchRepositoryTest {
    @Test fun `parser keeps only valid OSM compatible results`() {
        val results = parseNominatimResults(
            """[{"display_name":"Taipei 101","lat":"25.033964","lon":"121.564468"},{"display_name":"bad","lat":"99","lon":"1"}]""",
        )
        assertEquals(1, results.size)
        assertEquals("Taipei 101", results.single().name)
        assertEquals(PlaceSearchSource.Remote, results.single().source)
    }

    @Test fun `public provider enforces one request per second`() {
        val result = runCatching { PlaceSearchProviderConfig(requestIntervalMillis = 999) }
        assertTrue(result.isFailure)
    }

    @Test fun `nominatim url includes soft bias parameters`() {
        val url = buildNominatimSearchUrl(
            baseUrl = "https://nominatim.openstreetmap.org/search",
            query = "101",
            bias = PlaceSearchBias(
                countryCodes = "tw",
                viewbox = "121.2,25.4,121.9,24.7",
            ),
        )
        assertTrue(url.contains("q=101"))
        assertTrue(url.contains("countrycodes=tw"))
        assertTrue(url.contains("viewbox="))
        assertTrue(url.contains("bounded=0"))
        assertTrue(url.contains("format=jsonv2"))
    }

    @Test fun `nominatim url without bias stays minimal`() {
        val url = buildNominatimSearchUrl(
            baseUrl = "https://example.test/search",
            query = "Tokyo",
        )
        assertEquals(
            "https://example.test/search?format=jsonv2&limit=8&q=Tokyo",
            url,
        )
    }

    @Test fun `viewbox surrounds camera center`() {
        val box = viewboxAround(Coordinate(25.03, 121.5), deltaDegrees = 0.35)
        val parts = box.split(",")
        assertEquals(4, parts.size)
        assertEquals(121.15, parts[0].toDouble(), 1e-9)
        assertEquals(25.38, parts[1].toDouble(), 1e-9)
        assertEquals(121.85, parts[2].toDouble(), 1e-9)
        assertEquals(24.68, parts[3].toDouble(), 1e-9)
    }

    @Test fun `merge keeps landmarks first and drops near-duplicate remotes`() {
        val landmark = PlaceSearchResult(
            "Taipei 101",
            Coordinate(25.033964, 121.564468),
            PlaceSearchSource.Landmark,
        )
        val nearDuplicate = PlaceSearchResult(
            "Taipei 101, Xinyi, Taipei",
            Coordinate(25.0340, 121.5645),
            PlaceSearchSource.Remote,
        )
        val other = PlaceSearchResult(
            "Somewhere else",
            Coordinate(35.0, 139.0),
            PlaceSearchSource.Remote,
        )
        val merged = mergePlaceSearchResults(listOf(landmark), listOf(nearDuplicate, other))
        assertEquals(listOf(landmark, other), merged)
    }

    @Test fun `nickname heuristic flags short and numeric queries`() {
        assertTrue(looksLikeLandmarkNickname("101"))
        assertTrue(looksLikeLandmarkNickname("USJ"))
        assertFalse(looksLikeLandmarkNickname("Tokyo Station"))
        assertFalse(looksLikeLandmarkNickname("a"))
    }
}
