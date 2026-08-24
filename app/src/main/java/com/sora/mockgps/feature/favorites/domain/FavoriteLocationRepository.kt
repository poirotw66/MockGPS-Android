package com.sora.mockgps.feature.favorites.domain

import kotlinx.coroutines.flow.Flow

interface FavoriteLocationRepository {
    /** Favorites ordered by most recently changed first. */
    val favorites: Flow<List<FavoriteLocation>>
    val recentLocations: Flow<List<RecentLocation>>

    /**
     * Saves a favorite. A coordinate is rounded to six decimal places for de-duplication;
     * saving a matching coordinate refreshes its name and timestamp instead of adding a row.
     */
    suspend fun save(name: String, latitude: Double, longitude: Double): FavoriteLocation

    suspend fun get(id: Long): FavoriteLocation?

    /** Returns false when the favorite has already been removed. */
    suspend fun rename(id: Long, name: String): Boolean

    /** Returns false when the favorite has already been removed. */
    suspend fun delete(id: Long): Boolean
    suspend fun clearAll()
    suspend fun recordRecent(latitude: Double, longitude: Double): RecentLocation
    suspend fun clearRecentLocations()
}
