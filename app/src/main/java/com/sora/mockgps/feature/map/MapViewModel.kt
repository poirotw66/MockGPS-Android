package com.sora.mockgps.feature.map

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sora.mockgps.R
import com.sora.mockgps.feature.search.PlaceSearchBias
import com.sora.mockgps.feature.search.PlaceSearchException
import com.sora.mockgps.feature.search.PlaceSearchResult
import com.sora.mockgps.feature.search.PlaceSearchSource
import com.sora.mockgps.feature.search.formatCoordinateSearchLabel
import com.sora.mockgps.feature.search.mergePlaceSearchResults
import com.sora.mockgps.feature.search.parseCoordinateSearchQuery
import com.sora.mockgps.feature.search.viewboxAround
import com.sora.mockgps.core.model.Coordinate
import com.sora.mockgps.feature.favorites.domain.FavoriteLocation
import com.sora.mockgps.feature.favorites.domain.RecentLocation
import com.sora.mockgps.feature.routes.data.RouteGpxInterchange
import com.sora.mockgps.feature.routes.domain.RecentRouteSummary
import com.sora.mockgps.feature.routes.domain.SavedRouteSummary
import com.sora.mockgps.route.PlannedRoute
import com.sora.mockgps.route.RoutePolyline
import com.sora.mockgps.route.RouteTransportMode
import java.util.Locale
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

class MapViewModel @JvmOverloads constructor(
    application: Application,
    private val dependencies: MapDependencies = MapDependencies.from(application),
) : AndroidViewModel(application) {
    private val mutableUiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = mutableUiState.asStateFlow()

    private val favoriteRepository = dependencies.favoriteRepository
    private val routeRepository = dependencies.routeRepository
    val favorites: StateFlow<List<FavoriteLocation>> = favoriteRepository.favorites.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )
    val recentLocations: StateFlow<List<RecentLocation>> = favoriteRepository.recentLocations.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )
    val savedRoutes: StateFlow<List<SavedRouteSummary>> = routeRepository.savedRoutes.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )
    val recentRoutes: StateFlow<List<RecentRouteSummary>> = routeRepository.recentRoutes.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )
    private val routingRepository = dependencies.routingRepository
    private val automaticJourneyRoutePlanner = AutomaticJourneyRoutePlanner(
        routingRepository,
        dependencies.journeyRandom,
    )
    private val settingsRepository = dependencies.settingsRepository
    private val placeSearchRepository = dependencies.placeSearchRepository

    private var mapLoadTimeout: Job? = null
    private var routePlanningJob: Job? = null
    private var automaticJourneyOptions: AutoJourneyOptions? = null
    private var placeSearchJob: Job? = null
    private var initialSettingsApplied = false

    init {
        awaitMapLoad()
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                mutableUiState.update { state ->
                    val restoredCoordinate = settings.lastCoordinate.takeUnless { initialSettingsApplied }
                    state.copy(
                        mapType = settings.mapType,
                        showCoordinates = settings.showCoordinates,
                        updateIntervalMillis = settings.updateIntervalMillis,
                        accuracyMeters = settings.accuracyMeters,
                        pendingCoordinate = restoredCoordinate ?: state.pendingCoordinate,
                        camera = restoredCoordinate?.let { state.camera.copy(coordinate = it) } ?: state.camera,
                    )
                }
                initialSettingsApplied = true
            }
        }
    }

    fun onCameraIdle(position: CameraPosition) {
        mutableUiState.update { MapStateReducer.cameraIdle(it, position) }
    }

    fun selectCoordinate(coordinate: Coordinate) {
        mutableUiState.update { MapStateReducer.selectCoordinate(it, coordinate) }
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
        val mapType = mutableUiState.value.mapType
        viewModelScope.launch { settingsRepository.update { it.copy(mapType = mapType) } }
        awaitMapLoad()
    }

    fun setShowCoordinates(show: Boolean) {
        mutableUiState.update { it.copy(showCoordinates = show) }
        viewModelScope.launch { settingsRepository.update { it.copy(showCoordinates = show) } }
    }

    fun setUpdateIntervalMillis(intervalMillis: Long) {
        val normalized = intervalMillis.coerceIn(250L, 60_000L)
        mutableUiState.update { it.copy(updateIntervalMillis = normalized) }
        viewModelScope.launch { settingsRepository.update { it.copy(updateIntervalMillis = normalized) } }
    }

    fun setAccuracyMeters(accuracyMeters: Float) {
        val normalized = accuracyMeters.coerceIn(1f, 100f)
        mutableUiState.update { it.copy(accuracyMeters = normalized) }
        viewModelScope.launch { settingsRepository.update { it.copy(accuracyMeters = normalized) } }
    }

    fun rememberActiveCoordinate(coordinate: Coordinate, recordStaticRecent: Boolean) {
        viewModelScope.launch {
            settingsRepository.update { it.copy(lastCoordinate = coordinate) }
            if (recordStaticRecent) favoriteRepository.recordRecent(coordinate.latitude, coordinate.longitude)
        }
    }

    /** Debounced, cancellable query entry point. Coordinates and landmarks first; remote rate limited at 1/s. */
    fun onPlaceSearchQueryChanged(query: String) {
        placeSearchJob?.cancel()
        val normalizedQuery = query.trim()
        if (normalizedQuery.length < MINIMUM_SEARCH_QUERY_LENGTH) {
            mutableUiState.update {
                it.copy(
                    placeSearchQuery = query,
                    isPlaceSearching = false,
                    placeSearchResults = emptyList(),
                    placeSearchError = null,
                )
            }
            return
        }
        val coordinateResult = parseCoordinateSearchQuery(normalizedQuery)?.let { coordinate ->
            PlaceSearchResult(
                name = formatCoordinateSearchLabel(coordinate),
                coordinate = coordinate,
                source = PlaceSearchSource.Coordinate,
            )
        }
        if (coordinateResult != null) {
            mutableUiState.update {
                it.copy(
                    placeSearchQuery = query,
                    isPlaceSearching = false,
                    placeSearchResults = listOf(coordinateResult),
                    placeSearchError = null,
                )
            }
            return
        }
        val useZhTw = currentSearchLocale().usesTraditionalChinese()
        val localResults = matchLandmarks(normalizedQuery, journeyLandmarks).map { landmark ->
            PlaceSearchResult(
                name = landmark.displayName(useZhTw),
                coordinate = landmark.coordinate,
                source = PlaceSearchSource.Landmark,
            )
        }
        mutableUiState.update {
            it.copy(
                placeSearchQuery = query,
                isPlaceSearching = true,
                placeSearchResults = localResults,
                placeSearchError = null,
            )
        }
        placeSearchJob = viewModelScope.launch {
            delay(350)
            try {
                val remote = placeSearchRepository.search(normalizedQuery, placeSearchBias())
                val merged = mergePlaceSearchResults(localResults, remote)
                mutableUiState.update { current ->
                    if (current.placeSearchQuery == query) {
                        current.copy(isPlaceSearching = false, placeSearchResults = merged)
                    } else {
                        current
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: PlaceSearchException) {
                val error = when (failure) {
                    is PlaceSearchException.Network -> PlaceSearchError.Network
                    is PlaceSearchException.RateLimited -> PlaceSearchError.RateLimited
                    is PlaceSearchException.InvalidResponse -> PlaceSearchError.InvalidResponse
                }
                mutableUiState.update { current ->
                    if (current.placeSearchQuery != query) {
                        current
                    } else {
                        current.copy(
                            isPlaceSearching = false,
                            // Keep landmark hits usable offline; only surface remote errors when empty.
                            placeSearchError = if (localResults.isEmpty()) error else null,
                        )
                    }
                }
            }
        }
    }

    private fun placeSearchBias(): PlaceSearchBias {
        val center = mutableUiState.value.camera.coordinate
        val locale = currentSearchLocale()
        return PlaceSearchBias(
            countryCodes = nearestJourneyRegion(center).nominatimCountryCode(),
            viewbox = viewboxAround(center),
            acceptLanguage = if (locale.usesTraditionalChinese()) "zh-TW" else locale.toLanguageTag(),
        )
    }

    private fun currentSearchLocale(): Locale =
        getApplication<Application>().resources.configuration.locales[0]

    fun saveFavorite(name: String, coordinate: Coordinate) {
        viewModelScope.launch {
            runCatching { favoriteRepository.save(name, coordinate.latitude, coordinate.longitude) }
                .onSuccess { favorite ->
                    mutableUiState.update { it.copy(favoriteMessage = favorite.name, routeError = null) }
                }
                .onFailure {
                    mutableUiState.update { it.copy(routeError = localized(R.string.favorite_save_failed)) }
                }
        }
    }

    fun renameFavorite(id: Long, name: String) {
        viewModelScope.launch {
            runCatching { favoriteRepository.rename(id, name) }
                .onFailure {
                    mutableUiState.update { it.copy(routeError = localized(R.string.favorite_rename_failed)) }
                }
        }
    }

    fun deleteFavorite(id: Long) {
        viewModelScope.launch { favoriteRepository.delete(id) }
    }

    fun clearFavorites() {
        viewModelScope.launch { favoriteRepository.clearAll() }
    }

    fun clearRecentLocations() {
        viewModelScope.launch { favoriteRepository.clearRecentLocations() }
    }

    fun consumeFavoriteMessage() {
        mutableUiState.update { it.copy(favoriteMessage = null) }
    }

    fun savePlannedRoute(name: String) {
        val route = mutableUiState.value.plannedRoute
            ?: return setRouteOperationError(localized(R.string.route_plan_before_save))
        viewModelScope.launch {
            runCatching { routeRepository.save(name, route.points) }
                .onSuccess { saved ->
                    mutableUiState.update {
                        it.copy(
                            activeSavedRouteId = saved.id,
                            activeRouteName = saved.name,
                            routeOperationResult = RouteOperationResult(localized(R.string.route_saved, saved.name)),
                        )
                    }
                }
                .onFailure { setRouteOperationError(localized(R.string.route_save_failed)) }
        }
    }

    fun loadSavedRoute(id: Long) {
        viewModelScope.launch {
            runCatching { routeRepository.getSavedRoute(id) }
                .onSuccess { route ->
                    if (route == null) setRouteOperationError(localized(R.string.saved_route_unavailable))
                    else loadRoutePreview(route.points, route.distanceMeters, route.name, route.id)
                }
                .onFailure { setRouteOperationError(localized(R.string.saved_route_load_failed)) }
        }
    }

    fun loadRecentRoute(id: Long) {
        viewModelScope.launch {
            runCatching { routeRepository.getRecentRoute(id) }
                .onSuccess { route ->
                    if (route == null) setRouteOperationError(localized(R.string.recent_route_unavailable))
                    else loadRoutePreview(route.points, route.distanceMeters, route.name, route.savedRouteId)
                }
                .onFailure { setRouteOperationError(localized(R.string.recent_route_load_failed)) }
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
                                if (deleted) localized(R.string.saved_route_deleted)
                                else localized(R.string.saved_route_unavailable),
                                isError = !deleted,
                            ),
                        )
                    }
                }
                .onFailure { setRouteOperationError(localized(R.string.saved_route_delete_failed)) }
        }
    }
    fun renameSavedRoute(id: Long, name: String) {
        viewModelScope.launch {
            runCatching { routeRepository.rename(id, name) }
                .onSuccess { renamed ->
                    mutableUiState.update {
                        it.copy(
                            activeRouteName = if (renamed && it.activeSavedRouteId == id) name.trim() else it.activeRouteName,
                            routeOperationResult = RouteOperationResult(
                                if (renamed) localized(R.string.route_renamed, name)
                                else localized(R.string.saved_route_unavailable),
                                isError = !renamed,
                            ),
                        )
                    }
                }
                .onFailure { setRouteOperationError(localized(R.string.route_rename_failed)) }
        }
    }

    fun duplicateSavedRoute(id: Long, name: String) {
        viewModelScope.launch {
            runCatching { routeRepository.duplicate(id, name) }
                .onSuccess { duplicated ->
                    mutableUiState.update {
                        it.copy(routeOperationResult = RouteOperationResult(localized(R.string.route_duplicated, duplicated.name)))
                    }
                }
                .onFailure { setRouteOperationError(localized(R.string.route_duplicate_failed)) }
        }
    }

    fun reverseSavedRoute(id: Long, name: String? = null) {
        viewModelScope.launch {
            runCatching { routeRepository.reverse(id, name) }
                .onSuccess { reversed ->
                    loadRoutePreview(reversed.points, reversed.distanceMeters, reversed.name, reversed.id)
                    mutableUiState.update {
                        it.copy(routeOperationResult = RouteOperationResult(localized(R.string.route_reversed, reversed.name)))
                    }
                }
                .onFailure { setRouteOperationError(localized(R.string.route_reverse_failed)) }
        }
    }

    fun recordPlannedRouteAsRecent(name: String? = null) {
        val state = mutableUiState.value
        val route = state.plannedRoute
            ?: return setRouteOperationError(localized(R.string.route_plan_before_recent))
        viewModelScope.launch {
            runCatching {
                routeRepository.recordRecent(
                    name = name ?: state.activeRouteName ?: defaultRouteName(),
                    points = route.points,
                    savedRouteId = state.activeSavedRouteId,
                )
            }.onSuccess { recent ->
                mutableUiState.update {
                    it.copy(routeOperationResult = RouteOperationResult(localized(R.string.route_recent_added, recent.name)))
                }
            }.onFailure { setRouteOperationError(localized(R.string.route_recent_add_failed)) }
        }
    }

    fun deleteRecentRoute(id: Long) {
        viewModelScope.launch {
            runCatching { routeRepository.deleteRecentRoute(id) }
                .onSuccess { deleted ->
                    mutableUiState.update {
                        it.copy(
                            routeOperationResult = RouteOperationResult(
                                if (deleted) localized(R.string.recent_route_deleted)
                                else localized(R.string.recent_route_unavailable),
                                isError = !deleted,
                            ),
                        )
                    }
                }
                .onFailure { setRouteOperationError(localized(R.string.recent_route_delete_failed)) }
        }
    }

    fun clearRecentRoutes() {
        viewModelScope.launch {
            runCatching { routeRepository.clearRecentRoutes() }
                .onSuccess {
                    mutableUiState.update {
                        it.copy(routeOperationResult = RouteOperationResult(localized(R.string.recent_routes_cleared)))
                    }
                }
                .onFailure { setRouteOperationError(localized(R.string.recent_routes_clear_failed)) }
        }
    }

    fun exportRouteBackup() {
        viewModelScope.launch {
            runCatching { routeRepository.exportBackup() }
                .onSuccess { json ->
                    mutableUiState.update {
                        it.copy(
                            routeOperationResult = RouteOperationResult(
                                message = localized(R.string.route_backup_ready),
                                export = RouteExport("application/json", "mock-gps-routes.json", json),
                            ),
                        )
                    }
                }
                .onFailure { setRouteOperationError(localized(R.string.route_backup_export_failed)) }
        }
    }

    fun restoreRouteBackup(serialized: String, replaceExisting: Boolean = false) {
        viewModelScope.launch {
            runCatching { routeRepository.restoreBackup(serialized, replaceExisting) }
                .onSuccess { restored ->
                    mutableUiState.update {
                        it.copy(
                            routeOperationResult = RouteOperationResult(
                                localized(
                                    R.string.route_backup_restored,
                                    restored.savedRoutesRestored,
                                    restored.recentRoutesRestored,
                                ),
                            ),
                        )
                    }
                }
                .onFailure { setRouteOperationError(localized(R.string.route_backup_restore_failed)) }
        }
    }

    fun exportPlannedRouteGpx(name: String? = null) {
        val state = mutableUiState.value
        val route = state.plannedRoute
            ?: return setRouteOperationError(localized(R.string.route_plan_before_gpx))
        viewModelScope.launch {
            runCatching {
                val routeName = name ?: state.activeRouteName ?: defaultRouteName()
                routeName to RouteGpxInterchange.export(routeName, route.points)
            }.onSuccess { (routeName, gpx) ->
                mutableUiState.update {
                    it.copy(
                        routeOperationResult = RouteOperationResult(
                            message = localized(R.string.gpx_export_ready),
                            export = RouteExport("application/gpx+xml", routeName.safeFileName("gpx"), gpx),
                        ),
                    )
                }
            }.onFailure { setRouteOperationError(localized(R.string.gpx_export_failed)) }
        }
    }

    fun importGpx(serialized: String) {
        viewModelScope.launch {
            runCatching { RouteGpxInterchange.import(serialized) }
                .onSuccess { imported ->
                    loadRoutePreview(imported.points, routeDistance(imported.points), imported.name, null)
                    mutableUiState.update {
                        it.copy(routeOperationResult = RouteOperationResult(localized(R.string.gpx_imported, imported.name)))
                    }
                }
                .onFailure { setRouteOperationError(localized(R.string.gpx_import_failed)) }
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
                routeTransportMode = RouteTransportMode.Bicycle,
                showRouteControlPoints = true,
                activeSavedRouteId = null,
                activeRouteName = null,
                isPlanningRoute = false,
                routeError = null,
                automaticJourneyRecoveryAvailable = false,
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
                showRouteControlPoints = true,
                routeError = null,
                automaticJourneyRecoveryAvailable = false,
                isPlanningRoute = false,
                activeSavedRouteId = null,
                activeRouteName = null,
            )
        }
    }

    fun setRouteDestination(coordinate: Coordinate) {
        routePlanningJob?.cancel()
        if (mutableUiState.value.routeOrigin == coordinate) {
            mutableUiState.update {
                it.copy(routeError = getApplication<Application>().getString(R.string.route_error_same_point))
            }
            return
        }
        mutableUiState.update {
            it.copy(
                routeDestination = coordinate,
                routeWaypoints = listOfNotNull(it.routeOrigin, coordinate),
                plannedRoute = null,
                showRouteControlPoints = true,
                routeError = null,
                automaticJourneyRecoveryAvailable = false,
                isPlanningRoute = false,
                activeSavedRouteId = null,
                activeRouteName = null,
            )
        }
        planBicycleRoute()
    }

    internal fun generateAutomaticJourney(options: AutoJourneyOptions) {
        routePlanningJob?.cancel()
        automaticJourneyOptions = options
        val journey = automaticJourneyRoutePlanner.generate(options)
        mutableUiState.update {
            AutomaticJourneyStateReducer.planning(
                state = it,
                journey = journey,
                transportMode = options.transportMode,
                routeName = localized(
                    R.string.generated_journey_name,
                    journey.landmark.displayName(currentSearchLocale().usesTraditionalChinese()),
                    localized(options.region.labelResource()),
                    localized(journey.shape.labelResource()),
                ),
            )
        }
        planAutomaticJourney(journey, options.transportMode)
    }

    fun regenerateAutomaticJourney() {
        automaticJourneyOptions?.let(::generateAutomaticJourney)
    }

    internal fun generateShapeRoute(center: Coordinate, shape: RouteShape) {
        routePlanningJob?.cancel()
        val points = JourneyPlanner.shapePoints(center, shape)
        val route = PlannedRoute(
            points = points,
            distanceMeters = RoutePolyline(points).totalDistanceMeters,
            providerDurationSeconds = 0.0,
        )
        mutableUiState.update {
            it.copy(
                isRoutePlanningMode = true,
                routeOrigin = points.first(),
                routeDestination = points.last(),
                routeWaypoints = emptyList(),
                plannedRoute = route,
                showRouteControlPoints = false,
                isPlanningRoute = false,
                routeError = null,
                automaticJourneyRecoveryAvailable = false,
                activeSavedRouteId = null,
                activeRouteName = localized(shape.labelResource()),
            )
        }
    }

    fun addRouteWaypoint(coordinate: Coordinate) {
        updateRouteAndPlan { state ->
            if (state.routeOrigin == null || state.routeDestination == null || state.routeWaypoints.size >= MAX_LETTERED_ROUTE_POINTS) {
                state
            } else {
                val points = state.routeWaypoints.ifEmpty { listOf(state.routeOrigin, state.routeDestination) }
                    .toMutableList()
                    .also { it.add(it.lastIndex, coordinate) }
                state.copy(
                    routeWaypoints = points,
                    plannedRoute = null,
                    showRouteControlPoints = true,
                    isPlanningRoute = false,
                    activeSavedRouteId = null,
                    activeRouteName = null,
                    routeError = null,
                )
            }
        }
    }

    fun removeRouteWaypoint(index: Int) {
        updateRouteAndPlan { state ->
            if (index !in 1 until state.routeWaypoints.lastIndex) state else state.copy(
                routeWaypoints = state.routeWaypoints.toMutableList().also { it.removeAt(index) },
                plannedRoute = null,
                isPlanningRoute = false,
                activeSavedRouteId = null,
                activeRouteName = null,
                routeError = null,
                automaticJourneyRecoveryAvailable = false,
            )
        }
    }

    fun moveRouteWaypoint(index: Int, delta: Int) {
        updateRouteAndPlan { state ->
            val destination = index + delta
            if (index !in 1 until state.routeWaypoints.lastIndex ||
                destination !in 1 until state.routeWaypoints.lastIndex
            ) state else state.copy(
                routeWaypoints = state.routeWaypoints.toMutableList().also { points ->
                    points.add(destination, points.removeAt(index))
                },
                plannedRoute = null,
                routeError = null,
                automaticJourneyRecoveryAvailable = false,
            )
        }
    }

    fun swapRouteEndpoints() {
        updateRouteAndPlan { state ->
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

    private fun updateRouteAndPlan(update: (MapUiState) -> MapUiState) {
        routePlanningJob?.cancel()
        var changed = false
        mutableUiState.update { state ->
            update(state).also { changed = it !== state }
        }
        if (changed) planBicycleRoute()
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
        mutableUiState.update {
            it.copy(
                isPlanningRoute = true,
                plannedRoute = null,
                routeError = null,
                automaticJourneyRecoveryAvailable = false,
            )
        }
        routePlanningJob = viewModelScope.launch {
            try {
                val route = routingRepository.planRoute(waypoints, state.routeTransportMode)
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

    private fun planAutomaticJourney(journey: GeneratedJourney, transportMode: RouteTransportMode) {
        val origin = journey.points.first()
        val destination = journey.points.last()
        val waypoints = journey.points
        routePlanningJob = viewModelScope.launch {
            try {
                val route = automaticJourneyRoutePlanner.plan(journey, transportMode)
                mutableUiState.update { current ->
                    if (current.isRoutePlanningMode && current.routeOrigin == origin &&
                        current.routeDestination == destination && current.routeWaypoints == waypoints
                    ) {
                        AutomaticJourneyStateReducer.success(current, route)
                    } else {
                        current
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                mutableUiState.update { current ->
                    if (current.isRoutePlanningMode && current.routeOrigin == origin &&
                        current.routeDestination == destination && current.routeWaypoints == waypoints
                    ) {
                        AutomaticJourneyStateReducer.failure(
                            current,
                            localized(R.string.auto_journey_route_error),
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
                showRouteControlPoints = true,
                isPlanningRoute = false,
                activeSavedRouteId = null,
                activeRouteName = null,
                routeError = null,
                automaticJourneyRecoveryAvailable = false,
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
                routeTransportMode = RouteTransportMode.Bicycle,
                showRouteControlPoints = true,
                isPlanningRoute = false,
                activeSavedRouteId = null,
                activeRouteName = null,
                routeError = null,
                automaticJourneyRecoveryAvailable = false,
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
                routeTransportMode = RouteTransportMode.Bicycle,
                showRouteControlPoints = true,
                isPlanningRoute = false,
                activeSavedRouteId = null,
                activeRouteName = null,
                routeError = null,
                automaticJourneyRecoveryAvailable = false,
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

    private fun localized(@StringRes resourceId: Int, vararg formatArgs: Any): String =
        getApplication<Application>().getString(resourceId, *formatArgs)

    @StringRes
    private fun JourneyRegion.labelResource(): Int = when (this) {
        JourneyRegion.Taiwan -> R.string.region_taiwan
        JourneyRegion.Japan -> R.string.region_japan
        JourneyRegion.SouthKorea -> R.string.region_south_korea
    }

    @StringRes
    private fun RouteShape.labelResource(): Int = when (this) {
        RouteShape.Heart -> R.string.shape_heart
        RouteShape.Star -> R.string.shape_star
        RouteShape.Circle -> R.string.shape_circle
        RouteShape.Cat -> R.string.shape_cat
        RouteShape.Dog -> R.string.shape_dog
        RouteShape.Rabbit -> R.string.shape_rabbit
        RouteShape.Fish -> R.string.shape_fish
        RouteShape.Butterfly -> R.string.shape_butterfly
        RouteShape.ChristmasTree -> R.string.shape_christmas_tree
    }

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
        placeSearchJob?.cancel()
        super.onCleared()
    }

    private companion object {
        const val MAP_LOAD_TIMEOUT_MILLIS = 12_000L
        const val MAX_LETTERED_ROUTE_POINTS = 26
        const val MINIMUM_SEARCH_QUERY_LENGTH = 2
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
