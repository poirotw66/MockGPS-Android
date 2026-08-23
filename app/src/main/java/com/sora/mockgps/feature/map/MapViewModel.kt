package com.sora.mockgps.feature.map

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sora.mockgps.R
import com.sora.mockgps.core.model.Coordinate
import com.sora.mockgps.feature.favorites.data.DefaultFavoriteLocationRepository
import com.sora.mockgps.feature.favorites.data.FavoriteLocationDatabase
import com.sora.mockgps.feature.favorites.domain.FavoriteLocation
import com.sora.mockgps.route.FossgisBicycleRoutingRepository
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
    val favorites: StateFlow<List<FavoriteLocation>> = favoriteRepository.favorites.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList(),
    )
    private val routingRepository: RoutingRepository = FossgisBicycleRoutingRepository()

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

    fun beginRoutePlanning() {
        routePlanningJob?.cancel()
        mutableUiState.update {
            it.copy(
                isRoutePlanningMode = true,
                routeOrigin = null,
                routeDestination = null,
                plannedRoute = null,
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
                plannedRoute = null,
                routeError = null,
                isPlanningRoute = false,
            )
        }
    }

    fun setRouteDestination(coordinate: Coordinate) {
        routePlanningJob?.cancel()
        mutableUiState.update {
            it.copy(
                routeDestination = coordinate,
                plannedRoute = null,
                routeError = null,
                isPlanningRoute = false,
            )
        }
    }

    fun planBicycleRoute() {
        val state = mutableUiState.value
        val origin = state.routeOrigin ?: return
        val destination = state.routeDestination ?: return
        if (origin == destination) {
            mutableUiState.update {
                it.copy(routeError = getApplication<Application>().getString(R.string.route_error_same_point))
            }
            return
        }
        routePlanningJob?.cancel()
        mutableUiState.update { it.copy(isPlanningRoute = true, plannedRoute = null, routeError = null) }
        routePlanningJob = viewModelScope.launch {
            try {
                val route = routingRepository.planBicycleRoute(origin, destination)
                mutableUiState.update { current ->
                    if (current.isRoutePlanningMode &&
                        current.routeOrigin == origin && current.routeDestination == destination
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
                        current.routeOrigin == origin && current.routeDestination == destination
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
            it.copy(routeDestination = null, plannedRoute = null, isPlanningRoute = false, routeError = null)
        }
    }

    fun editRouteOrigin() {
        routePlanningJob?.cancel()
        mutableUiState.update {
            it.copy(
                routeOrigin = null,
                routeDestination = null,
                plannedRoute = null,
                isPlanningRoute = false,
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
                plannedRoute = null,
                isPlanningRoute = false,
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
        super.onCleared()
    }

    private companion object {
        const val MAP_LOAD_TIMEOUT_MILLIS = 12_000L
    }
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

    private fun Coordinate.isValid(): Boolean =
        latitude.isFinite() && latitude in -90.0..90.0 &&
            longitude.isFinite() && longitude in -180.0..180.0
}
