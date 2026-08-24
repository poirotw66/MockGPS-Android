package com.sora.mockgps.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Device-side smoke coverage for state exposed to Compose and notification/service clients. */
@RunWith(AndroidJUnit4::class)
class ServiceStateInstrumentedTest {
    @Test fun idle_state_is_safe_before_a_service_session() {
        assertEquals(MockServiceState.Idle, MockLocationForegroundService.state.value)
        assertEquals(RouteServiceState.Idle, MockLocationForegroundService.routeState.value)
    }

    @Test fun notification_ids_are_stable() {
        assertTrue(MockLocationNotification.NOTIFICATION_ID > 0)
        assertTrue(MockLocationNotification.CHANNEL_ID.isNotBlank())
    }
}
