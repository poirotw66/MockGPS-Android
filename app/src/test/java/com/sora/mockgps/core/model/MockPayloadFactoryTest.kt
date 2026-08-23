package com.sora.mockgps.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MockPayloadFactoryTest {
    @Test
    fun `create rejects invalid coordinate and optional fields`() {
        val factory = MockPayloadFactory(FakeClock())

        val latitudeFailure = factory.create(Coordinate(90.1, 0.0))
        val bearingFailure = factory.create(
            Coordinate(25.033, 121.565),
            MockPayloadOptions(bearingDegrees = 360f),
        )

        assertTrue(latitudeFailure is MockResult.Failure)
        assertEquals(
            PayloadValidationError.InvalidLatitude(90.1),
            (latitudeFailure as MockResult.Failure).error.let { (it as MockError.InvalidPayload).reason },
        )
        assertTrue(bearingFailure is MockResult.Failure)
    }

    @Test
    fun `create carries every supported location attribute`() {
        val factory = MockPayloadFactory(FakeClock())

        val result = factory.create(
            Coordinate(25.033964, 121.564468),
            MockPayloadOptions(accuracyMeters = 3f, altitudeMeters = 10.5, speedMetersPerSecond = 2.5f, bearingDegrees = 180f),
        )

        val payload = (result as MockResult.Success).value
        assertEquals(25.033964, payload.coordinate.latitude, 0.0)
        assertEquals(3f, payload.accuracyMeters, 0f)
        assertEquals(10.5, requireNotNull(payload.altitudeMeters), 0.0)
        assertEquals(2.5f, requireNotNull(payload.speedMetersPerSecond), 0f)
        assertEquals(180f, requireNotNull(payload.bearingDegrees), 0f)
    }

    @Test
    fun `create makes timestamps advance even if injected clock regresses`() {
        val clock = FakeClock(wallClock = 100, elapsedNanos = 200)
        val factory = MockPayloadFactory(clock)

        val first = (factory.create(Coordinate(0.0, 0.0)) as MockResult.Success).value
        clock.wallClock = 50
        clock.elapsedNanos = 100
        val second = (factory.create(Coordinate(0.0, 0.0)) as MockResult.Success).value

        assertEquals(101, second.wallClockMillis)
        assertEquals(201, second.elapsedRealtimeNanos)
        assertTrue(second.wallClockMillis > first.wallClockMillis)
        assertTrue(second.elapsedRealtimeNanos > first.elapsedRealtimeNanos)
    }

    private class FakeClock(
        var wallClock: Long = 1_000,
        var elapsedNanos: Long = 2_000,
    ) : MockClock {
        override fun currentTimeMillis(): Long = wallClock
        override fun elapsedRealtimeNanos(): Long = elapsedNanos
    }
}
