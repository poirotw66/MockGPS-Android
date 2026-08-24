package com.sora.mockgps.feature.map

import com.sora.mockgps.core.model.Coordinate
import com.sora.mockgps.route.GeoMath
import com.sora.mockgps.route.RoutePolyline
import com.sora.mockgps.route.RouteTransportMode
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

internal enum class JourneyRegion(val center: Coordinate) {
    Taiwan(Coordinate(25.0375, 121.5637)),
    Japan(Coordinate(35.6896, 139.7006)),
    SouthKorea(Coordinate(37.5665, 126.9780)),
}

internal enum class JourneyDuration(val minutes: Int) {
    Short(30), Medium(60), Long(120),
}

internal data class AutoJourneyOptions(
    val region: JourneyRegion = JourneyRegion.Taiwan,
    val duration: JourneyDuration = JourneyDuration.Medium,
    val transportMode: RouteTransportMode = RouteTransportMode.Bicycle,
)

internal enum class RouteShape { Heart, Star, Circle }

internal object JourneyPlanner {
    fun automaticControlPoints(options: AutoJourneyOptions): List<Coordinate> {
        val targetDistanceMeters = (
            speedKilometersPerHour(options.transportMode) * 1_000.0 * options.duration.minutes / 60.0
        ).coerceIn(MINIMUM_JOURNEY_METERS, MAXIMUM_JOURNEY_METERS)
        val radiusMeters = targetDistanceMeters / (2.0 * PI)
        val pointCount = when (options.duration) {
            JourneyDuration.Short -> 4
            JourneyDuration.Medium -> 6
            JourneyDuration.Long -> 8
        }
        return (0 until pointCount).map { index ->
            GeoMath.destination(
                origin = options.region.center,
                bearingDegrees = index * 360.0 / pointCount,
                distanceMeters = radiusMeters,
            )
        } + GeoMath.destination(options.region.center, 0.0, radiusMeters)
    }

    fun shapePoints(center: Coordinate, shape: RouteShape, radiusMeters: Double = DEFAULT_SHAPE_RADIUS_METERS): List<Coordinate> {
        require(radiusMeters in 100.0..10_000.0) { "Shape radius is out of range." }
        return when (shape) {
            RouteShape.Heart -> heartOffsets().map { (east, north) -> center.offset(east, north, radiusMeters) }
            RouteShape.Star -> starOffsets().map { (east, north) -> center.offset(east, north, radiusMeters) }
            RouteShape.Circle -> circleOffsets().map { (east, north) -> center.offset(east, north, radiusMeters) }
        }
    }

    fun shapeDistanceMeters(center: Coordinate, shape: RouteShape): Double =
        RoutePolyline(shapePoints(center, shape)).totalDistanceMeters

    private fun heartOffsets(): List<Pair<Double, Double>> = (0..24).map { index ->
        val angle = index * 2.0 * PI / 24.0
        val east = 16.0 * sin(angle) * sin(angle) * sin(angle) / 18.0
        val north = (13.0 * cos(angle) - 5.0 * cos(2.0 * angle) -
            2.0 * cos(3.0 * angle) - cos(4.0 * angle)) / 18.0
        east to north
    }

    private fun starOffsets(): List<Pair<Double, Double>> = (0..10).map { index ->
        val pointIndex = index % 10
        val angle = -PI / 2.0 + pointIndex * PI / 5.0
        val radius = if (pointIndex % 2 == 0) 1.0 else 0.42
        cos(angle) * radius to -sin(angle) * radius
    }

    private fun circleOffsets(): List<Pair<Double, Double>> = (0..24).map { index ->
        val angle = index * 2.0 * PI / 24.0
        sin(angle) to cos(angle)
    }

    private fun Coordinate.offset(eastScale: Double, northScale: Double, radiusMeters: Double): Coordinate {
        val eastMeters = eastScale * radiusMeters
        val northMeters = northScale * radiusMeters
        return GeoMath.destination(
            origin = this,
            bearingDegrees = Math.toDegrees(atan2(eastMeters, northMeters)),
            distanceMeters = hypot(eastMeters, northMeters),
        )
    }

    private fun speedKilometersPerHour(mode: RouteTransportMode): Double = when (mode) {
        RouteTransportMode.Walk -> 5.0
        RouteTransportMode.Bicycle -> 18.0
        RouteTransportMode.Drive -> 50.0
    }

    private const val MINIMUM_JOURNEY_METERS = 1_500.0
    private const val MAXIMUM_JOURNEY_METERS = 50_000.0
    private const val DEFAULT_SHAPE_RADIUS_METERS = 1_000.0
}