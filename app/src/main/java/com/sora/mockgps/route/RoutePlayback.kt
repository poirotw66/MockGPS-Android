package com.sora.mockgps.route

/** Time-based movement over a [RoutePolyline]. All times must use the same monotonic clock. */
class RoutePlayback(
    val route: RoutePolyline,
    val speedMetersPerSecond: Double = BICYCLE_SPEED_METERS_PER_SECOND,
) {
    private var mode = RoutePlaybackMode.READY
    private var travelledMeters = 0.0
    private var lastTimestampMillis: Long? = null

    init {
        require(speedMetersPerSecond.isFinite() && speedMetersPerSecond > 0.0) { "Speed must be positive" }
    }

    @Synchronized
    fun start(timestampMillis: Long): RoutePlaybackSnapshot {
        travelledMeters = 0.0
        mode = RoutePlaybackMode.RUNNING
        lastTimestampMillis = timestampMillis
        return snapshot()
    }

    @Synchronized
    fun advance(timestampMillis: Long): RoutePlaybackSnapshot {
        if (mode != RoutePlaybackMode.RUNNING) return snapshot()
        val lastTimestamp = requireNotNull(lastTimestampMillis)
        val elapsedMillis = (timestampMillis - lastTimestamp).coerceAtLeast(0L)
        lastTimestampMillis = maxOf(lastTimestamp, timestampMillis)
        travelledMeters = (travelledMeters + elapsedMillis * speedMetersPerSecond / MILLIS_PER_SECOND)
            .coerceAtMost(route.totalDistanceMeters)
        if (travelledMeters >= route.totalDistanceMeters) {
            mode = RoutePlaybackMode.REACHED_END
            lastTimestampMillis = null
        }
        return snapshot()
    }

    @Synchronized
    fun pause(timestampMillis: Long): RoutePlaybackSnapshot {
        advance(timestampMillis)
        if (mode == RoutePlaybackMode.RUNNING) {
            mode = RoutePlaybackMode.PAUSED
            lastTimestampMillis = null
        }
        return snapshot()
    }

    @Synchronized
    fun resume(timestampMillis: Long): RoutePlaybackSnapshot {
        if (mode == RoutePlaybackMode.PAUSED) {
            mode = RoutePlaybackMode.RUNNING
            lastTimestampMillis = timestampMillis
        }
        return snapshot()
    }

    @Synchronized
    fun stop(): RoutePlaybackSnapshot {
        mode = RoutePlaybackMode.STOPPED
        lastTimestampMillis = null
        return snapshot()
    }

    @Synchronized
    fun snapshot(): RoutePlaybackSnapshot = RoutePlaybackSnapshot(
        mode = mode,
        position = route.positionAt(travelledMeters),
        remainingDistanceMeters = route.totalDistanceMeters - travelledMeters,
        speedMetersPerSecond = speedMetersPerSecond,
    )

    companion object {
        const val BICYCLE_SPEED_KILOMETERS_PER_HOUR = 18.0
        const val BICYCLE_SPEED_METERS_PER_SECOND = BICYCLE_SPEED_KILOMETERS_PER_HOUR / 3.6
        private const val MILLIS_PER_SECOND = 1_000.0
    }
}

enum class RoutePlaybackMode { READY, RUNNING, PAUSED, REACHED_END, STOPPED }

data class RoutePlaybackSnapshot(
    val mode: RoutePlaybackMode,
    val position: RoutePosition,
    val remainingDistanceMeters: Double,
    val speedMetersPerSecond: Double,
)
