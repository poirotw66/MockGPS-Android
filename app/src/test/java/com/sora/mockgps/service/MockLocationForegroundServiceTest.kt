package com.sora.mockgps.service

import org.junit.Assert.assertEquals
import org.junit.Test

class MockLocationForegroundServiceTest {
    @Test
    fun `spike defaults to Taipei 101 and uses explicit commands`() {
        assertEquals(25.033964, MockLocationForegroundService.DEFAULT_COORDINATE.latitude, 0.0)
        assertEquals(121.564468, MockLocationForegroundService.DEFAULT_COORDINATE.longitude, 0.0)
        assertEquals("com.sora.mockgps.action.START", MockLocationForegroundService.ACTION_START)
        assertEquals("com.sora.mockgps.action.STOP", MockLocationForegroundService.ACTION_STOP)
        assertEquals(1_000L, MockLocationForegroundService.UPDATE_INTERVAL_MILLIS)
    }
}
