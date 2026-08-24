package com.sora.mockgps.feature.routes.domain

import com.sora.mockgps.core.model.Coordinate

/** A named route retained on this device. */
data class SavedRoute(
    val id: Long,
    val name: String,
    val points: List<Coordinate>,
    val distanceMeters: Double,
    val createdAt: Long,
    val updatedAt: Long,
    /** The route this was reversed from, when it was created with [RouteRepository.reverse]. */
    val reversedFromRouteId: Long? = null,
)

/** A bounded, newest-first history of routes used by the simulator. */
data class RecentRoute(
    val id: Long,
    val name: String,
    val points: List<Coordinate>,
    val distanceMeters: Double,
    val usedAt: Long,
    val savedRouteId: Long? = null,
)

/** Lightweight library rows; geometry is decoded only after the user loads a route. */
data class SavedRouteSummary(
    val id: Long,
    val name: String,
    val distanceMeters: Double,
    val updatedAt: Long,
)

data class RecentRouteSummary(
    val id: Long,
    val name: String,
    val distanceMeters: Double,
    val usedAt: Long,
)

data class ImportedRoute(
    val name: String,
    val points: List<Coordinate>,
)

data class RouteBackup(
    val savedRoutes: List<SavedRoute>,
    val recentRoutes: List<RecentRoute>,
)

data class RouteRestoreResult(
    val savedRoutesRestored: Int,
    val recentRoutesRestored: Int,
)
