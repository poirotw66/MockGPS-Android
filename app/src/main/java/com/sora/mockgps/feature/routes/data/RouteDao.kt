package com.sora.mockgps.feature.routes.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface RouteDao {
    @Query("SELECT id, name, distanceMeters, updatedAt FROM saved_routes ORDER BY updatedAt DESC, id DESC")
    fun observeSavedRoutes(): Flow<List<SavedRouteSummaryEntity>>

    @Query("SELECT id, name, distanceMeters, usedAt FROM recent_routes ORDER BY usedAt DESC, id DESC")
    fun observeRecentRoutes(): Flow<List<RecentRouteSummaryEntity>>

    @Query("SELECT * FROM saved_routes WHERE id = :id LIMIT 1")
    suspend fun getSavedRoute(id: Long): SavedRouteEntity?

    @Query("SELECT * FROM saved_routes ORDER BY updatedAt DESC, id DESC")
    suspend fun getAllSavedRoutes(): List<SavedRouteEntity>

    @Query("SELECT * FROM recent_routes ORDER BY usedAt DESC, id DESC")
    suspend fun getAllRecentRoutes(): List<RecentRouteEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSavedRoute(entity: SavedRouteEntity): Long

    @Query("UPDATE saved_routes SET name = :name, updatedAt = :updatedAt WHERE id = :id")
    suspend fun renameSavedRoute(id: Long, name: String, updatedAt: Long): Int

    @Query("UPDATE saved_routes SET reversedFromRouteId = :reversedFromRouteId WHERE id = :id")
    suspend fun updateReversedFromRouteId(id: Long, reversedFromRouteId: Long?): Int

    @Query("DELETE FROM saved_routes WHERE id = :id")
    suspend fun deleteSavedRouteById(id: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRecentRoute(entity: RecentRouteEntity): Long

    @Query("SELECT * FROM recent_routes WHERE id = :id LIMIT 1")
    suspend fun getRecentRoute(id: Long): RecentRouteEntity?

    @Query("SELECT * FROM recent_routes WHERE geometry = :geometry ORDER BY usedAt DESC, id DESC LIMIT 1")
    suspend fun getRecentRouteByGeometry(geometry: String): RecentRouteEntity?

    @Query("UPDATE recent_routes SET name = :name, usedAt = :usedAt, savedRouteId = :savedRouteId WHERE id = :id")
    suspend fun refreshRecentRoute(id: Long, name: String, usedAt: Long, savedRouteId: Long?): Int

    @Query("DELETE FROM recent_routes WHERE id = :id")
    suspend fun deleteRecentRouteById(id: Long): Int

    @Query("DELETE FROM recent_routes")
    suspend fun clearRecentRoutesInternal()

    @Query("UPDATE recent_routes SET savedRouteId = NULL WHERE savedRouteId = :savedRouteId")
    suspend fun detachRecentRoutes(savedRouteId: Long): Int

    @Query(
        "DELETE FROM recent_routes WHERE id NOT IN " +
            "(SELECT id FROM recent_routes ORDER BY usedAt DESC, id DESC LIMIT :maxEntries)",
    )
    suspend fun trimRecentRoutes(maxEntries: Int): Int

    @Query("DELETE FROM saved_routes")
    suspend fun clearSavedRoutesInternal()

    @Transaction
    suspend fun deleteSavedRoute(id: Long): Int {
        detachRecentRoutes(id)
        return deleteSavedRouteById(id)
    }

    @Transaction
    suspend fun replaceAllRoutes() {
        clearRecentRoutesInternal()
        clearSavedRoutesInternal()
    }

    /** Restores a fully validated backup atomically and remaps exported IDs to local row IDs. */
    @Transaction
    suspend fun restoreBackup(
        savedRoutes: List<SavedRouteEntity>,
        recentRoutes: List<RecentRouteEntity>,
        replaceExisting: Boolean,
    ): RestoreCounts {
        if (replaceExisting) replaceAllRoutes()
        val restoredIds = mutableMapOf<Long, Long>()
        savedRoutes.forEach { exported ->
            restoredIds[exported.id] = insertSavedRoute(
                exported.copy(id = 0, reversedFromRouteId = null),
            )
        }
        savedRoutes.forEach { exported ->
            val localId = requireNotNull(restoredIds[exported.id])
            updateReversedFromRouteId(localId, exported.reversedFromRouteId?.let(restoredIds::get))
        }
        recentRoutes.forEach { exported ->
            insertRecentRoute(
                exported.copy(id = 0, savedRouteId = exported.savedRouteId?.let(restoredIds::get)),
            )
        }
        trimRecentRoutes(MAX_RECENT_ROUTES)
        return RestoreCounts(savedRoutes.size, minOf(recentRoutes.size, MAX_RECENT_ROUTES))
    }

    private companion object {
        const val MAX_RECENT_ROUTES = 50
    }
}

data class RestoreCounts(
    val savedRoutes: Int,
    val recentRoutes: Int,
)
