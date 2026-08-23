package com.sora.mockgps.feature.favorites.data

import kotlin.math.round

/**
 * Coordinates are rounded to roughly 11 cm before uniqueness is checked. It prevents visually
 * identical pins from being stored twice while retaining the original coordinate for display.
 */
internal data class FavoriteCoordinate(
    val latitude: Double,
    val longitude: Double,
) {
    val normalizedLatitude: Long = normalize(latitude)
    val normalizedLongitude: Long = normalize(longitude)

    init {
        require(latitude.isFinite() && latitude in -90.0..90.0) {
            "Latitude must be a finite value between -90 and 90."
        }
        require(longitude.isFinite() && longitude in -180.0..180.0) {
            "Longitude must be a finite value between -180 and 180."
        }
    }

    private companion object {
        const val SCALE = 1_000_000.0

        fun normalize(value: Double): Long = round(value * SCALE).toLong()
    }
}
