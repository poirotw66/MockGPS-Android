package com.sora.mockgps.feature.routes.data

import com.sora.mockgps.core.model.Coordinate
import com.sora.mockgps.feature.routes.domain.RecentRoute
import com.sora.mockgps.feature.routes.domain.RouteBackup
import com.sora.mockgps.feature.routes.domain.SavedRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteInterchangeTest {
    private val points = listOf(
        Coordinate(25.033964, 121.564468),
        Coordinate(25.047675, 121.517055),
    )

    @Test
    fun `geometry and GPX round trip exact coordinates`() {
        assertEquals(points, RouteGeometryCodec.decode(RouteGeometryCodec.encode(points)))

        val imported = RouteGpxInterchange.import(RouteGpxInterchange.export("Morning ride", points))

        assertEquals("Morning ride", imported.name)
        assertEquals(points, imported.points)
    }

    @Test
    fun `GPX rejects unsafe declarations and incomplete geometry`() {
        val unsafe = "<!DOCTYPE gpx [<!ENTITY test SYSTEM 'file:///secret'>]><gpx/>"
        val incomplete = "<gpx xmlns=\"http://www.topografix.com/GPX/1/1\"><trk><trkseg><trkpt lat=\"25\"/></trkseg></trk></gpx>"

        assertFails { RouteGpxInterchange.import(unsafe) }
        assertFails { RouteGpxInterchange.import(incomplete) }
    }

    @Test
    fun `backup round trip preserves reverse and recent route linkage`() {
        val backup = RouteBackup(
            savedRoutes = listOf(
                SavedRoute(4, "Outward", points, 5_000.0, 10, 20),
                SavedRoute(8, "Return", points.asReversed(), 5_000.0, 30, 40, reversedFromRouteId = 4),
            ),
            recentRoutes = listOf(RecentRoute(12, "Return", points.asReversed(), 5_000.0, 50, 8)),
        )

        assertEquals(backup, RouteBackupJson.decode(RouteBackupJson.encode(backup)))
    }

    @Test
    fun `backup rejects unknown fields dangling associations and invalid coordinates`() {
        assertFails {
            RouteBackupJson.decode("""{"version":1,"savedRoutes":[],"recentRoutes":[],"extra":true}""")
        }
        assertFails {
            RouteBackupJson.decode(
                """{"version":1,"savedRoutes":[],"recentRoutes":[{"id":1,"name":"x","geometry":[[25,121],[25.1,121.1]],"distanceMeters":5,"usedAt":0,"savedRouteId":7}]}""",
            )
        }
        assertFails { RouteGeometryCodec.decode("[[91,121],[25,121]]") }
    }

    private fun assertFails(block: () -> Unit) {
        val result = runCatching(block)
        assertTrue("Expected validation to fail", result.isFailure)
    }
}
