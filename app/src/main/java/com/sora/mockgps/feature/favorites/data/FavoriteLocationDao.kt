package com.sora.mockgps.feature.favorites.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteLocationDao {
    @Query("SELECT * FROM favorite_locations ORDER BY updatedAt DESC, id DESC")
    fun observeAll(): Flow<List<FavoriteLocationEntity>>

    @Query("SELECT * FROM favorite_locations WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): FavoriteLocationEntity?

    @Query(
        "SELECT * FROM favorite_locations " +
            "WHERE normalizedLatitude = :normalizedLatitude " +
            "AND normalizedLongitude = :normalizedLongitude LIMIT 1",
    )
    suspend fun getByCoordinate(
        normalizedLatitude: Long,
        normalizedLongitude: Long,
    ): FavoriteLocationEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: FavoriteLocationEntity): Long

    @Query("UPDATE favorite_locations SET name = :name, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateName(id: Long, name: String, updatedAt: Long): Int

    @Query("DELETE FROM favorite_locations WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Transaction
    suspend fun save(entity: FavoriteLocationEntity): Long {
        val existing = getByCoordinate(entity.normalizedLatitude, entity.normalizedLongitude)
        if (existing != null) {
            updateName(existing.id, entity.name, entity.updatedAt)
            return existing.id
        }

        val insertedId = insert(entity)
        if (insertedId != -1L) return insertedId

        // A concurrent writer may have inserted the same normalized coordinate first.
        val concurrent = requireNotNull(
            getByCoordinate(entity.normalizedLatitude, entity.normalizedLongitude),
        ) { "Favorite insert was ignored but no matching row was found." }
        updateName(concurrent.id, entity.name, entity.updatedAt)
        return concurrent.id
    }
}
