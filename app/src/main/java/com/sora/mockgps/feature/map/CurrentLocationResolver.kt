package com.sora.mockgps.feature.map

import android.annotation.SuppressLint
import android.location.Location
import android.location.LocationManager
import com.sora.mockgps.core.model.Coordinate

internal fun selectBestKnownLocation(locations: Iterable<Location>): Location? =
    locations.maxByOrNull { it.time }

internal fun Location.isFresh(nowMillis: Long = System.currentTimeMillis(), maxAgeMillis: Long): Boolean =
    nowMillis - time <= maxAgeMillis

internal fun Location.toCoordinate(): Coordinate = Coordinate(latitude, longitude)

@SuppressLint("MissingPermission")
internal fun LocationManager.collectLastKnownLocations(): List<Location> =
    listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        .mapNotNull { provider -> runCatching { getLastKnownLocation(provider) }.getOrNull() }

internal const val CURRENT_LOCATION_FRESH_MAX_AGE_MILLIS = 60_000L
internal const val CURRENT_LOCATION_REQUEST_TIMEOUT_MILLIS = 10_000L
