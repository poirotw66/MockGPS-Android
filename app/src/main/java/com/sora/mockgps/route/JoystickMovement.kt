package com.sora.mockgps.route

import com.sora.mockgps.core.model.Coordinate

data class JoystickVector(
    val bearingDegrees: Float,
    val magnitude: Float,
)

enum class JoystickSpeed(val kilometersPerHour: Double) {
    Walk(5.0),
    Run(10.0),
    Bicycle(18.0),
    Car(100.0),
    HighSpeedRail(300.0),
    Airplane(1_000.0),
}

object JoystickMovement {
    private const val KILOMETERS_PER_HOUR_PER_METER_PER_SECOND = 3.6

    fun speedMetersPerSecond(speed: JoystickSpeed): Double =
        speed.kilometersPerHour / KILOMETERS_PER_HOUR_PER_METER_PER_SECOND

    fun stepCoordinate(
        origin: Coordinate,
        vector: JoystickVector,
        speed: JoystickSpeed,
        deltaMillis: Long,
    ): Coordinate {
        if (vector.magnitude <= 0f || deltaMillis <= 0L) return origin
        val distanceMeters = speedMetersPerSecond(speed) * (deltaMillis / 1_000.0) * vector.magnitude
        return GeoMath.destination(origin, vector.bearingDegrees.toDouble(), distanceMeters)
    }
}
