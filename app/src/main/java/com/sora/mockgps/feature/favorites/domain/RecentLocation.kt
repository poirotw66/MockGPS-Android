package com.sora.mockgps.feature.favorites.domain

data class RecentLocation(
    val id: Long,
    val latitude: Double,
    val longitude: Double,
    val usedAt: Long,
)
