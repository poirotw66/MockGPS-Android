package com.sora.mockgps.route

import com.sora.mockgps.core.model.Coordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteExperienceTest {
    private val origin = Coordinate(0.0, 0.0)
    private val middle = Coordinate(0.0, 0.001)
    private val destination = Coordinate(0.0, 0.002)

    @Test
    fun `movement profiles expose product speeds and validate configurable speeds`() {
        assertEquals(5.0, MovementProfile.Walk.kilometersPerHour, 0.0)
        assertEquals(10.0, MovementProfile.Run.kilometersPerHour, 0.0)
        assertEquals(18.0, MovementProfile.Bicycle.kilometersPerHour, 0.0)
        assertEquals(50.0, MovementProfile.Driving().kilometersPerHour, 0.0)
        assertEquals(5.0, MovementProfile.Bicycle.metersPerSecond, 0.0)

        assertThrows(IllegalArgumentException::class.java) { MovementProfile.Custom(0.0) }
        assertThrows(IllegalArgumentException::class.java) { MovementProfile.Driving(301.0) }
    }

    @Test
    fun `waypoint draft supports add remove reorder and endpoint swap`() {
        val draft = WaypointDraft.empty()
            .add(origin)
            .add(destination)
            .add(middle, index = 1)
            .move(fromIndex = 2, toIndex = 1)
            .swapEndpoints()
            .removeAt(1)

        assertEquals(listOf(middle, origin), draft.waypoints)
        assertTrue(draft.canBuildRoute)
        assertTrue(draft.toRoutePolyline().totalDistanceMeters > 0.0)
    }

    @Test
    fun `progress clamps distance and retains execution metadata`() {
        val progress = RouteProgressCalculator.calculate(
            totalMeters = 100.0,
            travelledMeters = 120.0,
            completedLegs = 3,
            direction = RouteDirection.Reverse,
        )

        assertEquals(100.0, progress.travelledMeters, 0.0)
        assertEquals(0.0, progress.remainingMeters, 0.0)
        assertEquals(1.0, progress.fraction, 0.0)
        assertEquals(3L, progress.completedLegs)
        assertEquals(RouteDirection.Reverse, progress.direction)
    }

    @Test
    fun `acceleration model ramps speed deterministically`() {
        val execution = RouteExecution(
            route = RoutePolyline(listOf(origin, Coordinate(0.0, 0.02))),
            movementProfile = MovementProfile.Custom(36.0),
            accelerationModel = AccelerationModel(
                accelerationMetersPerSecondSquared = 2.0,
                decelerationMetersPerSecondSquared = 2.0,
            ),
        )

        execution.start(0)
        val afterOneSecond = execution.advance(1_000)

        assertEquals(2.0, afterOneSecond.speedMetersPerSecond, 0.0001)
        assertEquals(1.0, afterOneSecond.progress.travelledMeters, 0.02)
    }

    @Test
    fun `loop and reverse continue through endpoint with explicit direction`() {
        val route = RoutePolyline(listOf(origin, destination))
        val speed = MovementProfile.Custom(180.0)
        val afterFirstArrivalMillis = (route.totalDistanceMeters / speed.metersPerSecond * 1_000.0).toLong() + 1_000L

        val loop = RouteExecution(route, speed, executionMode = RouteExecutionMode.Loop)
        loop.start(0)
        val loopSnapshot = loop.advance(afterFirstArrivalMillis)
        assertEquals(RouteExecutionState.RUNNING, loopSnapshot.state)
        assertEquals(RouteDirection.Forward, loopSnapshot.progress.direction)
        assertEquals(1L, loopSnapshot.progress.completedLegs)

        val reverse = RouteExecution(route, speed, executionMode = RouteExecutionMode.Reverse)
        reverse.start(0)
        val reverseSnapshot = reverse.advance(afterFirstArrivalMillis)
        assertEquals(RouteExecutionState.RUNNING, reverseSnapshot.state)
        assertEquals(RouteDirection.Reverse, reverseSnapshot.progress.direction)
        assertEquals(1L, reverseSnapshot.progress.completedLegs)
        assertTrue(reverseSnapshot.position.distanceMeters < route.totalDistanceMeters)
    }

    @Test
    fun `stop at end reaches terminal state and drift is repeatable bounded`() {
        val route = RoutePolyline(listOf(origin, middle))
        val execution = RouteExecution(route)
        execution.start(0)
        val reachedEnd = execution.advance(60_000)
        assertEquals(RouteExecutionState.REACHED_END, reachedEnd.state)
        assertEquals(route.totalDistanceMeters, reachedEnd.progress.travelledMeters, 0.0)

        val drift = GpsDriftConfiguration(maximumHorizontalMeters = 12.0, seed = 42L)
        val first = drift.apply(origin, sampleIndex = 7)
        val repeated = drift.apply(origin, sampleIndex = 7)
        val next = drift.apply(origin, sampleIndex = 8)
        assertEquals(first, repeated)
        assertNotEquals(first, next)
        assertTrue(GeoMath.distanceMeters(origin, first) <= 12.0001)
    }
}
