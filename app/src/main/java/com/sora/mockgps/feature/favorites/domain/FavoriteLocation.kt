package com.sora.mockgps.feature.favorites.domain

/** A user-labelled coordinate retained locally for quick reuse. */
data class FavoriteLocation(
    val id: Long,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val createdAt: Long,
    val updatedAt: Long,
)
