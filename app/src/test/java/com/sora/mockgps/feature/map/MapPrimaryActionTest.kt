package com.sora.mockgps.feature.map

import com.sora.mockgps.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.sora.mockgps.core.model.Coordinate

class MapPrimaryActionTest {
    private val pending = Coordinate(25.0, 121.0)
    private val active = Coordinate(25.1, 121.1)

    @Test
    fun `idle shows start without stop`() {
        val actions = resolveMapDockActions(
            isMapReady = true,
            isStarting = false,
            isActive = false,
            isRouteSession = false,
            isRoutePlanning = false,
            pendingCoordinate = pending,
            activeCoordinate = null,
        )
        assertEquals(MapPrimaryAction.StartMock, actions.primary)
        assertFalse(actions.showStop)
        assertFalse(actions.showPendingApplyHint)
    }

    @Test
    fun `active matching selection shows only stop`() {
        val actions = resolveMapDockActions(
            isMapReady = true,
            isStarting = false,
            isActive = true,
            isRouteSession = false,
            isRoutePlanning = false,
            pendingCoordinate = pending,
            activeCoordinate = pending,
        )
        assertNull(actions.primary)
        assertTrue(actions.showStop)
        assertFalse(actions.showPendingApplyHint)
    }

    @Test
    fun `active with new selection shows apply hint and stop`() {
        val actions = resolveMapDockActions(
            isMapReady = true,
            isStarting = false,
            isActive = true,
            isRouteSession = false,
            isRoutePlanning = false,
            pendingCoordinate = pending,
            activeCoordinate = active,
        )
        assertEquals(MapPrimaryAction.ApplyLocation, actions.primary)
        assertTrue(actions.showStop)
        assertTrue(actions.showPendingApplyHint)
        assertEquals(R.string.action_apply_new_location, actions.primary!!.labelRes)
    }

    @Test
    fun `route session keeps stop on dock and leaves pause to the route sheet`() {
        val actions = resolveMapDockActions(
            isMapReady = true,
            isStarting = false,
            isActive = true,
            isRouteSession = true,
            isRoutePlanning = true,
            pendingCoordinate = pending,
            activeCoordinate = pending,
        )
        assertNull(actions.primary)
        assertTrue(actions.showStop)
        assertFalse(actions.showPendingApplyHint)
    }

    @Test
    fun `route planning hides apply even when the pending point differs`() {
        val actions = resolveMapDockActions(
            isMapReady = true,
            isStarting = false,
            isActive = true,
            isRouteSession = false,
            isRoutePlanning = true,
            pendingCoordinate = pending,
            activeCoordinate = active,
        )
        assertNull(actions.primary)
        assertTrue(actions.showStop)
        assertFalse(actions.showPendingApplyHint)
    }
}
