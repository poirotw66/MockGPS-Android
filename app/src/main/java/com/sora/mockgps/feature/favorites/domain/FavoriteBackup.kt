package com.sora.mockgps.feature.favorites.domain

data class FavoriteBackup(
    val favorites: List<FavoriteLocation>,
    val recentLocations: List<RecentLocation>,
)

data class FavoriteRestoreResult(
    val favoritesRestored: Int,
    val recentLocationsRestored: Int,
)
