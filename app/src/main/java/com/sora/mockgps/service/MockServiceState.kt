package com.sora.mockgps.service

import com.sora.mockgps.core.model.Coordinate
import com.sora.mockgps.route.RouteExecutionSnapshot

/** The live service state. It is deliberately not persisted. */
sealed interface MockServiceState {
    data object Idle : MockServiceState
    data class Starting(val coordinate: Coordinate) : MockServiceState
    data class Active(val coordinate: Coordinate) : MockServiceState
    data class Error(val message: String) : MockServiceState
}

/**
 * The authoritative route-session state. Unlike [MockServiceState], this distinguishes route
 * playback from a static mock location without changing the static UI contract.
 */
sealed interface RouteServiceState {
    val sessionToken: ServiceSessionToken?

    data object Idle : RouteServiceState {
        override val sessionToken: ServiceSessionToken? = null
    }
}

data class RouteProgress(
    val coordinate: Coordinate,
    val travelledDistanceMeters: Double,
    val remainingDistanceMeters: Double,
    val totalDistanceMeters: Double,
    val speedMetersPerSecond: Double,
    val bearingDegrees: Float,
) {
    init {
        require(totalDistanceMeters.isFinite() && totalDistanceMeters > 0.0)
        require(travelledDistanceMeters.isFinite() && travelledDistanceMeters in 0.0..totalDistanceMeters)
        require(remainingDistanceMeters.isFinite() && remainingDistanceMeters in 0.0..totalDistanceMeters)
        require(speedMetersPerSecond.isFinite() && speedMetersPerSecond >= 0.0)
        require(bearingDegrees.isFinite() && bearingDegrees >= 0f && bearingDegrees < 360f)
    }
}

internal fun RouteExecutionSnapshot.toRouteProgress(): RouteProgress = RouteProgress(
    coordinate = reportedCoordinate,
    travelledDistanceMeters = progress.travelledMeters,
    remainingDistanceMeters = progress.remainingMeters,
    totalDistanceMeters = progress.totalMeters,
    speedMetersPerSecond = speedMetersPerSecond,
    bearingDegrees = position.bearingDegrees,
)

data class RouteStarting(
    val progress: RouteProgress,
    override val sessionToken: ServiceSessionToken? = null,
) : RouteServiceState
data class RouteRunning(
    val progress: RouteProgress,
    override val sessionToken: ServiceSessionToken? = null,
) : RouteServiceState
data class RoutePaused(
    val progress: RouteProgress,
    override val sessionToken: ServiceSessionToken? = null,
) : RouteServiceState
data class RouteCompleted(
    val progress: RouteProgress,
    override val sessionToken: ServiceSessionToken? = null,
) : RouteServiceState
data class RouteFailed(
    val message: String,
    val lastProgress: RouteProgress?,
    override val sessionToken: ServiceSessionToken? = null,
) : RouteServiceState
