package com.sora.mockgps.route

import com.sora.mockgps.core.model.Coordinate
import kotlin.math.sqrt

/** A validated speed choice for simulated movement. Public speeds are expressed in km/h. */
sealed class MovementProfile(
    val displayName: String,
    val kilometersPerHour: Double,
) {
    val metersPerSecond: Double get() = kilometersPerHour / KILOMETERS_PER_HOUR_PER_METER_PER_SECOND

    data object Walk : MovementProfile("Walk", 5.0)
    data object Run : MovementProfile("Run", 10.0)
    data object Bicycle : MovementProfile("Bicycle", 18.0)
    data class Driving(val speedKilometersPerHour: Double = DEFAULT_DRIVING_KILOMETERS_PER_HOUR) :
        MovementProfile("Driving", speedKilometersPerHour) {
        init {
            validateMovementSpeed(speedKilometersPerHour)
        }
    }

    data class Custom(val speedKilometersPerHour: Double) : MovementProfile("Custom", speedKilometersPerHour) {
        init {
            validateMovementSpeed(speedKilometersPerHour)
        }
    }

    companion object {
        const val DEFAULT_DRIVING_KILOMETERS_PER_HOUR = 50.0
        const val MAXIMUM_KILOMETERS_PER_HOUR = 300.0
        private const val KILOMETERS_PER_HOUR_PER_METER_PER_SECOND = 3.6
    }
}

private fun validateMovementSpeed(speedKilometersPerHour: Double) {
    require(speedKilometersPerHour.isFinite() && speedKilometersPerHour > 0.0) {
        "Speed must be a positive finite value."
    }
    require(speedKilometersPerHour <= MovementProfile.MAXIMUM_KILOMETERS_PER_HOUR) {
        "Speed cannot exceed ${MovementProfile.MAXIMUM_KILOMETERS_PER_HOUR} km/h."
    }
}

/**
 * Limits changes to the target speed. The default is intentionally instant so existing route
 * playback keeps its current 18 km/h behaviour until a caller opts in to smoothing.
 */
data class AccelerationModel(
    val accelerationMetersPerSecondSquared: Double = Double.POSITIVE_INFINITY,
    val decelerationMetersPerSecondSquared: Double = Double.POSITIVE_INFINITY,
) {
    init {
        require(accelerationMetersPerSecondSquared.isPositiveFiniteOrInfinity()) {
            "Acceleration must be positive."
        }
        require(decelerationMetersPerSecondSquared.isPositiveFiniteOrInfinity()) {
            "Deceleration must be positive."
        }
    }

    companion object {
        val Instant = AccelerationModel()
    }
}

enum class RouteExecutionMode {
    /** End the session on the first arrival at the destination. */
    StopAtEnd,
    /** Start again at the route origin after every arrival. */
    Loop,
    /** Travel the same geometry in the opposite direction after every arrival. */
    Reverse,
}

enum class RouteExecutionState { READY, RUNNING, PAUSED, REACHED_END, STOPPED }

enum class RouteDirection { Forward, Reverse }

data class RouteProgress(
    val travelledMeters: Double,
    val remainingMeters: Double,
    val totalMeters: Double,
    val fraction: Double,
    val completedLegs: Long,
    val direction: RouteDirection,
)

object RouteProgressCalculator {
    fun calculate(
        totalMeters: Double,
        travelledMeters: Double,
        completedLegs: Long = 0,
        direction: RouteDirection = RouteDirection.Forward,
    ): RouteProgress {
        require(totalMeters.isFinite() && totalMeters > 0.0) { "Total distance must be positive." }
        require(completedLegs >= 0) { "Completed legs cannot be negative." }
        val travelled = travelledMeters.coerceIn(0.0, totalMeters)
        return RouteProgress(
            travelledMeters = travelled,
            remainingMeters = totalMeters - travelled,
            totalMeters = totalMeters,
            fraction = travelled / totalMeters,
            completedLegs = completedLegs,
            direction = direction,
        )
    }
}

/** A mutable-by-copy route draft for a UI that lets users set and edit ordered waypoints. */
data class WaypointDraft(val waypoints: List<Coordinate>) {
    init {
        require(waypoints.all(::isValidCoordinate)) { "Waypoint contains an invalid coordinate." }
    }

    val canBuildRoute: Boolean get() = waypoints.size >= 2 && waypoints.zipWithNext().any { it.first != it.second }

    fun add(coordinate: Coordinate, index: Int = waypoints.size): WaypointDraft {
        require(index in 0..waypoints.size) { "Waypoint insertion index is out of bounds." }
        require(isValidCoordinate(coordinate)) { "Waypoint contains an invalid coordinate." }
        return WaypointDraft(waypoints.toMutableList().also { it.add(index, coordinate) })
    }

    fun removeAt(index: Int): WaypointDraft {
        require(index in waypoints.indices) { "Waypoint index is out of bounds." }
        return WaypointDraft(waypoints.toMutableList().also { it.removeAt(index) })
    }

    fun move(fromIndex: Int, toIndex: Int): WaypointDraft {
        require(fromIndex in waypoints.indices && toIndex in waypoints.indices) { "Waypoint index is out of bounds." }
        if (fromIndex == toIndex) return this
        return WaypointDraft(waypoints.toMutableList().also { points -> points.add(toIndex, points.removeAt(fromIndex)) })
    }

    fun swapEndpoints(): WaypointDraft {
        if (waypoints.size < 2) return this
        return WaypointDraft(waypoints.toMutableList().also { points ->
            val first = points.first()
            points[0] = points.last()
            points[points.lastIndex] = first
        })
    }

    fun toRoutePolyline(): RoutePolyline {
        require(canBuildRoute) { "At least two distinct consecutive waypoints are required." }
        return RoutePolyline(waypoints)
    }

    companion object {
        fun empty(): WaypointDraft = WaypointDraft(emptyList())
        fun of(waypoints: List<Coordinate>): WaypointDraft = WaypointDraft(waypoints.toList())
    }
}

/**
 * Deterministic, opt-in horizontal noise for repeatable QA scenarios. It never mutates the route
 * geometry; callers may use [apply] only for the coordinate reported to a mock provider.
 */
data class GpsDriftConfiguration(
    val maximumHorizontalMeters: Double = 0.0,
    val seed: Long = 0L,
) {
    init {
        require(maximumHorizontalMeters.isFinite() && maximumHorizontalMeters >= 0.0) {
            "Maximum GPS drift must be finite and non-negative."
        }
        require(maximumHorizontalMeters <= MAXIMUM_DRIFT_METERS) {
            "Maximum GPS drift cannot exceed $MAXIMUM_DRIFT_METERS metres."
        }
    }

    val isEnabled: Boolean get() = maximumHorizontalMeters > 0.0

    fun apply(coordinate: Coordinate, sampleIndex: Long): Coordinate {
        if (!isEnabled) return coordinate
        val first = unitRandom(mix(seed xor sampleIndex))
        val second = unitRandom(mix(seed + GOLDEN_GAMMA xor sampleIndex))
        val distance = sqrt(first) * maximumHorizontalMeters
        return GeoMath.destination(coordinate, bearingDegrees = second * 360.0, distanceMeters = distance)
    }

    companion object {
        const val MAXIMUM_DRIFT_METERS = 100.0
        private const val GOLDEN_GAMMA = -7046029254386353131L

        private fun mix(value: Long): Long {
            var mixed = value + GOLDEN_GAMMA
            mixed = (mixed xor (mixed ushr 30)) * -4658895280553007687L
            mixed = (mixed xor (mixed ushr 27)) * -7723592293110705685L
            return mixed xor (mixed ushr 31)
        }

        private fun unitRandom(value: Long): Double = (value ushr 11).toDouble() / (1L shl 53).toDouble()
    }
}

/**
 * A time-based execution engine that supports profile speed, acceleration and end behaviour.
 * It is separate from [RoutePlayback] so existing service callers stay source compatible.
 */
class RouteExecution(
    val route: RoutePolyline,
    val movementProfile: MovementProfile = MovementProfile.Bicycle,
    val accelerationModel: AccelerationModel = AccelerationModel.Instant,
    val executionMode: RouteExecutionMode = RouteExecutionMode.StopAtEnd,
    val gpsDrift: GpsDriftConfiguration = GpsDriftConfiguration(),
) {
    private var state = RouteExecutionState.READY
    private var direction = RouteDirection.Forward
    private var travelledMeters = 0.0
    private var currentSpeedMetersPerSecond = 0.0
    private var completedLegs = 0L
    private var sampleIndex = 0L
    private var lastTimestampMillis: Long? = null

    @Synchronized
    fun start(timestampMillis: Long): RouteExecutionSnapshot {
        state = RouteExecutionState.RUNNING
        direction = RouteDirection.Forward
        travelledMeters = 0.0
        currentSpeedMetersPerSecond = if (accelerationModel == AccelerationModel.Instant) {
            movementProfile.metersPerSecond
        } else {
            0.0
        }
        completedLegs = 0L
        sampleIndex = 0L
        lastTimestampMillis = timestampMillis
        return snapshot()
    }

    @Synchronized
    fun advance(timestampMillis: Long): RouteExecutionSnapshot {
        if (state != RouteExecutionState.RUNNING) return snapshot()
        val lastTimestamp = requireNotNull(lastTimestampMillis)
        val elapsedMillis = (timestampMillis - lastTimestamp).coerceAtLeast(0L)
        lastTimestampMillis = maxOf(lastTimestamp, timestampMillis)
        advanceSeconds(elapsedMillis / 1_000.0)
        sampleIndex++
        return snapshot()
    }

    @Synchronized
    fun pause(timestampMillis: Long): RouteExecutionSnapshot {
        advance(timestampMillis)
        if (state == RouteExecutionState.RUNNING) {
            state = RouteExecutionState.PAUSED
            lastTimestampMillis = null
        }
        return snapshot()
    }

    @Synchronized
    fun resume(timestampMillis: Long): RouteExecutionSnapshot {
        if (state == RouteExecutionState.PAUSED) {
            state = RouteExecutionState.RUNNING
            lastTimestampMillis = timestampMillis
        }
        return snapshot()
    }

    @Synchronized
    fun stop(): RouteExecutionSnapshot {
        state = RouteExecutionState.STOPPED
        currentSpeedMetersPerSecond = 0.0
        lastTimestampMillis = null
        return snapshot()
    }

    @Synchronized
    fun snapshot(): RouteExecutionSnapshot {
        val position = route.positionAt(if (direction == RouteDirection.Forward) travelledMeters else route.totalDistanceMeters - travelledMeters)
        val correctedBearing = if (direction == RouteDirection.Forward) position.bearingDegrees else (position.bearingDegrees + 180f) % 360f
        val reportedCoordinate = gpsDrift.apply(position.coordinate, sampleIndex)
        return RouteExecutionSnapshot(
            state = state,
            position = position.copy(bearingDegrees = correctedBearing),
            reportedCoordinate = reportedCoordinate,
            progress = RouteProgressCalculator.calculate(route.totalDistanceMeters, travelledMeters, completedLegs, direction),
            speedMetersPerSecond = currentSpeedMetersPerSecond,
        )
    }

    private fun advanceSeconds(totalSeconds: Double) {
        var remainingSeconds = totalSeconds
        // 20 Hz bounded integration is deterministic and prevents a delayed service tick from
        // tunnelling through a braking point or a loop/reverse endpoint.
        while (remainingSeconds > 0.0 && state == RouteExecutionState.RUNNING) {
            val seconds = minOf(remainingSeconds, MAX_INTEGRATION_STEP_SECONDS)
            advanceStep(seconds)
            remainingSeconds -= seconds
        }
    }

    private fun advanceStep(seconds: Double) {
        val remainingDistance = route.totalDistanceMeters - travelledMeters
        val brakeLimitedTarget = if (accelerationModel.decelerationMetersPerSecondSquared.isFinite()) {
            sqrt(2.0 * accelerationModel.decelerationMetersPerSecondSquared * remainingDistance)
        } else {
            movementProfile.metersPerSecond
        }
        val targetSpeed = minOf(movementProfile.metersPerSecond, brakeLimitedTarget)
        val nextSpeed = changeSpeedToward(currentSpeedMetersPerSecond, targetSpeed, seconds)
        val deltaDistance = ((currentSpeedMetersPerSecond + nextSpeed) / 2.0 * seconds).coerceAtLeast(0.0)
        currentSpeedMetersPerSecond = nextSpeed

        if (deltaDistance < remainingDistance) {
            travelledMeters += deltaDistance
            return
        }

        travelledMeters = route.totalDistanceMeters
        currentSpeedMetersPerSecond = 0.0
        completedLegs++
        when (executionMode) {
            RouteExecutionMode.StopAtEnd -> {
                state = RouteExecutionState.REACHED_END
                lastTimestampMillis = null
            }
            RouteExecutionMode.Loop -> travelledMeters = 0.0
            RouteExecutionMode.Reverse -> {
                travelledMeters = 0.0
                direction = if (direction == RouteDirection.Forward) RouteDirection.Reverse else RouteDirection.Forward
            }
        }
    }

    private fun changeSpeedToward(current: Double, target: Double, seconds: Double): Double {
        if (!accelerationModel.accelerationMetersPerSecondSquared.isFinite() &&
            !accelerationModel.decelerationMetersPerSecondSquared.isFinite()
        ) return target
        val limit = if (target >= current) {
            accelerationModel.accelerationMetersPerSecondSquared
        } else {
            accelerationModel.decelerationMetersPerSecondSquared
        }
        if (!limit.isFinite()) return target
        return if (target >= current) minOf(target, current + limit * seconds) else maxOf(target, current - limit * seconds)
    }

    private companion object {
        const val MAX_INTEGRATION_STEP_SECONDS = 0.05
    }
}

data class RouteExecutionSnapshot(
    val state: RouteExecutionState,
    val position: RoutePosition,
    val reportedCoordinate: Coordinate,
    val progress: RouteProgress,
    val speedMetersPerSecond: Double,
)

private fun Double.isPositiveFiniteOrInfinity(): Boolean = (isFinite() && this > 0.0) || this == Double.POSITIVE_INFINITY

private fun isValidCoordinate(coordinate: Coordinate): Boolean =
    coordinate.latitude.isFinite() && coordinate.latitude in -90.0..90.0 &&
        coordinate.longitude.isFinite() && coordinate.longitude in -180.0..180.0
