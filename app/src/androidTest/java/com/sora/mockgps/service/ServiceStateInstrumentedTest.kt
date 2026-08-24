package com.sora.mockgps.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sora.mockgps.R
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

    @Test fun notifications_expose_the_expected_route_and_stop_actions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val stop = context.getString(R.string.action_stop)
        val pause = MockLocationNotification.build(context, 25.0, 121.0, RouteNotificationAction.Pause)
        val resume = MockLocationNotification.build(context, 25.0, 121.0, RouteNotificationAction.Resume)
        val static = MockLocationNotification.build(context, 25.0, 121.0)

        assertEquals(listOf(context.getString(R.string.action_pause_route), stop), pause.actions.map { it.title.toString() })
        assertEquals(listOf(context.getString(R.string.action_resume_route), stop), resume.actions.map { it.title.toString() })
        assertEquals(listOf(stop), static.actions.map { it.title.toString() })
    }
}
