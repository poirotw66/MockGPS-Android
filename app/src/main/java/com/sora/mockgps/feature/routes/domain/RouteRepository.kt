package com.sora.mockgps.feature.routes.domain

import com.sora.mockgps.core.model.Coordinate
import kotlinx.coroutines.flow.Flow

interface RouteRepository {
    val savedRoutes: Flow<List<SavedRouteSummary>>
    val recentRoutes: Flow<List<RecentRouteSummary>>

    suspend fun save(name: String, points: List<Coordinate>): SavedRoute
    suspend fun getSavedRoute(id: Long): SavedRoute?
    suspend fun rename(id: Long, name: String): Boolean
    suspend fun duplicate(id: Long, name: String): SavedRoute
    suspend fun reverse(id: Long, name: String? = null): SavedRoute
    suspend fun deleteSavedRoute(id: Long): Boolean

    /** Adds a route use to local history, retaining only the newest 50 entries. */
    suspend fun recordRecent(name: String, points: List<Coordinate>, savedRouteId: Long? = null): RecentRoute
    suspend fun getRecentRoute(id: Long): RecentRoute?
    suspend fun deleteRecentRoute(id: Long): Boolean
    suspend fun clearRecentRoutes()

    suspend fun exportBackup(): String
    suspend fun restoreBackup(serialized: String, replaceExisting: Boolean = false): RouteRestoreResult
}
