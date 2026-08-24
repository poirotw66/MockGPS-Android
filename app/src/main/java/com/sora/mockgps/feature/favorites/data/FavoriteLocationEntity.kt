package com.sora.mockgps.feature.favorites.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.sora.mockgps.feature.favorites.domain.FavoriteLocation

@Entity(
    tableName = "favorite_locations",
    indices = [
        Index(
            value = ["normalizedLatitude", "normalizedLongitude"],
            unique = true,
        ),
    ],
)
data class FavoriteLocationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val normalizedLatitude: Long,
    val normalizedLongitude: Long,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "recent_locations",
    indices = [
        Index(value = ["normalizedLatitude", "normalizedLongitude"], unique = true),
        Index(value = ["usedAt"]),
    ],
)
data class RecentLocationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val latitude: Double,
    val longitude: Double,
    val normalizedLatitude: Long,
    val normalizedLongitude: Long,
    val usedAt: Long,
)

internal fun FavoriteLocationEntity.toDomain() = FavoriteLocation(
    id = id,
    name = name,
    latitude = latitude,
    longitude = longitude,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun RecentLocationEntity.toDomain() = com.sora.mockgps.feature.favorites.domain.RecentLocation(
    id = id,
    latitude = latitude,
    longitude = longitude,
    usedAt = usedAt,
)
