package com.sora.mockgps.feature.map

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sora.mockgps.R
import com.sora.mockgps.core.model.Coordinate
import com.sora.mockgps.feature.favorites.data.DefaultFavoriteLocationRepository
import com.sora.mockgps.feature.favorites.data.FavoriteLocationDatabase
import com.sora.mockgps.feature.favorites.domain.FavoriteLocation
import com.sora.mockgps.feature.routes.data.DefaultRouteRepository
import com.sora.mockgps.feature.routes.data.RouteGpxInterchange
import com.sora.mockgps.feature.routes.domain.RecentRoute
import com.sora.mockgps.feature.routes.domain.SavedRoute
import com.sora.mockgps.route.FossgisBicycleRoutingRepository
import com.sora.mockgps.route.CachingRoutingRepository
import com.sora.mockgps.route.PlannedRoute
import com.sora.mockgps.route.RoutePolyline
import com.sora.mockgps.route.RoutingRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import org.maplibre.compose.camera.CameraPosition

class MapViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableUiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = mutableUiState.asStateFlow()

    private val favoriteRepository = DefaultFavoriteLocationRepository(
        FavoriteLocationDatabase.getInstance(application).favoriteLocationDao(),
    )
    private val routeRepository = DefaultRouteRepository(
        FavoriteLocationDatabase.getInstance(application).routeDao(),
    )
    val favorites: StateFlow<List<FavoriteLocation>> = favoriteRepository.favorites.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )
    val savedRoutes: StateFlow<List<SavedRoute>> = routeRepository.savedRoutes.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )
    val recentRoutes: StateFlow<List<RecentRoute>> = routeRepository.recentRoutes.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )
    private val routingRepository: RoutingRepository = CachingRoutingRepository(FossgisBicycleRoutingRepository())

    private var mapLoadTimeout: Job? = null
    private var routePlanningJob: Job? = null

    init {
        awaitMapLoad()
    }

    fun onCameraIdle(position: CameraPosition) {
        mutableUiState.update { MapStateReducer.cameraIdle(it, position) }
    }

    fun onMapLoaded() {
        mapLoadTimeout?.cancel()
        mutableUiState.update(MapStateReducer::mapLoaded)
    }

    fun onMapLoadFailed() {
        mapLoadTimeout?.cancel()
        mutableUiState.update { it.copy(loadingState = MapLoadingState.Error) }
    }

    fun retryMap() {
        mutableUiState.update(MapStateReducer::retry)
        awaitMapLoad()
    }

    fun toggleMapType() {
        mutableUiState.update(MapStateReducer::toggleMapType)
        awaitMapLoad()
    }

    fun saveFavorite(name: String, coordinate: Coordinate) {
        viewModelScope.launch {
            runCatching { favoriteRepository.save(name, coordinate.latitude, coordinate.longitude) }
                .onSuccess { favorite ->
                    mutableUiState.update { it.copy(favoriteMessage = favorite.name, routeError = null) }
                }
                .onFailure { failure ->
                    mutableUiState.update { it.copy(routeError = failure.message ?: "Unable to save favorite.") }
                }
        }
    }

    fun renameFavorite(id: Long, name: String) {
        viewModelScope.launch {
            runCatching { favoriteRepository.rename(id, name) }
                .onFailure { failure ->
                    mutableUiState.update { it.copy(routeError = failure.message ?: "Unable to rename favorite.") }
                }
        }
    }

    fun deleteFavorite(id: Long) {
        viewModelScope.launch { favoriteRepository.delete(id) }
    }

    fun consumeFavoriteMessage() {
        mutableUiState.update { it.copy(favoriteMessage = null) }
    }

    fun savePlannedRoute(name: String) {
        val route = mutableUiState.value.plannedRoute ?: return setRouteOperationError("Plan a route before saving it.")
        viewModelScope.launch {
            runCatching { routeRepository.save(name, route.points) }
                .onSuccess { saved ->
                    mutableUiState.update {
                        it.copy(
                            activeSavedRouteId = saved.id,
                            activeRouteName = saved.name,
                            routeOperationResult = RouteOperationResult("Saved route \u201c${saved.name}\u201d."),
                        )
                    }
                }
                .onFailure { failure -> setRouteOperationError(failure.message ?: "Unable to save route.") }
        }
    }

    fun loadSavedRoute(id: Long) {
        viewModelScope.launch {
            runCatching { routeRepository.getSavedRoute(id) }
                .onSuccess { route ->
                    if (route == null) setRouteOperationError("Saved route is no longer available.")
                    else loadRoutePreview(route.points, route.distanceMeters, route.name, route.id)
                }
                .onFailure { failure -> setRouteOperationError(failure.message ?: "Unable to load saved route.") }
        }
    }

    fun loadRecentRoute(id: Long) {
        viewModelScope.launch {
            runCatching { routeRepository.getRecentRoute(id) }
                .onSuccess { route ->
                    if (route == null) setRouteOperationError("Recent route is no longer available.")
                    else loadRoutePreview(route.points, route.distanceMeters, route.name, route.savedRouteId)
                }
                .onFailure { failure -> setRouteOperationError(failure.message ?: "Unable to load recent route.") }
        }
    }

    fun deleteSavedRoute(id: Long) {
        viewModelScope.launch {
            runCatching { routeRepository.deleteSavedRoute(id) }
                .onSuccess { deleted ->
                    mutableUiState.update { current ->
                        current.copy(
                            activeSavedRouteId = current.activeSavedRouteId?.takeUnless { it == id },
                            routeOperationResult = RouteOperationResult(
                                if (deleted) "Saved route deleted." else "Saved route is no longer available.",
                                isError = !deleted,
                            ),
                        )
                    }
                }
                .onFailure { failure -> setRouteOperationError(failure.message ?: "Unable to delete saved route.") }
        }
    }

    fun reverseSavedRoute(id: Long, name: String? = null) {
        viewModelScope.launch {
            runCatching { routeRepository.reverse(id, name) }
                .onSuccess { reversed ->
                    loadRoutePreview(reversed.points, reversed.distanceMeters, reversed.name, reversed.id)
                    mutableUiState.update {
                        it.copy(routeOperationResult = RouteOperationResult("Created reversed route \u201c${reversed.name}\u201d."))
                    }
                }
                .onFailure { failure -> setRouteOperationError(failure.message ?: "Unable to reverse saved route.") }
        }
    }

    fun recordPlannedRouteAsRecent(name: String? = null) {
        val state = mutableUiState.value
        val route = state.plannedRoute ?: return setRouteOperationError("Plan a route before adding it to recents.")
        viewModelScope.launch {
            runCatching {
                routeRepository.recordRecent(
                    name = name ?: state.activeRouteName ?: defaultRouteName(),
                    points = route.points,
                    savedRouteId = state.activeSavedRouteId,
                )
            }.onSuccess { recent ->
                mutableUiState.update {
                    it.copy(routeOperationResult = RouteOperationResult("Added \u201c${recent.name}\u201d to recent routes."))
                }
            }.onFailure { failure -> setRouteOperationError(failure.message ?: "Unable to add route to recents.") }
        }
    }

    fun deleteRecentRoute(id: Long) {
        viewModelScope.launch {
            runCatching { routeRepository.deleteRecentRoute(id) }
                .onSuccess { deleted ->
                    mutableUiState.update {
                        it.copy(
                            routeOperationResult = RouteOperationResult(
                                if (deleted) "Recent route deleted." else "Recent route is no longer available.",
                                isError = !deleted,
                            ),
                        )
                    }
                }
                .onFailure { failure -> setRouteOperationError(failure.message ?: "Unable to delete recent route.") }
        }
    }

    fun clearRecentRoutes() {
        viewModelScope.launch {
            runCatching { routeRepository.clearRecentRoutes() }
                .onSuccess {
                    mutableUiState.update { it.copy(routeOperationResult = RouteOperationResult("Recent routes cleared.")) }
                }
                .onFailure { failure -> setRouteOperationError(failure.message ?: "Unable to clear recent routes.") }
        }
    }

    fun exportRouteBackup() {
        viewModelScope.launch {
            runCatching { routeRepository.exportBackup() }
                .onSuccess { json ->
                    mutableUiState.update {
                        it.copy(
                            routeOperationResult = RouteOperationResult(
                                message = "Route backup is ready.",
                                export = RouteExport("application/json", "mock-gps-routes.json", json),
                            ),
                        )
                    }
                }
                .onFailure { failure -> setRouteOperationError(failure.message ?: "Unable to export route backup.") }
        }
    }

    fun restoreRouteBackup(serialized: String, replaceExisting: Boolean = false) {
        viewModelScope.launch {
            runCatching { routeRepository.restoreBackup(serialized, replaceExisting) }
                .onSuccess { restored ->
                    mutableUiState.update {
                        it.copy(
                            routeOperationResult = RouteOperationResult(
                                "Restored ${restored.savedRoutesRestored} saved routes and ${restored.recentRoutesRestored} recent routes.",
                            ),
                        )
                    }
                }
                .onFailure { failure -> setRouteOperationError(failure.message ?: "Unable to restore route backup.") }
        }
    }

    fun exportPlannedRouteGpx(name: String? = null) {
        val state = mutableUiState.value
        val route = state.plannedRoute ?: return setRouteOperationError("Plan a route before exporting GPX.")
        viewModelScope.launch {
            runCatching {
                val routeName = name ?: state.activeRouteName ?: defaultRouteName()
                routeName to RouteGpxInterchange.export(routeName, route.points)
            }.onSuccess { (routeName, gpx) ->
                mutableUiState.update {
                    it.copy(
                        routeOperationResult = RouteOperationResult(
                            message = "GPX export is ready.",
                            export = RouteExport("application/gpx+xml", routeName.safeFileName("gpx"), gpx),
                        ),
                    )
                }
            }.onFailure { failure -> setRouteOperationError(failure.message ?: "Unable to export GPX.") }
        }
    }

    fun importGpx(serialized: String) {
        viewModelScope.launch {
            runCatching { RouteGpxInterchange.import(serialized) }
                .onSuccess { imported ->
                    loadRoutePreview(imported.points, routeDistance(imported.points), imported.name, null)
                    mutableUiState.update { it.copy(routeOperationResult = RouteOperationResult("Imported GPX route \u201c${imported.name}\u201d.")) }
                }
                .onFailure { failure -> setRouteOperationError(failure.message ?: "Unable to import GPX.") }
        }
    }

    fun consumeRouteOperationResult() {
        mutableUiState.update { it.copy(routeOperationResult = null) }
    }

    fun beginRoutePlanning() {
        routePlanningJob?.cancel()
        mutableUiState.update {
            it.copy(
                isRoutePlanningMode = true,
                routeOrigin = null,
                routeDestination = null,
                routeWaypoints = emptyList(),
                plannedRoute = null,
                activeSavedRouteId = null,
                activeRouteName = null,
                isPlanningRoute = false,
                routeError = null,
            )
        }
    }

    fun setRouteOrigin(coordinate: Coordinate) {
        routePlanningJob?.cancel()
        mutableUiState.update {
            it.copy(
                isRoutePlanningMode = true,
                routeOrigin = coordinate,
                routeDestination = null,
                routeWaypoints = listOf(coordinate),
                plannedRoute = null,
                routeError = null,
                isPlanningRoute = false,
                activeSavedRouteId = null,
                activeRouteName = null,
            )
        }
    }

    fun setRouteDestination(coordinate: Coordinate) {
        routePlanningJob?.cancel()
        mutableUiState.update {
            it.copy(
                routeDestination = coordinate,
                routeWaypoints = listOfNotNull(it.routeOrigin, coordinate),
                plannedRoute = null,
                routeError = null,
                isPlanningRoute = false,
                activeSavedRouteId = null,
                activeRouteName = null,
            )
        }
    }

    fun addRouteWaypoint(coordinate: Coordinate) {
        routePlanningJob?.cancel()
        mutableUiState.update { state ->
            if (state.routeOrigin == null || state.routeDestination == null || state.routeWaypoints.size >= 25) {
                state
            } else {
                val points = state.routeWaypoints.ifEmpty { listOf(state.routeOrigin, state.routeDestination) }
                    .toMutableList()
                    .also { it.add(it.lastIndex, coordinate) }
                state.copy(
                    routeWaypoints = points,
                    plannedRoute = null,
                    isPlanningRoute = false,
                    activeSavedRouteId = null,
                    activeRouteName = null,
                    routeError = null,
                )
            }
        }
    }

    fun removeRouteWaypoint(index: Int) {
        routePlanningJob?.cancel()
        mutableUiState.update { state ->
            if (index !in 1 until state.routeWaypoints.lastIndex) state else state.copy(
                routeWaypoints = state.routeWaypoints.toMutableList().also { it.removeAt(index) },
                plannedRoute = null,
                isPlanningRoute = false,
                activeSavedRouteId = null,
                activeRouteName = null,
                routeError = null,
            )
        }
    }

    fun moveRouteWaypoint(index: Int, delta: Int) {
        routePlanningJob?.cancel()
        mutableUiState.update { state ->
            val destination = index + delta
            if (index !in 1 until state.routeWaypoints.lastIndex ||
                destination !in 1 until state.routeWaypoints.lastIndex
            ) state else state.copy(
                routeWaypoints = state.routeWaypoints.toMutableList().also { points ->
                    points.add(destination, points.removeAt(index))
                },
                plannedRoute = null,
                routeError = null,
            )
        }
    }

    fun swapRouteEndpoints() {
        routePlanningJob?.cancel()
        mutableUiState.update { state ->
            if (state.routeWaypoints.size < 2) state else {
                val points = state.routeWaypoints.toMutableList().also {
                    val first = it.first()
                    it[0] = it.last()
                    it[it.lastIndex] = first
                }
                state.copy(
                    routeOrigin = points.first(),
                    routeDestination = points.last(),
                    routeWaypoints = points,
                    plannedRoute = null,
                    isPlanningRoute = false,
                    activeSavedRouteId = null,
                    activeRouteName = null,
                    routeError = null,
                )
            }
        }
    }

    fun planBicycleRoute() {
        val state = mutableUiState.value
        val origin = state.routeOrigin ?: return
        val destination = state.routeDestination ?: return
        val waypoints = state.routeWaypoints.takeIf { it.size >= 2 } ?: listOf(origin, destination)
        if (waypoints.zipWithNext().any { (first, second) -> first == second }) {
            mutableUiState.update {
                it.copy(routeError = getApplication<Application>().getString(R.string.route_error_same_point))
            }
            return
        }
        routePlanningJob?.cancel()
        mutableUiState.update { it.copy(isPlanningRoute = true, plannedRoute = null, routeError = null) }
        routePlanningJob = viewModelScope.launch {
            try {
                val route = routingRepository.planBicycleRoute(waypoints)
                mutableUiState.update { current ->
                    if (current.isRoutePlanningMode &&
                        current.routeOrigin == origin && current.routeDestination == destination &&
                            current.routeWaypoints == waypoints
                    ) {
                        current.copy(plannedRoute = route, isPlanningRoute = false, routeError = null)
                    } else {
                        current
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                mutableUiState.update { current ->
                    if (current.isRoutePlanningMode &&
                        current.routeOrigin == origin && current.routeDestination == destination &&
                            current.routeWaypoints == waypoints
                    ) {
                        current.copy(
                            isPlanningRoute = false,
                            routeError = getApplication<Application>().getString(R.string.route_error_unavailable),
                        )
                    } else {
                        current
                    }
                }
            }
        }
    }

    fun editRouteDestination() {
        routePlanningJob?.cancel()
        mutableUiState.update {
            it.copy(
                routeDestination = null,
                routeWaypoints = it.routeOrigin?.let(::listOf).orEmpty(),
                plannedRoute = null,
                isPlanningRoute = false,
                activeSavedRouteId = null,
                activeRouteName = null,
                routeError = null,
            )
        }
    }

    fun editRouteOrigin() {
        routePlanningJob?.cancel()
        mutableUiState.update {
            it.copy(
                routeOrigin = null,
                routeDestination = null,
                routeWaypoints = emptyList(),
                plannedRoute = null,
                isPlanningRoute = false,
                activeSavedRouteId = null,
                activeRouteName = null,
                routeError = null,
            )
        }
    }

    fun clearRoute() {
        routePlanningJob?.cancel()
        mutableUiState.update {
            it.copy(
                isRoutePlanningMode = false,
                routeOrigin = null,
                routeDestination = null,
                routeWaypoints = emptyList(),
                plannedRoute = null,
                isPlanningRoute = false,
                activeSavedRouteId = null,
                activeRouteName = null,
                routeError = null,
            )
        }
    }

    fun navigateBackRoutePlanning() {
        when (mutableUiState.value.routePlanningStep) {
            RoutePlanningStep.Preview,
            RoutePlanningStep.Planning,
            RoutePlanningStep.ReadyToPreview,
            -> editRouteDestination()
            RoutePlanningStep.SelectDestination -> editRouteOrigin()
            RoutePlanningStep.SelectStart -> clearRoute()
            RoutePlanningStep.Inactive -> Unit
        }
    }

    private fun loadRoutePreview(
        points: List<Coordinate>,
        distanceMeters: Double,
        name: String,
        savedRouteId: Long?,
    ) {
        mutableUiState.update { state ->
            MapStateReducer.loadedRoutePreview(state, points, distanceMeters, name, savedRouteId)
        }
    }

    private fun setRouteOperationError(message: String) {
        mutableUiState.update { it.copy(routeOperationResult = RouteOperationResult(message, isError = true)) }
    }

    private fun defaultRouteName(): String =
        getApplication<Application>().getString(R.string.default_route_name)

    private fun awaitMapLoad() {
        mapLoadTimeout?.cancel()
        mapLoadTimeout = viewModelScope.launch {
            delay(MAP_LOAD_TIMEOUT_MILLIS)
            mutableUiState.update { current ->
                if (current.loadingState == MapLoadingState.Loading) {
                    current.copy(loadingState = MapLoadingState.Error)
                } else {
                    current
                }
            }
        }
    }

    override fun onCleared() {
        mapLoadTimeout?.cancel()
        routePlanningJob?.cancel()
        super.onCleared()
    }

    private companion object {
        const val MAP_LOAD_TIMEOUT_MILLIS = 12_000L
    }
}

private fun routeDistance(points: List<Coordinate>): Double = RoutePolyline(points).totalDistanceMeters

private fun String.safeFileName(extension: String): String {
    val base = trim()
        .replace(Regex("[^A-Za-z0-9._-]+"), "-")
        .trim('-', '.')
        .take(80)
        .ifBlank { "route" }
    return "$base.$extension"
}

/** Pure state transitions kept separate from Android lifecycle code for deterministic JVM tests. */
internal object MapStateReducer {
    fun cameraIdle(state: MapUiState, position: CameraPosition): MapUiState {
        val coordinate = Coordinate(position.target.latitude, position.target.longitude)
        if (!coordinate.isValid()) return state
        if (
            coordinate == state.pendingCoordinate &&
            position.zoom.toFloat() == state.camera.zoom &&
            position.bearing.toFloat() == state.camera.bearing &&
            position.tilt.toFloat() == state.camera.tilt
        ) return state
        return state.copy(
            pendingCoordinate = coordinate,
            camera = MapCamera(
                coordinate = coordinate,
                zoom = position.zoom.toFloat(),
                bearing = position.bearing.toFloat(),
                tilt = position.tilt.toFloat(),
            ),
        )
    }

    fun mapLoaded(state: MapUiState): MapUiState =
        state.copy(loadingState = MapLoadingState.Ready)

    fun retry(state: MapUiState): MapUiState = state.copy(
        loadingState = MapLoadingState.Loading,
        mapRenderKey = state.mapRenderKey + 1,
    )

    fun toggleMapType(state: MapUiState): MapUiState = state.copy(
        mapType = when (state.mapType) {
            MapDisplayType.Light -> MapDisplayType.Dark
            MapDisplayType.Dark -> MapDisplayType.Light
        },
        loadingState = MapLoadingState.Loading,
    )

    fun loadedRoutePreview(
        state: MapUiState,
        points: List<Coordinate>,
        distanceMeters: Double,
        name: String,
        savedRouteId: Long?,
    ): MapUiState {
        require(points.size >= 2) { "A route preview needs at least two points." }
        return state.copy(
            isRoutePlanningMode = true,
            routeOrigin = points.first(),
            routeDestination = points.last(),
            routeWaypoints = listOf(points.first(), points.last()),
            plannedRoute = PlannedRoute(points, distanceMeters, providerDurationSeconds = 0.0),
            isPlanningRoute = false,
            routeError = null,
            activeRouteName = name,
            activeSavedRouteId = savedRouteId,
        )
    }

    private fun Coordinate.isValid(): Boolean =
        latitude.isFinite() && latitude in -90.0..90.0 &&
            longitude.isFinite() && longitude in -180.0..180.0
}
