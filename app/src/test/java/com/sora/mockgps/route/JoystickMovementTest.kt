package com.sora.mockgps.route

import com.sora.mockgps.core.model.Coordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JoystickMovementTest {
    @Test
    fun `zero magnitude leaves origin unchanged`() {
        val origin = Coordinate(25.0, 121.0)
        val result = JoystickMovement.stepCoordinate(
            origin = origin,
            vector = JoystickVector(bearingDegrees = 90f, magnitude = 0f),
            speed = JoystickSpeed.Run,
            deltaMillis = 500L,
        )
        assertEquals(origin, result)
    }

    @Test
    fun `walk speed advances north over half a second at full magnitude`() {
        val origin = Coordinate(0.0, 0.0)
        val result = JoystickMovement.stepCoordinate(
            origin = origin,
            vector = JoystickVector(bearingDegrees = 0f, magnitude = 1f),
            speed = JoystickSpeed.Walk,
            deltaMillis = 500L,
        )
        val expectedDistance = JoystickMovement.speedMetersPerSecond(JoystickSpeed.Walk) * 0.5
        val actualDistance = GeoMath.distanceMeters(origin, result)
        assertEquals(expectedDistance, actualDistance, 0.01)
        assertTrue(result.latitude > origin.latitude)
        assertEquals(0.0, result.longitude, 0.00001)
    }

    @Test
    fun `car rail and airplane use requested kilometers per hour`() {
        assertEquals(100.0, JoystickSpeed.Car.kilometersPerHour, 0.0)
        assertEquals(300.0, JoystickSpeed.HighSpeedRail.kilometersPerHour, 0.0)
        assertEquals(1_000.0, JoystickSpeed.Airplane.kilometersPerHour, 0.0)
        assertEquals(100.0 / 3.6, JoystickMovement.speedMetersPerSecond(JoystickSpeed.Car), 0.0001)
        assertEquals(1_000.0 / 3.6, JoystickMovement.speedMetersPerSecond(JoystickSpeed.Airplane), 0.0001)
    }
}
