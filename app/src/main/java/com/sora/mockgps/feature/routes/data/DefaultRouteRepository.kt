package com.sora.mockgps.feature.routes.data

import com.sora.mockgps.core.model.Coordinate
import com.sora.mockgps.feature.routes.domain.RecentRoute
import com.sora.mockgps.feature.routes.domain.RouteBackup
import com.sora.mockgps.feature.routes.domain.RouteRepository
import com.sora.mockgps.feature.routes.domain.RouteRestoreResult
import com.sora.mockgps.feature.routes.domain.SavedRoute
import com.sora.mockgps.feature.routes.domain.SavedRouteSummary
import com.sora.mockgps.feature.routes.domain.RecentRouteSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

fun interface RouteClock {
    fun currentTimeMillis(): Long
}

class DefaultRouteRepository(
    private val dao: RouteDao,
    private val clock: RouteClock = RouteClock(System::currentTimeMillis),
) : RouteRepository {
    override val savedRoutes: Flow<List<SavedRouteSummary>> = dao.observeSavedRoutes().map { entities ->
        entities.map { SavedRouteSummary(it.id, it.name, it.distanceMeters, it.updatedAt) }
    }
    override val recentRoutes: Flow<List<RecentRouteSummary>> = dao.observeRecentRoutes().map { entities ->
        entities.map { RecentRouteSummary(it.id, it.name, it.distanceMeters, it.usedAt) }
    }

    override suspend fun save(name: String, points: List<Coordinate>): SavedRoute {
        val normalizedName = RouteDataValidator.name(name)
        val normalizedPoints = RouteDataValidator.points(points)
        val now = clock.currentTimeMillis()
        val id = dao.insertSavedRoute(
            SavedRouteEntity(
                name = normalizedName,
                geometry = RouteGeometryCodec.encode(normalizedPoints),
                distanceMeters = RouteDataValidator.distanceMeters(normalizedPoints),
                createdAt = now,
                updatedAt = now,
            ),
        )
        return requireNotNull(dao.getSavedRoute(id)) { "Saved route could not be read back." }.toDomain()
    }

    override suspend fun getSavedRoute(id: Long): SavedRoute? = dao.getSavedRoute(id)?.toDomain()

    override suspend fun rename(id: Long, name: String): Boolean =
        dao.renameSavedRoute(id, RouteDataValidator.name(name), clock.currentTimeMillis()) > 0

    override suspend fun duplicate(id: Long, name: String): SavedRoute {
        val source = requireNotNull(dao.getSavedRoute(id)) { "Saved route does not exist." }
        return save(name, RouteGeometryCodec.decode(source.geometry))
    }

    override suspend fun reverse(id: Long, name: String?): SavedRoute {
        val source = requireNotNull(dao.getSavedRoute(id)) { "Saved route does not exist." }
        val sourcePoints = RouteGeometryCodec.decode(source.geometry)
        val reversedName = name?.let(RouteDataValidator::name) ?: "${source.name.take(89)} (reverse)"
        val now = clock.currentTimeMillis()
        val routeId = dao.insertSavedRoute(
            SavedRouteEntity(
                name = RouteDataValidator.name(reversedName),
                geometry = RouteGeometryCodec.encode(sourcePoints.asReversed()),
                distanceMeters = source.distanceMeters,
                createdAt = now,
                updatedAt = now,
                reversedFromRouteId = source.id,
            ),
        )
        return requireNotNull(dao.getSavedRoute(routeId)) { "Reversed route could not be read back." }.toDomain()
    }

    override suspend fun deleteSavedRoute(id: Long): Boolean = dao.deleteSavedRoute(id) > 0

    override suspend fun recordRecent(
        name: String,
        points: List<Coordinate>,
        savedRouteId: Long?,
    ): RecentRoute {
        val normalizedName = RouteDataValidator.name(name)
        val normalizedPoints = RouteDataValidator.points(points)
        if (savedRouteId != null) requireNotNull(dao.getSavedRoute(savedRouteId)) { "Saved route does not exist." }
        val geometry = RouteGeometryCodec.encode(normalizedPoints)
        val now = clock.currentTimeMillis()
        val existing = dao.getRecentRouteByGeometry(geometry)
        val id = if (existing != null) {
            dao.refreshRecentRoute(existing.id, normalizedName, now, savedRouteId)
            existing.id
        } else dao.insertRecentRoute(
            RecentRouteEntity(
                name = normalizedName,
                geometry = geometry,
                distanceMeters = RouteDataValidator.distanceMeters(normalizedPoints),
                usedAt = now,
                savedRouteId = savedRouteId,
            ),
        )
        dao.trimRecentRoutes(MAX_RECENT_ROUTES)
        return requireNotNull(dao.getRecentRoute(id)) { "Recent route was trimmed before it could be read back." }.toDomain()
    }

    override suspend fun deleteRecentRoute(id: Long): Boolean = dao.deleteRecentRouteById(id) > 0

    override suspend fun getRecentRoute(id: Long): RecentRoute? = dao.getRecentRoute(id)?.toDomain()

    override suspend fun clearRecentRoutes() {
        dao.clearRecentRoutesInternal()
    }

    override suspend fun exportBackup(): String = RouteBackupJson.encode(
        RouteBackup(
            savedRoutes = dao.getAllSavedRoutes().map(SavedRouteEntity::toDomain),
            recentRoutes = dao.getAllRecentRoutes().map(RecentRouteEntity::toDomain),
        ),
    )

    override suspend fun restoreBackup(serialized: String, replaceExisting: Boolean): RouteRestoreResult {
        val backup = RouteBackupJson.decode(serialized)
        val counts = dao.restoreBackup(
            savedRoutes = backup.savedRoutes.map(SavedRoute::toEntity),
            recentRoutes = backup.recentRoutes.map(RecentRoute::toEntity),
            replaceExisting = replaceExisting,
        )
        return RouteRestoreResult(counts.savedRoutes, counts.recentRoutes)
    }

    private companion object {
        const val MAX_RECENT_ROUTES = 50
    }
}

internal fun SavedRouteEntity.toDomain(): SavedRoute = SavedRoute(
    id = id,
    name = RouteDataValidator.name(name),
    points = RouteGeometryCodec.decode(geometry),
    distanceMeters = distanceMeters.validDistance(),
    createdAt = createdAt.validTimestamp(),
    updatedAt = updatedAt.validTimestamp(),
    reversedFromRouteId = reversedFromRouteId,
)

internal fun RecentRouteEntity.toDomain(): RecentRoute = RecentRoute(
    id = id,
    name = RouteDataValidator.name(name),
    points = RouteGeometryCodec.decode(geometry),
    distanceMeters = distanceMeters.validDistance(),
    usedAt = usedAt.validTimestamp(),
    savedRouteId = savedRouteId,
)

private fun SavedRoute.toEntity() = SavedRouteEntity(
    id = id,
    name = RouteDataValidator.name(name),
    geometry = RouteGeometryCodec.encode(points),
    distanceMeters = distanceMeters.validDistance(),
    createdAt = createdAt.validTimestamp(),
    updatedAt = updatedAt.validTimestamp(),
    reversedFromRouteId = reversedFromRouteId,
)

private fun RecentRoute.toEntity() = RecentRouteEntity(
    id = id,
    name = RouteDataValidator.name(name),
    geometry = RouteGeometryCodec.encode(points),
    distanceMeters = distanceMeters.validDistance(),
    usedAt = usedAt.validTimestamp(),
    savedRouteId = savedRouteId,
)

private fun Double.validDistance(): Double = also {
    require(it.isFinite() && it in 0.01..RouteDataValidator.MAX_DISTANCE_METERS) { "Route distance is invalid." }
}
private fun Long.validTimestamp(): Long = also { require(it >= 0) { "Route timestamp is invalid." } }
