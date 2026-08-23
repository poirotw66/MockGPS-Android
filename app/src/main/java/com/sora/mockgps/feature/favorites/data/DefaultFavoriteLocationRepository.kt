package com.sora.mockgps.feature.favorites.data

import com.sora.mockgps.feature.favorites.domain.FavoriteLocation
import com.sora.mockgps.feature.favorites.domain.FavoriteLocationRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

fun interface FavoriteLocationClock {
    fun currentTimeMillis(): Long
}

class DefaultFavoriteLocationRepository(
    private val dao: FavoriteLocationDao,
    private val clock: FavoriteLocationClock = FavoriteLocationClock(System::currentTimeMillis),
) : FavoriteLocationRepository {
    override val favorites: Flow<List<FavoriteLocation>> = dao.observeAll().map { entities ->
        entities.map(FavoriteLocationEntity::toDomain)
    }

    override suspend fun save(name: String, latitude: Double, longitude: Double): FavoriteLocation {
        val coordinate = FavoriteCoordinate(latitude, longitude)
        val now = clock.currentTimeMillis()
        val id = dao.save(
            FavoriteLocationEntity(
                name = name.validatedName(),
                latitude = coordinate.latitude,
                longitude = coordinate.longitude,
                normalizedLatitude = coordinate.normalizedLatitude,
                normalizedLongitude = coordinate.normalizedLongitude,
                createdAt = now,
                updatedAt = now,
            ),
        )
        return requireNotNull(dao.getById(id)) { "Saved favorite could not be read back." }.toDomain()
    }

    override suspend fun get(id: Long): FavoriteLocation? = dao.getById(id)?.toDomain()

    override suspend fun rename(id: Long, name: String): Boolean =
        dao.updateName(id, name.validatedName(), clock.currentTimeMillis()) > 0

    override suspend fun delete(id: Long): Boolean = dao.deleteById(id) > 0

    private fun String.validatedName(): String {
        val normalized = trim()
        require(normalized.isNotEmpty()) { "Favorite name cannot be blank." }
        require(normalized.length <= MAX_NAME_LENGTH) {
            "Favorite name cannot exceed $MAX_NAME_LENGTH characters."
        }
        return normalized
    }

    private companion object {
        const val MAX_NAME_LENGTH = 100
    }
}
