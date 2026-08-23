package com.sora.mockgps.route

import org.junit.Assert.assertEquals
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
}
