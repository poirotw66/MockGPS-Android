package com.sora.mockgps.core.model

/** A geographic position which deliberately has no dependency on a map SDK. */
data class Coordinate(
    val latitude: Double,
    val longitude: Double,
)
