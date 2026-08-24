package com.sora.mockgps.feature.routes.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "saved_routes",
    indices = [Index(value = ["updatedAt"])],
)
data class SavedRouteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Compact JSON coordinate array, decoded only after strict validation. */
    val geometry: String,
    val distanceMeters: Double,
    val createdAt: Long,
    val updatedAt: Long,
    val reversedFromRouteId: Long? = null,
)

@Entity(
    tableName = "recent_routes",
    indices = [Index(value = ["usedAt"]), Index(value = ["savedRouteId"])],
)
data class RecentRouteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val geometry: String,
    val distanceMeters: Double,
    val usedAt: Long,
    val savedRouteId: Long? = null,
)

data class SavedRouteSummaryEntity(
    val id: Long,
    val name: String,
    val distanceMeters: Double,
    val updatedAt: Long,
)

data class RecentRouteSummaryEntity(
    val id: Long,
    val name: String,
    val distanceMeters: Double,
    val usedAt: Long,
)
