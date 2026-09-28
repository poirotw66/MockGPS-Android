package com.sora.mockgps.feature.map

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sora.mockgps.BloomWalkApplication
import com.sora.mockgps.R
import com.sora.mockgps.feature.search.LandmarkMatcher
import com.sora.mockgps.feature.search.PlaceSearchController
import com.sora.mockgps.feature.search.PlaceSearchErrorKind
import com.sora.mockgps.feature.search.PlaceSearchPhase
import com.sora.mockgps.feature.search.PlaceSearchResult
import com.sora.mockgps.feature.search.PlaceSearchSource
import com.sora.mockgps.feature.search.PlaceSearchSnapshot
import com.sora.mockgps.feature.search.buildPlaceSearchBias
import com.sora.mockgps.core.model.Coordinate
import com.sora.mockgps.core.settings.nextAccuracyMeters
import com.sora.mockgps.core.settings.nextUpdateIntervalMillis
import com.sora.mockgps.feature.favorites.domain.FavoriteLocation
import com.sora.mockgps.feature.favorites.domain.RecentLocation
import com.sora.mockgps.feature.routes.domain.RecentRouteSummary
import com.sora.mockgps.feature.routes.domain.SavedRouteSummary
import java.util.Locale
import kotlinx.coroutines.Job
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
    private val dependencies: MapDependencies = (application as? BloomWalkApplication)?.mapDependencies
        ?: MapDependencies.from(application),
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
    private val placeSearch = PlaceSearchController(
        scope = viewModelScope,
        repository = dependencies.placeSearchRepository,
        landmarkMatcher = LandmarkMatcher { query ->
            val useZhTw = currentSearchLocale().usesTraditionalChinese()
            matchLandmarks(query, journeyLandmarks).map { landmark ->
                PlaceSearchResult(
                    name = landmark.displayName(useZhTw),
                    coordinate = landmark.coordinate,
                    source = PlaceSearchSource.Landmark,
                )
            }
        },
        biasProvider = ::placeSearchBias,
        searchEnabled = { dependencies.configRepository.flags.value.searchEnabled },
    )
    private val routing = MapRoutingCoordinator(
        application = application,
        scope = viewModelScope,
        uiState = mutableUiState,
        routeRepository = routeRepository,
        routingRepository = routingRepository,
        automaticJourneyRoutePlanner = automaticJourneyRoutePlanner,
        searchLocale = ::currentSearchLocale,
    )

    private var mapLoadTimeout: Job? = null
    private var initialSettingsApplied = false

    init {
        placeSearch.addListener(::applyPlaceSearchSnapshot)
        awaitMapLoad()
        viewModelScope.launch {
            dependencies.configRepository.flags.collect { flags ->
                mutableUiState.update { state ->
                    state.copy(
                        remoteSearchEnabled = flags.searchEnabled,
                        remoteRoutingEnabled = flags.routingEnabled,
                        remoteAdsEnabled = flags.adsEnabled,
                        remotePurchasesEnabled = flags.purchasesEnabled,
                    )
                }
            }
        }
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                mutableUiState.update { state ->
                    val restoredCoordinate = settings.lastCoordinate.takeUnless { initialSettingsApplied }
                    state.copy(
                        mapType = settings.mapType,
                        showCoordinates = settings.showCoordinates,
                        updateIntervalMillis = settings.updateIntervalMillis,
                        accuracyMeters = settings.accuracyMeters,
                        setupGuideDismissed = settings.setupGuideDismissed,
                        settingsReady = true,
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
        mutableUiState.update { it.copy(updateIntervalMillis = intervalMillis) }
        viewModelScope.launch { settingsRepository.update { it.copy(updateIntervalMillis = intervalMillis) } }
    }

    fun cycleUpdateIntervalMillis() {
        setUpdateIntervalMillis(nextUpdateIntervalMillis(mutableUiState.value.updateIntervalMillis))
    }

    fun setAccuracyMeters(accuracyMeters: Float) {
        mutableUiState.update { it.copy(accuracyMeters = accuracyMeters) }
        viewModelScope.launch { settingsRepository.update { it.copy(accuracyMeters = accuracyMeters) } }
    }

    fun cycleAccuracyMeters() {
        setAccuracyMeters(nextAccuracyMeters(mutableUiState.value.accuracyMeters))
    }

    fun dismissSetupGuide() {
        mutableUiState.update { it.copy(setupGuideDismissed = true) }
        viewModelScope.launch { settingsRepository.update { it.copy(setupGuideDismissed = true) } }
    }

    fun rememberActiveCoordinate(coordinate: Coordinate, recordStaticRecent: Boolean) {
        viewModelScope.launch {
            settingsRepository.update { it.copy(lastCoordinate = coordinate) }
            if (recordStaticRecent) favoriteRepository.recordRecent(coordinate.latitude, coordinate.longitude)
        }
    }

    /** Local-only input. Typing never calls the gateway. */
    fun onPlaceSearchQueryChanged(query: String) {
        placeSearch.onQueryChanged(query)
    }

    /** IME Search and the visible search button share this explicit submit. */
    fun submitPlaceSearch() {
        placeSearch.submit()
    }

    private fun applyPlaceSearchSnapshot(snapshot: PlaceSearchSnapshot) {
        mutableUiState.update { current ->
            current.copy(
                placeSearchQuery = snapshot.query,
                isPlaceSearching = snapshot.phase == PlaceSearchPhase.Submitting,
                placeSearchPhase = snapshot.phase,
                placeSearchResults = snapshot.results,
                placeSearchAttribution = snapshot.attribution,
                placeSearchError = snapshot.error?.toUiError(),
                placeSearchSubmitEnabled = !placeSearch.isSubmitting,
            )
        }
    }

    private fun placeSearchBias() = buildPlaceSearchBias(
        cameraCenter = mutableUiState.value.camera.coordinate,
        acceptLanguage = currentSearchLocale().let { locale ->
            if (locale.usesTraditionalChinese()) "zh-TW" else locale.toLanguageTag()
        },
    )

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

    fun exportFavoritesBackup() {
        viewModelScope.launch {
            runCatching { favoriteRepository.exportBackup() }
                .onSuccess { json ->
                    mutableUiState.update {
                        it.copy(
                            routeOperationResult = RouteOperationResult(
                                message = localized(R.string.favorites_backup_ready),
                                export = RouteExport("application/json", "mock-gps-favorites.json", json),
                            ),
                        )
                    }
                }
                .onFailure {
                    mutableUiState.update {
                        it.copy(
                            routeOperationResult = RouteOperationResult(
                                message = localized(R.string.favorites_backup_export_failed),
                                isError = true,
                            ),
                        )
                    }
                }
        }
    }

    fun restoreFavoritesBackup(serialized: String, replaceExisting: Boolean = false) {
        viewModelScope.launch {
            runCatching { favoriteRepository.restoreBackup(serialized, replaceExisting) }
                .onSuccess { restored ->
                    mutableUiState.update {
                        it.copy(
                            routeOperationResult = RouteOperationResult(
                                localized(
                                    R.string.favorites_backup_restored,
                                    restored.favoritesRestored,
                                    restored.recentLocationsRestored,
                                ),
                            ),
                        )
                    }
                }
                .onFailure {
                    mutableUiState.update {
                        it.copy(
                            routeOperationResult = RouteOperationResult(
                                message = localized(R.string.favorites_backup_restore_failed),
                                isError = true,
                            ),
                        )
                    }
                }
        }
    }

    private fun localized(@StringRes resourceId: Int, vararg formatArgs: Any): String =
        getApplication<Application>().getString(resourceId, *formatArgs)

    fun savePlannedRoute(name: String) = routing.savePlannedRoute(name)
    fun loadSavedRoute(id: Long) = routing.loadSavedRoute(id)
    fun loadRecentRoute(id: Long) = routing.loadRecentRoute(id)
    fun deleteSavedRoute(id: Long) = routing.deleteSavedRoute(id)
    fun renameSavedRoute(id: Long, name: String) = routing.renameSavedRoute(id, name)
    fun duplicateSavedRoute(id: Long, name: String) = routing.duplicateSavedRoute(id, name)
    fun reverseSavedRoute(id: Long, name: String? = null) = routing.reverseSavedRoute(id, name)
    fun recordPlannedRouteAsRecent(name: String? = null) = routing.recordPlannedRouteAsRecent(name)
    fun deleteRecentRoute(id: Long) = routing.deleteRecentRoute(id)
    fun clearRecentRoutes() = routing.clearRecentRoutes()
    fun exportRouteBackup() = routing.exportRouteBackup()
    fun restoreRouteBackup(serialized: String, replaceExisting: Boolean = false) =
        routing.restoreRouteBackup(serialized, replaceExisting)
    fun exportPlannedRouteGpx(name: String? = null) = routing.exportPlannedRouteGpx(name)
    fun importGpx(serialized: String) = routing.importGpx(serialized)
    fun consumeRouteOperationResult() = routing.consumeRouteOperationResult()
    fun beginRoutePlanning() = routing.beginRoutePlanning()
    fun setRouteOrigin(coordinate: Coordinate) = routing.setRouteOrigin(coordinate)
    fun setRouteDestination(coordinate: Coordinate) = routing.setRouteDestination(coordinate)
    internal fun generateAutomaticJourney(options: AutoJourneyOptions) =
        routing.generateAutomaticJourney(options)
    fun regenerateAutomaticJourney() = routing.regenerateAutomaticJourney()
    internal fun generateShapeRoute(center: Coordinate, shape: RouteShape) =
        routing.generateShapeRoute(center, shape)
    fun addRouteWaypoint(coordinate: Coordinate) = routing.addRouteWaypoint(coordinate)
    fun removeRouteWaypoint(index: Int) = routing.removeRouteWaypoint(index)
    fun moveRouteWaypoint(index: Int, delta: Int) = routing.moveRouteWaypoint(index, delta)
    fun swapRouteEndpoints() = routing.swapRouteEndpoints()
    fun planBicycleRoute() = routing.planBicycleRoute()
    fun editRouteDestination() = routing.editRouteDestination()
    fun editRouteOrigin() = routing.editRouteOrigin()
    fun clearRoute() = routing.clearRoute()
    fun navigateBackRoutePlanning() = routing.navigateBackRoutePlanning()

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
        routing.cancel()
        super.onCleared()
    }

    private companion object {
        const val MAP_LOAD_TIMEOUT_MILLIS = 12_000L
    }
}

private fun PlaceSearchErrorKind.toUiError(): PlaceSearchError = when (this) {
    PlaceSearchErrorKind.Network -> PlaceSearchError.Network
    PlaceSearchErrorKind.RateLimited -> PlaceSearchError.RateLimited
    PlaceSearchErrorKind.GlobalQuota -> PlaceSearchError.GlobalQuota
    PlaceSearchErrorKind.InvalidRequest -> PlaceSearchError.InvalidRequest
    PlaceSearchErrorKind.RequestTooLarge -> PlaceSearchError.RequestTooLarge
    PlaceSearchErrorKind.InvalidResponse -> PlaceSearchError.InvalidResponse
    PlaceSearchErrorKind.Unavailable -> PlaceSearchError.Unavailable
}