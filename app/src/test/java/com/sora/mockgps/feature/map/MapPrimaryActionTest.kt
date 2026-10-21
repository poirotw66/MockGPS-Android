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
            hasPlannedRoute = false,
            pendingCoordinate = pending,
            activeCoordinate = null,
        )
        assertEquals(MapPrimaryAction.StartMock, actions.primary)
        assertFalse(actions.showStop)
        assertFalse(actions.showPendingApplyHint)
        assertFalse(actions.showStartRoute)
    }

    @Test
    fun `active matching selection shows only stop`() {
        val actions = resolveMapDockActions(
            isMapReady = true,
            isStarting = false,
            isActive = true,
            isRouteSession = false,
            isRoutePlanning = false,
            hasPlannedRoute = false,
            pendingCoordinate = pending,
            activeCoordinate = pending,
        )
        assertNull(actions.primary)
        assertTrue(actions.showStop)
        assertFalse(actions.showPendingApplyHint)
        assertFalse(actions.showStartRoute)
    }

    @Test
    fun `active with new selection shows apply hint and stop`() {
        val actions = resolveMapDockActions(
            isMapReady = true,
            isStarting = false,
            isActive = true,
            isRouteSession = false,
            isRoutePlanning = false,
            hasPlannedRoute = false,
            pendingCoordinate = pending,
            activeCoordinate = active,
        )
        assertEquals(MapPrimaryAction.ApplyLocation, actions.primary)
        assertTrue(actions.showStop)
        assertTrue(actions.showPendingApplyHint)
        assertFalse(actions.showStartRoute)
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
            hasPlannedRoute = true,
            pendingCoordinate = pending,
            activeCoordinate = pending,
        )
        assertNull(actions.primary)
        assertTrue(actions.showStop)
        assertFalse(actions.showPendingApplyHint)
        assertFalse(actions.showStartRoute)
    }

    @Test
    fun `route planning hides apply even when the pending point differs`() {
        val actions = resolveMapDockActions(
            isMapReady = true,
            isStarting = false,
            isActive = true,
            isRouteSession = false,
            isRoutePlanning = true,
            hasPlannedRoute = false,
            pendingCoordinate = pending,
            activeCoordinate = active,
        )
        assertNull(actions.primary)
        assertTrue(actions.showStop)
        assertFalse(actions.showPendingApplyHint)
        assertFalse(actions.showStartRoute)
    }

    @Test
    fun `ready route shows start simulation without replacing mock controls`() {
        val idle = resolveMapDockActions(
            isMapReady = true,
            isStarting = false,
            isActive = false,
            isRouteSession = false,
            isRoutePlanning = true,
            hasPlannedRoute = true,
            pendingCoordinate = pending,
            activeCoordinate = null,
        )
        assertEquals(MapPrimaryAction.StartMock, idle.primary)
        assertTrue(idle.showStartRoute)

        val mocking = resolveMapDockActions(
            isMapReady = true,
            isStarting = false,
            isActive = true,
            isRouteSession = false,
            isRoutePlanning = true,
            hasPlannedRoute = true,
            pendingCoordinate = pending,
            activeCoordinate = active,
        )
        assertNull(mocking.primary)
        assertTrue(mocking.showStop)
        assertTrue(mocking.showStartRoute)
        assertFalse(mocking.showPendingApplyHint)
    }
}
