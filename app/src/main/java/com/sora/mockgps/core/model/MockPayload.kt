package com.sora.mockgps.core.model

data class MockPayload(
    val coordinate: Coordinate,
    val accuracyMeters: Float,
    val wallClockMillis: Long,
    val elapsedRealtimeNanos: Long,
    val altitudeMeters: Double? = null,
    val speedMetersPerSecond: Float? = null,
    val bearingDegrees: Float? = null,
)

data class MockPayloadOptions(
    val accuracyMeters: Float = DEFAULT_ACCURACY_METERS,
    val altitudeMeters: Double? = null,
    val speedMetersPerSecond: Float? = null,
    val bearingDegrees: Float? = null,
) {
    companion object {
        const val DEFAULT_ACCURACY_METERS = 5f
    }
}
