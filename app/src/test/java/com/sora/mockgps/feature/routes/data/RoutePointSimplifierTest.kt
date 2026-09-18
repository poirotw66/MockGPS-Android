package com.sora.mockgps.feature.routes.data

import com.sora.mockgps.core.model.Coordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutePointSimplifierTest {
    @Test
    fun `simplify keeps endpoints and respects max points`() {
        val points = (0..4_000).map { index ->
            Coordinate(25.0 + index * 0.00001, 121.0 + index * 0.00001)
        }
        val simplified = RoutePointSimplifier.simplifyToMaxPoints(points, RouteDataValidator.MAX_POINTS)
        assertTrue(simplified.size <= RouteDataValidator.MAX_POINTS)
        assertEquals(points.first(), simplified.first())
        assertEquals(points.last(), simplified.last())
    }

    @Test
    fun `short routes are unchanged`() {
        val points = listOf(
            Coordinate(25.0, 121.0),
            Coordinate(25.1, 121.1),
            Coordinate(25.2, 121.2),
        )
        assertEquals(points, RoutePointSimplifier.simplifyToMaxPoints(points, 2_000))
    }
}
