package com.sora.mockgps.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class BicycleRoutingRepositoryTest {
    @Test
    fun `OSRM GeoJSON uses longitude latitude order`() {
        val route = parseOsrmRoute(
            """{"code":"Ok","routes":[{"distance":250.0,"duration":80.0,"geometry":{"coordinates":[[121.5,25.0],[121.6,25.1]]}}]}""",
        )

        assertEquals(25.0, route.points.first().latitude, 0.0)
        assertEquals(121.5, route.points.first().longitude, 0.0)
        assertEquals(50.0, route.simulatedDurationSeconds, 0.0)
    }

    @Test
    fun `missing route and invalid geometry are rejected`() {
        assertThrows(RoutingException::class.java) {
            parseOsrmRoute("""{"code":"NoRoute","routes":[]}""")
        }
        assertThrows(RoutingException::class.java) {
            parseOsrmRoute(
                """{"code":"Ok","routes":[{"distance":10.0,"geometry":{"coordinates":[[121.5,95.0],[121.6,25.1]]}}]}""",
            )
        }
    }

    @Test
    fun `OSRM request keeps every ordered waypoint in longitude latitude order`() {
        val request = BicycleRouteRequest(
            listOf(
                com.sora.mockgps.core.model.Coordinate(25.033964, 121.564468),
                com.sora.mockgps.core.model.Coordinate(25.037500, 121.563700),
                com.sora.mockgps.core.model.Coordinate(25.045000, 121.517000),
            ),
        )
        val url = buildOsrmBicycleRouteUrl(request)

        assertEquals(
            "https://routing.openstreetmap.de/routed-bike/route/v1/driving/" +
                "121.564468,25.033964;121.5637,25.0375;121.517,25.045" +
                "?overview=full&geometries=geojson&steps=false",
            url,
        )
    }

    @Test
    fun `provider config normalizes a custom base URL without changing the route query`() {
        val provider = RoutingProviderConfig(baseUrl = "https://router.example.test/api/route/v1/bicycle")
        val request = BicycleRouteRequest(
            listOf(
                com.sora.mockgps.core.model.Coordinate(1.0, 2.0),
                com.sora.mockgps.core.model.Coordinate(3.0, 4.0),
            ),
        )

        assertEquals(
            "https://router.example.test/api/route/v1/bicycle/2.0,1.0;4.0,3.0" +
                "?overview=full&geometries=geojson&steps=false",
            buildOsrmBicycleRouteUrl(request, provider),
        )
    }

    @Test
    fun `route request rejects too few repeated or invalid waypoints`() {
        val valid = com.sora.mockgps.core.model.Coordinate(25.0, 121.0)
        assertThrows(IllegalArgumentException::class.java) { BicycleRouteRequest(listOf(valid)) }
        assertThrows(IllegalArgumentException::class.java) { BicycleRouteRequest(listOf(valid, valid)) }
        assertThrows(IllegalArgumentException::class.java) {
            BicycleRouteRequest(listOf(valid, com.sora.mockgps.core.model.Coordinate(95.0, 121.0)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RoutingProviderConfig(baseUrl = "ftp://router.example.test/route/")
        }
        assertTrue(BicycleRouteRequest.MAXIMUM_WAYPOINTS >= 3)
    }
}
