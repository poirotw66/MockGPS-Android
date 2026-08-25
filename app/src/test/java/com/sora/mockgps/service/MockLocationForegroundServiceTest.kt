package com.sora.mockgps.service

import com.sora.mockgps.core.model.EngineFailure
import com.sora.mockgps.core.model.EngineOperation
import com.sora.mockgps.core.model.MockError
import org.junit.Assert.assertEquals
import org.junit.Test

class MockLocationForegroundServiceTest {
    @Test
    fun `spike defaults to Taipei 101 and uses explicit commands`() {
        assertEquals(25.033964, MockLocationForegroundService.DEFAULT_COORDINATE.latitude, 0.0)
        assertEquals(121.564468, MockLocationForegroundService.DEFAULT_COORDINATE.longitude, 0.0)
        assertEquals("com.sora.mockgps.action.START", MockLocationForegroundService.ACTION_START)
        assertEquals("com.sora.mockgps.action.STOP", MockLocationForegroundService.ACTION_STOP)
        assertEquals("com.sora.mockgps.action.UPDATE", MockLocationForegroundService.ACTION_UPDATE)
        assertEquals("com.sora.mockgps.action.START_ROUTE", MockLocationForegroundService.ACTION_START_ROUTE)
        assertEquals("com.sora.mockgps.action.PAUSE_ROUTE", MockLocationForegroundService.ACTION_PAUSE_ROUTE)
        assertEquals("com.sora.mockgps.action.RESUME_ROUTE", MockLocationForegroundService.ACTION_RESUME_ROUTE)
        assertEquals("com.sora.mockgps.action.STOP_ROUTE", MockLocationForegroundService.ACTION_STOP_ROUTE)
        assertEquals("com.sora.mockgps.action.SET_JOYSTICK", MockLocationForegroundService.ACTION_SET_JOYSTICK)
        assertEquals(
            "com.sora.mockgps.extra.ROUTE_MOVEMENT_PROFILE",
            MockLocationForegroundService.EXTRA_ROUTE_MOVEMENT_PROFILE,
        )
        assertEquals(
            "com.sora.mockgps.extra.ROUTE_EXECUTION_MODE",
            MockLocationForegroundService.EXTRA_ROUTE_EXECUTION_MODE,
        )
        assertEquals(
            "com.sora.mockgps.extra.ROUTE_GPS_DRIFT_METERS",
            MockLocationForegroundService.EXTRA_ROUTE_GPS_DRIFT_METERS,
        )
        assertEquals("com.sora.mockgps.extra.SESSION_ID", MockLocationForegroundService.EXTRA_SESSION_ID)
        assertEquals(
            "com.sora.mockgps.extra.SESSION_GENERATION",
            MockLocationForegroundService.EXTRA_SESSION_GENERATION,
        )
        assertEquals(1_000L, MockLocationForegroundService.UPDATE_INTERVAL_MILLIS)
    }

    @Test
    fun `service errors distinguish setup Play services and generic failures`() {
        assertEquals(
            MockServiceErrorKind.MockAppSetup,
            MockError.StartFailed("framework-gps", SecurityException()).toServiceErrorKind(),
        )
        assertEquals(
            MockServiceErrorKind.GooglePlayServices,
            MockError.StartFailed("fused-location", IllegalStateException()).toServiceErrorKind(),
        )
        assertEquals(
            MockServiceErrorKind.Generic,
            MockError.StopFailed(
                listOf(EngineFailure("framework-gps", EngineOperation.STOP, IllegalStateException())),
            ).toServiceErrorKind(),
        )
    }
}
