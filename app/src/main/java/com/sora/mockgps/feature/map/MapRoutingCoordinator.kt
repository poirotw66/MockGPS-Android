package com.sora.mockgps.feature.map

import android.app.Application
import androidx.annotation.StringRes
import com.sora.mockgps.R
import com.sora.mockgps.core.model.Coordinate
import com.sora.mockgps.feature.routes.data.RouteDataValidator
import com.sora.mockgps.feature.routes.data.RouteGpxInterchange
import com.sora.mockgps.feature.routes.domain.RouteRepository
import com.sora.mockgps.route.PlannedRoute
import com.sora.mockgps.route.RoutePolyline
import com.sora.mockgps.route.RouteTransportMode
import com.sora.mockgps.route.RoutingRepository
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns route library CRUD, bicycle/auto-journey planning, and related UiState updates.
 */
internal class MapRoutingCoordinator(
    private val application: Application,
    private val scope: CoroutineScope,
    private val uiState: MutableStateFlow<MapUiState>,
    private val routeRepository: RouteRepository,
    private val routingRepository: RoutingRepository,
    private val automaticJourneyRoutePlanner: AutomaticJourneyRoutePlanner,
    private val searchLocale: () -> Locale,
) {
    private var routePlanningJob: Job? = null
    private var automaticJourneyOptions: AutoJourneyOptions? = null

    fun savePlannedRoute(name: String) {
        val route = uiState.value.plannedRoute
            ?: return setRouteOperationError(localized(R.string.route_plan_before_save))
        scope.launch {
            runCatching { routeRepository.save(name, route.points) }
                .onSuccess { saved ->
                    uiState.update {
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
        scope.launch {
            runCatching { routeRepository.getSavedRoute(id) }
                .onSuccess { route ->
                    if (route == null) setRouteOperationError(localized(R.string.saved_route_unavailable))
                    else loadRoutePreview(route.points, route.distanceMeters, route.name, route.id)
                }
                .onFailure { setRouteOperationError(localized(R.string.saved_route_load_failed)) }
        }
    }

    fun loadRecentRoute(id: Long) {
        scope.launch {
            runCatching { routeRepository.getRecentRoute(id) }
                .onSuccess { route ->
                    if (route == null) setRouteOperationError(localized(R.string.recent_route_unavailable))
                    else loadRoutePreview(route.points, route.distanceMeters, route.name, route.savedRouteId)
                }
                .onFailure { setRouteOperationError(localized(R.string.recent_route_load_failed)) }
        }
    }

    fun deleteSavedRoute(id: Long) {
        scope.launch {
            runCatching { routeRepository.deleteSavedRoute(id) }
                .onSuccess { deleted ->
                    uiState.update { current ->
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
        scope.launch {
            runCatching { routeRepository.rename(id, name) }
                .onSuccess { renamed ->
                    uiState.update {
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
        scope.launch {
            runCatching { routeRepository.duplicate(id, name) }
                .onSuccess { duplicated ->
                    uiState.update {
                        it.copy(routeOperationResult = RouteOperationResult(localized(R.string.route_duplicated, duplicated.name)))
                    }
                }
                .onFailure { setRouteOperationError(localized(R.string.route_duplicate_failed)) }
        }
    }

    fun reverseSavedRoute(id: Long, name: String? = null) {
        scope.launch {
            runCatching { routeRepository.reverse(id, name) }
                .onSuccess { reversed ->
                    loadRoutePreview(reversed.points, reversed.distanceMeters, reversed.name, reversed.id)
                    uiState.update {
                        it.copy(routeOperationResult = RouteOperationResult(localized(R.string.route_reversed, reversed.name)))
                    }
                }
                .onFailure { setRouteOperationError(localized(R.string.route_reverse_failed)) }
        }
    }

    fun recordPlannedRouteAsRecent(name: String? = null) {
        val state = uiState.value
        val route = state.plannedRoute
            ?: return setRouteOperationError(localized(R.string.route_plan_before_recent))
        scope.launch {
            runCatching {
                routeRepository.recordRecent(
                    name = name ?: state.activeRouteName ?: defaultRouteName(),
                    points = route.points,
                    savedRouteId = state.activeSavedRouteId,
                )
            }.onSuccess { recent ->
                uiState.update {
                    it.copy(routeOperationResult = RouteOperationResult(localized(R.string.route_recent_added, recent.name)))
                }
            }.onFailure { setRouteOperationError(localized(R.string.route_recent_add_failed)) }
        }
    }

    fun deleteRecentRoute(id: Long) {
        scope.launch {
            runCatching { routeRepository.deleteRecentRoute(id) }
                .onSuccess { deleted ->
                    uiState.update {
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
        scope.launch {
            runCatching { routeRepository.clearRecentRoutes() }
                .onSuccess {
                    uiState.update {
                        it.copy(routeOperationResult = RouteOperationResult(localized(R.string.recent_routes_cleared)))
                    }
                }
                .onFailure { setRouteOperationError(localized(R.string.recent_routes_clear_failed)) }
        }
    }

    fun exportRouteBackup() {
        scope.launch {
            runCatching { routeRepository.exportBackup() }
                .onSuccess { json ->
                    uiState.update {
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
        scope.launch {
            runCatching { routeRepository.restoreBackup(serialized, replaceExisting) }
                .onSuccess { restored ->
                    uiState.update {
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
        val state = uiState.value
        val route = state.plannedRoute
            ?: return setRouteOperationError(localized(R.string.route_plan_before_gpx))
        scope.launch {
            runCatching {
                val routeName = name ?: state.activeRouteName ?: defaultRouteName()
                routeName to RouteGpxInterchange.export(routeName, route.points)
            }.onSuccess { (routeName, gpx) ->
                uiState.update {
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
        scope.launch {
            runCatching { RouteGpxInterchange.import(serialized) }
                .onSuccess { imported ->
                    loadRoutePreview(imported.points, routeDistance(imported.points), imported.name, null)
                    val message = imported.simplifiedFromPointCount?.let { originalCount ->
                        localized(
                            R.string.gpx_imported_simplified,
                            imported.name,
                            originalCount,
                            imported.points.size,
                        )
                    } ?: localized(R.string.gpx_imported, imported.name)
                    uiState.update {
                        it.copy(routeOperationResult = RouteOperationResult(message))
                    }
                }
                .onFailure { failure ->
                    val message = when {
                        failure.message?.contains("too large", ignoreCase = true) == true ->
                            localized(R.string.gpx_import_file_too_large)
                        failure.message?.contains("2 to", ignoreCase = true) == true ->
                            localized(R.string.gpx_import_invalid_point_count, RouteDataValidator.MAX_POINTS)
                        else -> localized(R.string.gpx_import_failed)
                    }
                    setRouteOperationError(message)
                }
        }
    }

    fun consumeRouteOperationResult() {
        uiState.update { it.copy(routeOperationResult = null) }
    }

    fun beginRoutePlanning() {
        routePlanningJob?.cancel()
        uiState.update {
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
                automaticJourneyRecoveryKind = null,
            )
        }
    }

    fun setRouteOrigin(coordinate: Coordinate) {
        routePlanningJob?.cancel()
        uiState.update {
            it.copy(
                isRoutePlanningMode = true,
                routeOrigin = coordinate,
                routeDestination = null,
                routeWaypoints = listOf(coordinate),
                plannedRoute = null,
                showRouteControlPoints = true,
                routeError = null,
                automaticJourneyRecoveryAvailable = false,
                automaticJourneyRecoveryKind = null,
                isPlanningRoute = false,
                activeSavedRouteId = null,
                activeRouteName = null,
            )
        }
    }

    fun setRouteDestination(coordinate: Coordinate) {
        routePlanningJob?.cancel()
        if (uiState.value.routeOrigin == coordinate) {
            uiState.update {
                it.copy(routeError = application.getString(R.string.route_error_same_point))
            }
            return
        }
        uiState.update {
            it.copy(
                routeDestination = coordinate,
                routeWaypoints = listOfNotNull(it.routeOrigin, coordinate),
                plannedRoute = null,
                showRouteControlPoints = true,
                routeError = null,
                automaticJourneyRecoveryAvailable = false,
                automaticJourneyRecoveryKind = null,
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
        val routeName = automaticJourneyRouteName(options, journey)
        when (options.routeStyle) {
            AutoJourneyRouteStyle.PerfectShape -> {
                val route = perfectShapeRoute(journey.points)
                uiState.update {
                    AutomaticJourneyStateReducer.planning(
                        state = it,
                        journey = journey,
                        transportMode = options.transportMode,
                        routeName = routeName,
                    ).copy(
                        routeWaypoints = emptyList(),
                        plannedRoute = route,
                        isPlanningRoute = false,
                    )
                }
            }
            AutoJourneyRouteStyle.RoadAdapted -> {
                uiState.update {
                    AutomaticJourneyStateReducer.planning(
                        state = it,
                        journey = journey,
                        transportMode = options.transportMode,
                        routeName = routeName,
                    )
                }
                planAutomaticJourney(
                    journey = journey,
                    transportMode = options.transportMode,
                    targetDistanceMeters = JourneyPlanner.targetDistanceMeters(options),
                    routeName = routeName,
                )
            }
        }
    }

    fun regenerateAutomaticJourney() {
        automaticJourneyOptions?.let(::generateAutomaticJourney)
    }

    internal fun generateShapeRoute(center: Coordinate, shape: RouteShape) {
        routePlanningJob?.cancel()
        val points = JourneyPlanner.shapePoints(center, shape)
        val route = perfectShapeRoute(points)
        uiState.update {
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
                automaticJourneyRecoveryKind = null,
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
                automaticJourneyRecoveryKind = null,
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
                automaticJourneyRecoveryKind = null,
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
        uiState.update { state ->
            update(state).also { changed = it !== state }
        }
        if (changed) planBicycleRoute()
    }

    fun planBicycleRoute() {
        val state = uiState.value
        val origin = state.routeOrigin ?: return
        val destination = state.routeDestination ?: return
        val waypoints = state.routeWaypoints.takeIf { it.size >= 2 } ?: listOf(origin, destination)
        if (waypoints.zipWithNext().any { (first, second) -> first == second }) {
            uiState.update {
                it.copy(routeError = application.getString(R.string.route_error_same_point))
            }
            return
        }
        routePlanningJob?.cancel()
        uiState.update {
            it.copy(
                isPlanningRoute = true,
                plannedRoute = null,
                routeError = null,
                automaticJourneyRecoveryAvailable = false,
                automaticJourneyRecoveryKind = null,
            )
        }
        routePlanningJob = scope.launch {
            try {
                val route = routingRepository.planRoute(waypoints, state.routeTransportMode)
                uiState.update { current ->
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
                val message = when (failure) {
                    is com.sora.mockgps.route.RoutingNetworkException,
                    is com.sora.mockgps.route.RoutingUnavailableException,
                    -> application.getString(R.string.route_error_network)
                    else -> application.getString(R.string.route_error_unavailable)
                }
                uiState.update { current ->
                    if (current.isRoutePlanningMode &&
                        current.routeOrigin == origin && current.routeDestination == destination &&
                            current.routeWaypoints == waypoints
                    ) {
                        current.copy(
                            isPlanningRoute = false,
                            routeError = message,
                        )
                    } else {
                        current
                    }
                }
            }
        }
    }

    private fun planAutomaticJourney(
        journey: GeneratedJourney,
        transportMode: RouteTransportMode,
        targetDistanceMeters: Double,
        routeName: String,
    ) {
        routePlanningJob = scope.launch {
            try {
                val result = automaticJourneyRoutePlanner.planWithinTargetDistance(
                    journey = journey,
                    transportMode = transportMode,
                    targetDistanceMeters = targetDistanceMeters,
                )
                uiState.update { current ->
                    if (current.isRoutePlanningMode && current.isPlanningRoute &&
                        current.activeRouteName == routeName
                    ) {
                        AutomaticJourneyStateReducer.success(current, result.route, result.journey)
                    } else {
                        current
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                uiState.update { current ->
                    if (current.isRoutePlanningMode && current.isPlanningRoute &&
                        current.activeRouteName == routeName
                    ) {
                        AutomaticJourneyStateReducer.failure(
                            current,
                            automaticJourneyRouteError(),
                            automaticJourneyRecoveryKind(),
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
        uiState.update {
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
                automaticJourneyRecoveryKind = null,
            )
        }
    }

    fun editRouteOrigin() {
        routePlanningJob?.cancel()
        uiState.update {
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
                automaticJourneyRecoveryKind = null,
            )
        }
    }

    fun clearRoute() {
        routePlanningJob?.cancel()
        uiState.update {
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
                automaticJourneyRecoveryKind = null,
            )
        }
    }

    fun navigateBackRoutePlanning() {
        when (uiState.value.routePlanningStep) {
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
        uiState.update { state ->
            MapStateReducer.loadedRoutePreview(state, points, distanceMeters, name, savedRouteId)
        }
    }

    private fun setRouteOperationError(message: String) {
        uiState.update { it.copy(routeOperationResult = RouteOperationResult(message, isError = true)) }
    }

    private fun defaultRouteName(): String =
        application.getString(R.string.default_route_name)

    private fun localized(@StringRes resourceId: Int, vararg formatArgs: Any): String =
        application.getString(resourceId, *formatArgs)

    @StringRes
    private fun JourneyRegion.labelResource(): Int = when (this) {
        JourneyRegion.CurrentLocation -> R.string.region_current_location
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

    private fun automaticJourneyRouteName(options: AutoJourneyOptions, journey: GeneratedJourney): String {
        val useZhTw = searchLocale().usesTraditionalChinese()
        return when (options.region) {
            JourneyRegion.CurrentLocation -> localized(
                R.string.generated_current_location_journey_name,
                localized(journey.shape.labelResource()),
            )
            else -> localized(
                R.string.generated_journey_name,
                journey.landmark.displayName(useZhTw),
                localized(options.region.labelResource()),
                localized(journey.shape.labelResource()),
            )
        }
    }

    private fun automaticJourneyRouteError(): String = when (automaticJourneyOptions?.region) {
        JourneyRegion.CurrentLocation -> localized(R.string.auto_journey_route_error_current_location)
        else -> localized(R.string.auto_journey_route_error)
    }

    private fun automaticJourneyRecoveryKind(): AutomaticJourneyRecoveryKind =
        if (automaticJourneyOptions?.region == JourneyRegion.CurrentLocation) {
            AutomaticJourneyRecoveryKind.AnotherShape
        } else {
            AutomaticJourneyRecoveryKind.AnotherLandmark
        }

    private fun perfectShapeRoute(points: List<Coordinate>): PlannedRoute = PlannedRoute(
        points = points,
        distanceMeters = RoutePolyline(points).totalDistanceMeters,
        providerDurationSeconds = 0.0,
    )

    fun cancel() {
        routePlanningJob?.cancel()
    }

    private companion object {
        const val MAX_LETTERED_ROUTE_POINTS = 26
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
