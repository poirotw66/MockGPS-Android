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
import kotlin.random.Random

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

internal enum class RouteShape { Heart, Star, Circle, Cat, Dog, Rabbit, Fish, Butterfly, ChristmasTree }

internal data class GeneratedJourney(
    val shape: RouteShape,
    val points: List<Coordinate>,
)

internal object JourneyPlanner {
    fun automaticJourney(
        options: AutoJourneyOptions,
        random: Random = Random.Default,
    ): GeneratedJourney {
        val targetDistanceMeters = (
            speedKilometersPerHour(options.transportMode) * 1_000.0 * options.duration.minutes / 60.0
        ).coerceIn(MINIMUM_JOURNEY_METERS, MAXIMUM_JOURNEY_METERS)
        val shape = RouteShape.entries[random.nextInt(RouteShape.entries.size)]
        val baselineDistance = shapeDistanceMeters(options.region.center, shape)
        val radiusMeters = (DEFAULT_SHAPE_RADIUS_METERS * targetDistanceMeters / baselineDistance)
            .coerceIn(MINIMUM_SHAPE_RADIUS_METERS, MAXIMUM_SHAPE_RADIUS_METERS)
        return GeneratedJourney(shape, shapePoints(options.region.center, shape, radiusMeters))
    }

    fun shapePoints(center: Coordinate, shape: RouteShape, radiusMeters: Double = DEFAULT_SHAPE_RADIUS_METERS): List<Coordinate> {
        require(radiusMeters in MINIMUM_SHAPE_RADIUS_METERS..MAXIMUM_SHAPE_RADIUS_METERS) {
            "Shape radius is out of range."
        }
        return when (shape) {
            RouteShape.Heart -> heartOffsets().map { (east, north) -> center.offset(east, north, radiusMeters) }
            RouteShape.Star -> starOffsets().map { (east, north) -> center.offset(east, north, radiusMeters) }
            RouteShape.Circle -> circleOffsets().map { (east, north) -> center.offset(east, north, radiusMeters) }
            RouteShape.Cat -> catOffsets().map { (east, north) -> center.offset(east, north, radiusMeters) }
            RouteShape.Dog -> dogOffsets().map { (east, north) -> center.offset(east, north, radiusMeters) }
            RouteShape.Rabbit -> rabbitOffsets().map { (east, north) -> center.offset(east, north, radiusMeters) }
            RouteShape.Fish -> fishOffsets().map { (east, north) -> center.offset(east, north, radiusMeters) }
            RouteShape.Butterfly -> butterflyOffsets().map { (east, north) -> center.offset(east, north, radiusMeters) }
            RouteShape.ChristmasTree -> christmasTreeOffsets().map { (east, north) -> center.offset(east, north, radiusMeters) }
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

    private fun catOffsets(): List<Pair<Double, Double>> = listOf(
        -0.52 to -0.82, -0.78 to -0.45, -0.84 to 0.18, -0.65 to 0.52,
        -0.76 to 1.0, -0.26 to 0.74, 0.26 to 0.74, 0.76 to 1.0,
        0.65 to 0.52, 0.84 to 0.18, 0.78 to -0.45, 0.52 to -0.82,
        -0.52 to -0.82,
    )

    private fun dogOffsets(): List<Pair<Double, Double>> = listOf(
        -0.45 to -0.75, -0.72 to -0.42, -0.68 to 0.25, -1.0 to 0.1,
        -0.9 to 0.88, -0.48 to 0.62, -0.3 to 0.82, 0.0 to 0.92,
        0.3 to 0.82, 0.48 to 0.62, 0.9 to 0.88, 1.0 to 0.1,
        0.68 to 0.25, 0.72 to -0.42, 0.45 to -0.75, 0.0 to -0.95,
        -0.45 to -0.75,
    )

    private fun rabbitOffsets(): List<Pair<Double, Double>> = listOf(
        -0.52 to -0.82, -0.76 to -0.42, -0.72 to 0.12, -0.48 to 0.42,
        -0.62 to 1.0, -0.38 to 0.98, -0.14 to 0.48, 0.14 to 0.48,
        0.38 to 0.98, 0.62 to 1.0, 0.48 to 0.42, 0.72 to 0.12,
        0.76 to -0.42, 0.52 to -0.82, -0.52 to -0.82,
    )

    private fun fishOffsets(): List<Pair<Double, Double>> = listOf(
        1.0 to 0.0, 0.52 to 0.5, -0.18 to 0.62, -0.62 to 0.34,
        -1.0 to 0.72, -0.82 to 0.0, -1.0 to -0.72, -0.62 to -0.34,
        -0.18 to -0.62, 0.52 to -0.5, 1.0 to 0.0,
    )

    private fun butterflyOffsets(): List<Pair<Double, Double>> = listOf(
        0.0 to 0.0, -0.24 to 0.2, -0.9 to 0.82, -1.0 to 0.2,
        -0.5 to 0.0, -0.9 to -0.78, -0.2 to -0.4, 0.0 to 0.0,
        0.2 to -0.4, 0.9 to -0.78, 0.5 to 0.0, 1.0 to 0.2,
        0.9 to 0.82, 0.24 to 0.2, 0.0 to 0.0,
    )

    private fun christmasTreeOffsets(): List<Pair<Double, Double>> = listOf(
        0.0 to 1.0, -0.35 to 0.5, -0.15 to 0.5, -0.6 to 0.0,
        -0.35 to 0.0, -0.85 to -0.55, -0.18 to -0.55, -0.18 to -0.9,
        0.18 to -0.9, 0.18 to -0.55, 0.85 to -0.55, 0.35 to 0.0,
        0.6 to 0.0, 0.15 to 0.5, 0.35 to 0.5, 0.0 to 1.0,
    )

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
    private const val MAXIMUM_JOURNEY_METERS = 100_000.0
    private const val MINIMUM_SHAPE_RADIUS_METERS = 100.0
    private const val MAXIMUM_SHAPE_RADIUS_METERS = 30_000.0
    private const val DEFAULT_SHAPE_RADIUS_METERS = 1_000.0
}