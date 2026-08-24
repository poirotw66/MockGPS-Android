package com.sora.mockgps.route

import com.sora.mockgps.core.model.Coordinate
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** An immutable, ordered route geometry independent of any map or routing SDK. */
class RoutePolyline(points: List<Coordinate>) {
    val points: List<Coordinate> = points.toList()
    private val cumulativeDistancesMeters: DoubleArray
    val totalDistanceMeters: Double

    init {
        require(this.points.size >= MINIMUM_POINT_COUNT) { "A route needs at least two points" }
        require(this.points.all { it.isValid() }) { "Route contains an invalid coordinate" }

        cumulativeDistancesMeters = DoubleArray(this.points.size)
        for (index in 1 until this.points.size) {
            cumulativeDistancesMeters[index] = cumulativeDistancesMeters[index - 1] +
                GeoMath.distanceMeters(this.points[index - 1], this.points[index])
        }
        totalDistanceMeters = cumulativeDistancesMeters.last()
        require(totalDistanceMeters > 0.0) { "A route must contain at least one non-zero segment" }
    }

    /** Returns the route position at [distanceMeters], clamped to the start and end. */
    fun positionAt(distanceMeters: Double): RoutePosition {
        val clampedDistance = distanceMeters.coerceIn(0.0, totalDistanceMeters)
        val segmentIndex = segmentIndexFor(clampedDistance)
        val segmentStart = cumulativeDistancesMeters[segmentIndex]
        val segmentEnd = cumulativeDistancesMeters[segmentIndex + 1]
        val segmentLength = segmentEnd - segmentStart
        val fraction = if (segmentLength == 0.0) 0.0 else (clampedDistance - segmentStart) / segmentLength
        val start = points[segmentIndex]
        val end = points[segmentIndex + 1]

        return RoutePosition(
            coordinate = GeoMath.interpolate(start, end, fraction),
            distanceMeters = clampedDistance,
            bearingDegrees = GeoMath.initialBearingDegrees(start, end),
        )
    }

    private fun segmentIndexFor(distanceMeters: Double): Int {
        if (distanceMeters >= totalDistanceMeters) return points.lastIndex - 1
        var low = 0
        var high = cumulativeDistancesMeters.lastIndex - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (cumulativeDistancesMeters[middle + 1] <= distanceMeters) low = middle + 1 else high = middle - 1
        }
        return min(low, points.lastIndex - 1)
    }

    private companion object {
        const val MINIMUM_POINT_COUNT = 2
    }
}

data class RoutePosition(
    val coordinate: Coordinate,
    val distanceMeters: Double,
    val bearingDegrees: Float,
)

internal object GeoMath {
    private const val EARTH_RADIUS_METERS = 6_371_008.8

    fun distanceMeters(first: Coordinate, second: Coordinate): Double {
        val latitudeDelta = radians(second.latitude - first.latitude)
        val longitudeDelta = radians(normalizeLongitude(second.longitude - first.longitude))
        val firstLatitude = radians(first.latitude)
        val secondLatitude = radians(second.latitude)
        val haversine = sin(latitudeDelta / 2).let { it * it } +
            cos(firstLatitude) * cos(secondLatitude) * sin(longitudeDelta / 2).let { it * it }
        return EARTH_RADIUS_METERS * 2 * asin(sqrt(haversine.coerceIn(0.0, 1.0)))
    }

    fun interpolate(first: Coordinate, second: Coordinate, fraction: Double): Coordinate {
        val progress = fraction.coerceIn(0.0, 1.0)
        if (progress == 0.0) return first
        if (progress == 1.0) return second
        val angularDistance = distanceMeters(first, second) / EARTH_RADIUS_METERS
        if (angularDistance < 1e-12) return first

        val firstLatitude = radians(first.latitude)
        val firstLongitude = radians(first.longitude)
        val secondLatitude = radians(second.latitude)
        val secondLongitude = radians(second.longitude)
        val scaleStart = sin((1 - progress) * angularDistance) / sin(angularDistance)
        val scaleEnd = sin(progress * angularDistance) / sin(angularDistance)
        val x = scaleStart * cos(firstLatitude) * cos(firstLongitude) + scaleEnd * cos(secondLatitude) * cos(secondLongitude)
        val y = scaleStart * cos(firstLatitude) * sin(firstLongitude) + scaleEnd * cos(secondLatitude) * sin(secondLongitude)
        val z = scaleStart * sin(firstLatitude) + scaleEnd * sin(secondLatitude)
        return Coordinate(
            latitude = degrees(atan2(z, sqrt(x * x + y * y))),
            longitude = normalizeLongitude(degrees(atan2(y, x))),
        )
    }

    fun initialBearingDegrees(first: Coordinate, second: Coordinate): Float {
        val firstLatitude = radians(first.latitude)
        val secondLatitude = radians(second.latitude)
        val longitudeDelta = radians(normalizeLongitude(second.longitude - first.longitude))
        val bearing = degrees(
            atan2(
                sin(longitudeDelta) * cos(secondLatitude),
                cos(firstLatitude) * sin(secondLatitude) - sin(firstLatitude) * cos(secondLatitude) * cos(longitudeDelta),
            ),
        )
        return ((bearing + 360.0) % 360.0).toFloat()
    }

    fun destination(origin: Coordinate, bearingDegrees: Double, distanceMeters: Double): Coordinate {
        if (distanceMeters == 0.0) return origin
        val angularDistance = distanceMeters / EARTH_RADIUS_METERS
        val bearing = radians(bearingDegrees)
        val latitude = radians(origin.latitude)
        val longitude = radians(origin.longitude)
        val destinationLatitude = asin(
            sin(latitude) * cos(angularDistance) + cos(latitude) * sin(angularDistance) * cos(bearing),
        )
        val destinationLongitude = longitude + atan2(
            sin(bearing) * sin(angularDistance) * cos(latitude),
            cos(angularDistance) - sin(latitude) * sin(destinationLatitude),
        )
        return Coordinate(degrees(destinationLatitude), normalizeLongitude(degrees(destinationLongitude)))
    }

    private fun radians(degrees: Double): Double = degrees * PI / 180.0
    private fun degrees(radians: Double): Double = radians * 180.0 / PI
    private fun normalizeLongitude(longitude: Double): Double = ((longitude + 540.0) % 360.0) - 180.0
}

private fun Coordinate.isValid(): Boolean =
    latitude.isFinite() && latitude in -90.0..90.0 && longitude.isFinite() && longitude in -180.0..180.0
