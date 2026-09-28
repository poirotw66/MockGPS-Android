package com.sora.mockgps.route

import com.sora.mockgps.core.model.Coordinate
import com.sora.mockgps.core.network.GatewayException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayRoutingRepositoryTest {
    @Test
    fun `parses named latitude longitude objects and does not invert them`() {
        val parsed = parseGatewayRoute(
            """
            {
              "requestId": "req",
              "providerRevision": "geoapify-v1",
              "mode": "bicycle",
              "points": [
                { "latitude": 25.033964, "longitude": 121.564468 },
                { "latitude": 25.0478, "longitude": 121.5170 }
              ],
              "distanceMeters": 4200.5,
              "providerDurationSeconds": 890,
              "attribution": [
                { "label": "Powered by Geoapify", "url": "https://www.geoapify.com/" }
              ]
            }
            """.trimIndent(),
            RouteTransportMode.Bicycle,
        )
        assertEquals(25.033964, parsed.route.points.first().latitude, 1e-9)
        assertEquals(121.564468, parsed.route.points.first().longitude, 1e-9)
        assertEquals(4200.5, parsed.route.distanceMeters, 1e-9)
        assertEquals(1, parsed.attribution.size)
    }

    @Test
    fun `mode mismatch and infinite coordinates are rejected`() {
        assertThrows(RoutingException::class.java) {
            parseGatewayRoute(
                """{"mode":"walk","points":[{"latitude":25.0,"longitude":121.0},{"latitude":25.1,"longitude":121.1}],"distanceMeters":10}""",
                RouteTransportMode.Bicycle,
            )
        }
        assertThrows(RoutingException::class.java) {
            parseGatewayRoute(
                """{"mode":"bicycle","points":[{"latitude":"NaN","longitude":121.0},{"latitude":25.1,"longitude":121.1}],"distanceMeters":10}""",
                RouteTransportMode.Bicycle,
            )
        }
    }

    @Test
    fun `online input distance rejects abuse-length polylines`() {
        val far = listOf(Coordinate(25.0, 121.0), Coordinate(26.2, 121.0))
        assertThrows(RoutingOutOfScopeException::class.java) {
            validateOnlineInputDistance(far)
        }
        validateOnlineInputDistance(listOf(Coordinate(25.0, 121.0), Coordinate(25.01, 121.01)))
    }

    @Test
    fun `typed gateway errors do not become public fallbacks`() {
        assertTrue(routingExceptionFromGateway(GatewayException.NoRoute()) is RoutingNoRouteException)
        assertTrue(routingExceptionFromGateway(GatewayException.GlobalQuotaExhausted()) is RoutingQuotaException)
        assertTrue(routingExceptionFromGateway(GatewayException.ProviderUnavailable()) is RoutingNetworkException)
    }
}
