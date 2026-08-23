package com.sora.mockgps.core.model

/** Clock is injectable so payload generation is deterministic in JVM tests. */
interface MockClock {
    fun currentTimeMillis(): Long
    fun elapsedRealtimeNanos(): Long
}

class MockPayloadFactory(private val clock: MockClock) {
    private var lastWallClockMillis = Long.MIN_VALUE
    private var lastElapsedRealtimeNanos = Long.MIN_VALUE

    @Synchronized
    fun create(
        coordinate: Coordinate,
        options: MockPayloadOptions = MockPayloadOptions(),
    ): MockResult<MockPayload> {
        val validationError = MockPayloadValidator.validate(coordinate, options)
        if (validationError != null) return MockResult.Failure(MockError.InvalidPayload(validationError))

        val wallClockMillis = clock.currentTimeMillis().coerceAtLeast(nextAfter(lastWallClockMillis))
        val elapsedRealtimeNanos = clock.elapsedRealtimeNanos().coerceAtLeast(nextAfter(lastElapsedRealtimeNanos))
        lastWallClockMillis = wallClockMillis
        lastElapsedRealtimeNanos = elapsedRealtimeNanos

        return MockResult.Success(
            MockPayload(
                coordinate = coordinate,
                accuracyMeters = options.accuracyMeters,
                wallClockMillis = wallClockMillis,
                elapsedRealtimeNanos = elapsedRealtimeNanos,
                altitudeMeters = options.altitudeMeters,
                speedMetersPerSecond = options.speedMetersPerSecond,
                bearingDegrees = options.bearingDegrees,
            ),
        )
    }

    private fun nextAfter(value: Long): Long = if (value == Long.MAX_VALUE) Long.MAX_VALUE else value + 1
}
