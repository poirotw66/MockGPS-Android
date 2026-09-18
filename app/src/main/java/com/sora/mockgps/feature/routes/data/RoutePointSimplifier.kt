package com.sora.mockgps.feature.routes.data

import com.sora.mockgps.core.model.Coordinate

/** Evenly samples a dense polyline down to [maxPoints], always keeping the first and last vertices. */
internal object RoutePointSimplifier {
    fun simplifyToMaxPoints(points: List<Coordinate>, maxPoints: Int): List<Coordinate> {
        require(maxPoints >= 2) { "maxPoints must be at least 2." }
        if (points.size <= maxPoints) return points.toList()
        if (maxPoints == 2) return listOf(points.first(), points.last())
        val lastIndex = points.lastIndex
        val sampled = ArrayList<Coordinate>(maxPoints)
        for (slot in 0 until maxPoints) {
            val index = ((slot.toLong() * lastIndex) / (maxPoints - 1)).toInt()
            val point = points[index]
            if (sampled.isEmpty() || sampled.last() != point) {
                sampled.add(point)
            }
        }
        if (sampled.last() != points.last()) {
            if (sampled.size < maxPoints) {
                sampled.add(points.last())
            } else {
                sampled[sampled.lastIndex] = points.last()
            }
        }
        if (sampled.first() != points.first()) {
            sampled[0] = points.first()
        }
        return sampled
    }
}
