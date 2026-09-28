package com.sora.mockgps.feature.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayPlaceSearchRepositoryTest {
    @Test
    fun `parses named coordinates and allowlisted attribution`() {
        val page = parseGatewaySearchPage(
            """
            {
              "requestId": "7fa29c99-6507-4618-94d8-4af824f57e64",
              "providerRevision": "geoapify-v1",
              "results": [
                {
                  "name": "台北車站",
                  "address": "臺北市",
                  "coordinate": { "latitude": 25.0478, "longitude": 121.5170 }
                }
              ],
              "attribution": [
                { "label": "Powered by Geoapify", "url": "https://www.geoapify.com/" },
                { "label": "bad", "url": "https://evil.example/x" }
              ]
            }
            """.trimIndent(),
        )
        assertEquals(1, page.results.size)
        assertEquals("台北車站", page.results.single().name)
        assertEquals(25.0478, page.results.single().coordinate.latitude, 1e-9)
        assertEquals(121.5170, page.results.single().coordinate.longitude, 1e-9)
        assertEquals("geoapify-v1", page.providerRevision)
        assertEquals(1, page.attribution.size)
        assertEquals("https://www.geoapify.com/", page.attribution.single().url)
    }

    @Test
    fun `empty remote results are success`() {
        val page = parseGatewaySearchPage(
            """{"requestId":"x","providerRevision":"geoapify-v1","results":[],"attribution":[]}""",
        )
        assertTrue(page.results.isEmpty())
    }

    @Test
    fun `non finite coordinates are dropped`() {
        val page = parseGatewaySearchPage(
            """{"results":[{"name":"bad","coordinate":{"latitude":"NaN","longitude":121.0}}]}""",
        )
        assertTrue(page.results.isEmpty())
    }
}
