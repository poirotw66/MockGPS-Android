package com.sora.mockgps.feature.map

import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundServiceStartOutcomeTest {
    @Test
    fun `successful start returns started`() {
        assertSame(ForegroundServiceStartOutcome.Started, foregroundServiceStartOutcome {})
    }

    @Test
    fun `security failures retain setup classification and cause`() {
        val cause = SecurityException("permission denied")
        val outcome = foregroundServiceStartOutcome { throw cause }

        assertTrue(outcome is ForegroundServiceStartOutcome.SecurityOrSetup)
        assertSame(cause, (outcome as ForegroundServiceStartOutcome.SecurityOrSetup).cause)
    }

    @Test
    fun `unexpected failures retain generic classification and cause`() {
        val cause = IllegalStateException("unexpected")
        val outcome = foregroundServiceStartOutcome { throw cause }

        assertTrue(outcome is ForegroundServiceStartOutcome.Failed)
        assertSame(cause, (outcome as ForegroundServiceStartOutcome.Failed).cause)
    }
}