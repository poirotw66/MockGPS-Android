package com.sora.mockgps.feature.map

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sora.mockgps.R
import com.sora.mockgps.core.model.Coordinate
import com.sora.mockgps.feature.favorites.domain.FavoriteLocation
import com.sora.mockgps.feature.routes.domain.SavedRouteSummary
import com.sora.mockgps.route.PlannedRoute
import com.sora.mockgps.service.MockLocationForegroundService
import com.sora.mockgps.service.MockServiceState
import com.sora.mockgps.service.MockServiceErrorKind
import com.sora.mockgps.service.RouteCompleted
import com.sora.mockgps.service.RouteFailed
import com.sora.mockgps.service.RoutePaused
import com.sora.mockgps.service.RouteProgress
import com.sora.mockgps.service.RouteRunning
import com.sora.mockgps.service.RouteServiceState
import com.sora.mockgps.service.RouteStarting
import com.sora.mockgps.ui.theme.BloomWalkCoral
import com.sora.mockgps.ui.theme.BloomWalkGold
import com.sora.mockgps.ui.theme.BloomWalkSage
import java.util.Locale
import kotlin.math.cos
import kotlin.math.log2
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.OrnamentOptions
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.ClickResult
import org.maplibre.spatialk.geojson.Position

@Composable
internal fun ServiceErrorDialog(
    kind: MockServiceErrorKind,
    onDismiss: () -> Unit,
    onOpenDeveloperOptions: () -> Unit,
) {
    val message = stringResource(
        when (kind) {
            MockServiceErrorKind.MockAppSetup -> R.string.mock_error_setup_required
            MockServiceErrorKind.GooglePlayServices -> R.string.mock_error_google_play_services
            MockServiceErrorKind.Generic -> R.string.mock_error_generic
        },
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mock_error_title)) },
        text = { Text(message) },
        confirmButton = {
            if (kind == MockServiceErrorKind.MockAppSetup) {
                TextButton(onClick = onOpenDeveloperOptions) {
                    Text(stringResource(R.string.action_open_developer_options))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}

/** A full-screen map picker with controls kept clear of the map's centre. */
@Composable
fun MapScreen(viewModel: MapViewModel = viewModel()) {
    val context = LocalContext.current
    val exportRouteFailed = stringResource(R.string.export_route_failed)
    val readGpxFailed = stringResource(R.string.read_gpx_failed)
    val readBackupFailed = stringResource(R.string.read_backup_failed)
    val currentLocationUnavailable = stringResource(R.string.current_location_unavailable)
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val recentLocations by viewModel.recentLocations.collectAsStateWithLifecycle()
    val savedRoutes by viewModel.savedRoutes.collectAsStateWithLifecycle()
    val recentRoutes by viewModel.recentRoutes.collectAsStateWithLifecycle()
    val serviceState by MockLocationForegroundService.state.collectAsStateWithLifecycle()
    val routeState by MockLocationForegroundService.routeState.collectAsStateWithLifecycle()
    val cameraState = rememberMapCameraState(uiState.camera)
    val coroutineScope = rememberCoroutineScope()
    var permissionMessage by remember { mutableStateOf<String?>(null) }
    var serviceStartErrorMessage by remember { mutableStateOf<String?>(null) }
    var notificationPermissionHandled by rememberSaveable { mutableStateOf(false) }
    var pendingRouteStart by rememberSaveable { mutableStateOf(false) }
    var pendingCurrentLocation by rememberSaveable { mutableStateOf(false) }
    var routeOptions by rememberSaveable(stateSaver = RouteSimulationOptionsSaver) {
        mutableStateOf(RouteSimulationOptions())
    }
    var saveFavoriteCoordinate by remember { mutableStateOf<Coordinate?>(null) }
    var showFavorites by remember { mutableStateOf(false) }
    var renameFavorite by remember { mutableStateOf<FavoriteLocation?>(null) }
    var deleteFavorite by remember { mutableStateOf<FavoriteLocation?>(null) }
    var showRouteLibrary by remember { mutableStateOf(false) }
    var renameSavedRoute by remember { mutableStateOf<SavedRouteSummary?>(null) }
    var duplicateSavedRoute by remember { mutableStateOf<SavedRouteSummary?>(null) }
    var saveRouteName by remember { mutableStateOf(false) }
    var pendingRouteExport by remember { mutableStateOf<RouteExport?>(null) }
    var pendingRecentRoute by remember { mutableStateOf(false) }
    var selectingRouteWaypoint by rememberSaveable { mutableStateOf(false) }
    var showAutoJourney by rememberSaveable { mutableStateOf(false) }
    var showShapeRoute by rememberSaveable { mutableStateOf(false) }
    var confirmClearFavorites by remember { mutableStateOf(false) }
    var confirmClearRecentLocations by remember { mutableStateOf(false) }
    var confirmClearRecents by remember { mutableStateOf(false) }

    val createRouteFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val export = pendingRouteExport
        pendingRouteExport = null
        if (uri != null && export != null) {
            runCatching { context.writeText(uri, export.content) }
                .onFailure { Toast.makeText(context, exportRouteFailed, Toast.LENGTH_LONG).show() }
        }
        viewModel.consumeRouteOperationResult()
    }
    val importGpxLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { selected ->
            runCatching { context.readText(selected) }
                .onSuccess(viewModel::importGpx)
                .onFailure { Toast.makeText(context, readGpxFailed, Toast.LENGTH_LONG).show() }
        }
    }
    val importBackupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { selected ->
            runCatching { context.readText(selected) }
                .onSuccess { viewModel.restoreRouteBackup(it) }
                .onFailure { Toast.makeText(context, readBackupFailed, Toast.LENGTH_LONG).show() }
        }
    }

    val locationPermissionRequired = stringResource(R.string.location_permission_required)
    val notificationPermissionDenied = stringResource(R.string.notification_permission_denied)
    val serviceStartNotAllowed = stringResource(R.string.foreground_service_start_not_allowed)
    val serviceStartSecurityFailed = stringResource(R.string.foreground_service_security_failed)
    val serviceStartFailed = stringResource(R.string.foreground_service_start_failed)
    val developerOptionsUnavailable = stringResource(R.string.developer_options_unavailable)
    fun applyServiceStartOutcome(outcome: ForegroundServiceStartOutcome) {
        serviceStartErrorMessage = when (outcome) {
            ForegroundServiceStartOutcome.Started -> null
            is ForegroundServiceStartOutcome.NotAllowed -> serviceStartNotAllowed
            is ForegroundServiceStartOutcome.SecurityOrSetup -> serviceStartSecurityFailed
            is ForegroundServiceStartOutcome.Failed -> serviceStartFailed
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val shouldStartRoute = pendingRouteStart
        val shouldUseCurrentLocation = pendingCurrentLocation
        pendingRouteStart = false
        pendingCurrentLocation = false
        notificationPermissionHandled = true
        if (!context.hasLocationPermission()) {
            permissionMessage = locationPermissionRequired
        } else {
            permissionMessage = if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !context.isGranted(Manifest.permission.POST_NOTIFICATIONS)
            ) notificationPermissionDenied else null
            if (shouldUseCurrentLocation) {
                val coordinate = context.lastKnownCoordinate()
                if (coordinate == null) {
                    permissionMessage = currentLocationUnavailable
                } else {
                    viewModel.selectCoordinate(coordinate)
                    coroutineScope.launch {
                        cameraState.animateTo(cameraState.position.copy(target = coordinate.toPosition()))
                    }
                }
            } else if (!shouldStartRoute) {
                serviceStartErrorMessage = null
                applyServiceStartOutcome(context.startMockService(
                    uiState.pendingCoordinate, uiState.updateIntervalMillis, uiState.accuracyMeters,
                ))
            } else {
                val route = uiState.plannedRoute?.points
                if (route == null) {
                    serviceStartErrorMessage = serviceStartFailed
                } else {
                    pendingRecentRoute = true
                    serviceStartErrorMessage = null
                    val outcome = context.startRouteService(
                        route,
                        routeOptions,
                        uiState.updateIntervalMillis,
                        uiState.accuracyMeters,
                    )
                    if (outcome != ForegroundServiceStartOutcome.Started) {
                        pendingRecentRoute = false
                    }
                    applyServiceStartOutcome(outcome)
                }
            }
        }
    }

    val isRouteStarting = routeState is RouteStarting
    val isRouteRunning = routeState is RouteRunning
    val isRoutePaused = routeState is RoutePaused
    val isRouteSession = isRouteStarting || isRouteRunning || isRoutePaused
    val isStarting = serviceState is MockServiceState.Starting || isRouteStarting
    val isActive = serviceState is MockServiceState.Active
    val isMapReady = uiState.loadingState == MapLoadingState.Ready
    val routeProgress = routeState.progressOrNull()
    val activeCoordinate = routeProgress?.coordinate ?: (serviceState as? MockServiceState.Active)?.coordinate
    val favoriteSavedMessage = uiState.favoriteMessage?.let {
        stringResource(R.string.favorite_saved, it)
    }
    val serviceError = serviceState as? MockServiceState.Error

    LaunchedEffect(isStarting, isActive) {
        if (isStarting || isActive) serviceStartErrorMessage = null
    }

    LaunchedEffect(favoriteSavedMessage) {
        favoriteSavedMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.consumeFavoriteMessage()
        }
    }

    LaunchedEffect(uiState.routeOperationResult) {
        uiState.routeOperationResult?.let { result ->
            Toast.makeText(context, result.message, if (result.isError) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
            if (result.export == null) {
                viewModel.consumeRouteOperationResult()
            } else {
                pendingRouteExport = result.export
                createRouteFileLauncher.launch(result.export.fileName)
            }
        }
    }

    LaunchedEffect(uiState.routeWaypoints, uiState.plannedRoute) {
        uiState.routeWaypoints.takeIf { it.size >= 2 && uiState.plannedRoute == null }?.let { points ->
            cameraState.animateTo(points.previewCameraPosition(cameraState.position))
        }
    }
    LaunchedEffect(uiState.plannedRoute) {
        uiState.plannedRoute?.points?.let { points ->
            cameraState.animateTo(points.previewCameraPosition(cameraState.position))
        }
    }
    LaunchedEffect(routeState, pendingRecentRoute) {
        if (pendingRecentRoute && routeState is RouteRunning) {
            viewModel.recordPlannedRouteAsRecent()
            pendingRecentRoute = false
        } else if (pendingRecentRoute && routeState is RouteFailed) {
            pendingRecentRoute = false
        }
    }
    LaunchedEffect(serviceState, routeState) {
        (serviceState as? MockServiceState.Active)?.coordinate?.let { coordinate ->
            when (routeState) {
                RouteServiceState.Idle -> viewModel.rememberActiveCoordinate(coordinate, recordStaticRecent = true)
                is RouteCompleted -> viewModel.rememberActiveCoordinate(coordinate, recordStaticRecent = false)
                else -> Unit
            }
        }
    }
    serviceError?.let { error ->
        ServiceErrorDialog(
            kind = error.kind,
            onDismiss = MockLocationForegroundService::consumeError,
            onOpenDeveloperOptions = {
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                }.onFailure { permissionMessage = developerOptionsUnavailable }
                MockLocationForegroundService.consumeError()
            },
        )
    }

    saveFavoriteCoordinate?.let { coordinate ->
        FavoriteNameDialog(
            title = stringResource(R.string.favorite_new_title),
            initialName = "${coordinate.latitude.formatCoordinate()}, ${coordinate.longitude.formatCoordinate()}",
            onDismiss = { saveFavoriteCoordinate = null },
            onConfirm = { name ->
                viewModel.saveFavorite(name, coordinate)
                saveFavoriteCoordinate = null
            },
        )
    }
    if (showFavorites) {
        FavoritesDialog(
            favorites = favorites,
            recentLocations = recentLocations,
            onSelect = { favorite ->
                showFavorites = false
                val favoriteCoordinate = Coordinate(favorite.latitude, favorite.longitude)
                viewModel.selectCoordinate(favoriteCoordinate)
                when (uiState.routePlanningStep) {
                    RoutePlanningStep.SelectStart -> viewModel.setRouteOrigin(favoriteCoordinate)
                    RoutePlanningStep.SelectDestination -> viewModel.setRouteDestination(favoriteCoordinate)
                    else -> Unit
                }
                coroutineScope.launch {
                    cameraState.animateTo(
                        cameraState.position.copy(
                            target = Position(latitude = favorite.latitude, longitude = favorite.longitude),
                        ),
                    )
                }
            },
            onSelectRecent = { recent ->
                showFavorites = false
                val coordinate = Coordinate(recent.latitude, recent.longitude)
                viewModel.selectCoordinate(coordinate)
                when (uiState.routePlanningStep) {
                    RoutePlanningStep.SelectStart -> viewModel.setRouteOrigin(coordinate)
                    RoutePlanningStep.SelectDestination -> viewModel.setRouteDestination(coordinate)
                    else -> Unit
                }
                coroutineScope.launch {
                    cameraState.animateTo(cameraState.position.copy(target = coordinate.toPosition()))
                }
            },
            onRename = { renameFavorite = it },
            onDelete = { deleteFavorite = it },
            onClearAll = { confirmClearFavorites = true },
            onClearRecentLocations = { confirmClearRecentLocations = true },
            onDismiss = { showFavorites = false },
        )
    }
    if (showRouteLibrary) {
        RouteLibraryDialog(
            savedRoutes = savedRoutes,
            recentRoutes = recentRoutes,
            onLoadSaved = {
                viewModel.loadSavedRoute(it.id)
                showRouteLibrary = false
            },
            onLoadRecent = {
                viewModel.loadRecentRoute(it.id)
                showRouteLibrary = false
            },
            onReverse = {
                viewModel.reverseSavedRoute(it.id)
                showRouteLibrary = false
            },
            onRename = { renameSavedRoute = it },
            onDuplicate = { duplicateSavedRoute = it },
            onDelete = { viewModel.deleteSavedRoute(it.id) },
            onImportGpx = { importGpxLauncher.launch(arrayOf("application/gpx+xml", "text/xml", "application/xml")) },
            onImportBackup = { importBackupLauncher.launch(arrayOf("application/json")) },
            onExportBackup = viewModel::exportRouteBackup,
            onClearRecents = { confirmClearRecents = true },
            onDismiss = { showRouteLibrary = false },
        )
    }
    if (saveRouteName) {
        FavoriteNameDialog(
            title = stringResource(R.string.action_save_route),
            initialName = uiState.activeRouteName ?: stringResource(R.string.default_route_name),
            fieldLabelResource = R.string.route_name,
            onDismiss = { saveRouteName = false },
            onConfirm = {
                viewModel.savePlannedRoute(it)
                saveRouteName = false
            },
        )
    }
    renameSavedRoute?.let { route ->
        FavoriteNameDialog(
            title = stringResource(R.string.route_rename_title),
            initialName = route.name,
            fieldLabelResource = R.string.route_name,
            onDismiss = { renameSavedRoute = null },
            onConfirm = { name ->
                viewModel.renameSavedRoute(route.id, name)
                renameSavedRoute = null
            },
        )
    }
    duplicateSavedRoute?.let { route ->
        FavoriteNameDialog(
            title = stringResource(R.string.route_duplicate_title),
            initialName = stringResource(R.string.route_duplicate_name, route.name),
            fieldLabelResource = R.string.route_name,
            onDismiss = { duplicateSavedRoute = null },
            onConfirm = { name ->
                viewModel.duplicateSavedRoute(route.id, name)
                duplicateSavedRoute = null
            },
        )
    }
    if (showAutoJourney) AutoJourneyDialog(
        onDismiss = { showAutoJourney = false },
        onGenerate = { options ->
            showAutoJourney = false
            routeOptions = routeOptions.copy(
                preset = when (options.transportMode) {
                    com.sora.mockgps.route.RouteTransportMode.Walk -> MovementPreset.Walk
                    com.sora.mockgps.route.RouteTransportMode.Bicycle -> MovementPreset.Bicycle
                    com.sora.mockgps.route.RouteTransportMode.Drive -> MovementPreset.Drive
                },
            )
            viewModel.generateAutomaticJourney(options)
        },
    )
    if (showShapeRoute) ShapeRouteDialog(
        onDismiss = { showShapeRoute = false },
        onGenerate = { shape ->
            showShapeRoute = false
            viewModel.generateShapeRoute(uiState.pendingCoordinate, shape)
        },
    )
    if (confirmClearFavorites) ConfirmClearDialog(
        title = stringResource(R.string.clear_favorites_title),
        message = stringResource(R.string.clear_favorites_message),
        onConfirm = { viewModel.clearFavorites(); confirmClearFavorites = false; showFavorites = false },
        onDismiss = { confirmClearFavorites = false },
    )
    if (confirmClearRecentLocations) ConfirmClearDialog(
        title = stringResource(R.string.clear_recent_locations_title),
        message = stringResource(R.string.clear_recent_locations_message),
        onConfirm = {
            viewModel.clearRecentLocations()
            confirmClearRecentLocations = false
        },
        onDismiss = { confirmClearRecentLocations = false },
    )
    if (confirmClearRecents) ConfirmClearDialog(
        title = stringResource(R.string.clear_history_title),
        message = stringResource(R.string.clear_history_message),
        onConfirm = { viewModel.clearRecentRoutes(); confirmClearRecents = false },
        onDismiss = { confirmClearRecents = false },
    )

    BackHandler(
        enabled = (selectingRouteWaypoint || uiState.isRoutePlanningMode) &&
            !isRouteSession && !showFavorites && renameFavorite == null && deleteFavorite == null,
        onBack = {
            if (selectingRouteWaypoint) selectingRouteWaypoint = false
            else viewModel.navigateBackRoutePlanning()
        },
    )
    renameFavorite?.let { favorite ->
        FavoriteNameDialog(
            title = stringResource(R.string.favorite_rename_title),
            initialName = favorite.name,
            onDismiss = { renameFavorite = null },
            onConfirm = { name ->
                viewModel.renameFavorite(favorite.id, name)
                renameFavorite = null
            },
        )
    }
    deleteFavorite?.let { favorite ->
        DeleteFavoriteDialog(
            favoriteName = favorite.name,
            onConfirm = {
                viewModel.deleteFavorite(favorite.id)
                deleteFavorite = null
            },
            onDismiss = { deleteFavorite = null },
        )
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val compactLayout = maxWidth > maxHeight
        val panelMaxWidth = if (compactLayout) {
            (maxWidth / 2 - 16.dp).coerceAtMost(360.dp)
        } else {
            560.dp
        }
        MapPicker(
            modifier = Modifier.fillMaxSize(),
            mapType = uiState.mapType,
            mapRenderKey = uiState.mapRenderKey,
            loadingState = uiState.loadingState,
            pendingCoordinate = uiState.pendingCoordinate,
            routePoints = uiState.plannedRoute?.points ?: uiState.routeWaypoints,
            routeOrigin = uiState.routeOrigin,
            routeDestination = uiState.routeDestination,
            routeWaypoints = uiState.routeWaypoints,
            showRouteControlPoints = uiState.showRouteControlPoints,
            activeRouteCoordinate = activeCoordinate.takeIf { isRouteSession },
            cameraState = cameraState,
            onMapLoaded = viewModel::onMapLoaded,
            onMapLoadFailed = viewModel::onMapLoadFailed,
            onCameraIdle = viewModel::onCameraIdle,
            onCoordinateSelected = { coordinate ->
                viewModel.selectCoordinate(coordinate)
                when {
                    selectingRouteWaypoint -> {
                        selectingRouteWaypoint = false
                        viewModel.addRouteWaypoint(coordinate)
                    }
                    uiState.routePlanningStep == RoutePlanningStep.SelectStart -> {
                        viewModel.setRouteOrigin(coordinate)
                    }
                    uiState.routePlanningStep == RoutePlanningStep.SelectDestination -> {
                        viewModel.setRouteDestination(coordinate)
                    }
                }
            },
            onRetry = viewModel::retryMap,
        )
        val routeMapPrompt = when {
            selectingRouteWaypoint -> stringResource(R.string.route_map_prompt_stop)
            uiState.routeError != null -> uiState.routeError
            uiState.routePlanningStep == RoutePlanningStep.SelectStart -> stringResource(R.string.route_map_prompt_start)
            uiState.routePlanningStep == RoutePlanningStep.SelectDestination -> stringResource(R.string.route_map_prompt_destination)
            uiState.routePlanningStep == RoutePlanningStep.Planning -> stringResource(R.string.route_map_prompt_planning)
            uiState.routePlanningStep == RoutePlanningStep.Preview && !uiState.showRouteControlPoints -> uiState.activeRouteName
            else -> null
        }
        routeMapPrompt?.let { prompt ->
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 8.dp, start = 16.dp, end = 16.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
                tonalElevation = 3.dp,
                shadowElevation = 5.dp,
            ) {
                Text(
                    text = prompt,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (uiState.routeError != null) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
        }
        MapControlPanel(
            pendingCoordinate = uiState.pendingCoordinate,
            activeCoordinate = activeCoordinate,
            showCoordinates = uiState.showCoordinates,
            permissionMessage = serviceStartErrorMessage ?: permissionMessage,
            compactLayout = compactLayout,
            panelMaxWidth = panelMaxWidth,
            mapType = uiState.mapType,
            isMapReady = isMapReady,
            isStarting = isStarting,
            isActive = isActive,
            isRouteSession = isRouteSession,
            routePaused = isRoutePaused,
            routeProgress = routeProgress,
            routeResult = routeState.takeIf { it is RouteCompleted || it is RouteFailed },
            isSelectingRouteWaypoint = selectingRouteWaypoint,
            routeOptions = routeOptions,
            onRouteOptionsChange = { routeOptions = it },
            favoritesCount = favorites.size,
            routePlanningStep = uiState.routePlanningStep,
            routeOrigin = uiState.routeOrigin,
            routeDestination = uiState.routeDestination,
            routeWaypoints = uiState.routeWaypoints,
            plannedRoute = uiState.plannedRoute,
            isPlanningRoute = uiState.isPlanningRoute,
            routeError = uiState.routeError,
            automaticJourneyRecoveryAvailable = uiState.automaticJourneyRecoveryAvailable,
            activeRouteName = uiState.activeRouteName,
            placeSearchQuery = uiState.placeSearchQuery,
            isPlaceSearching = uiState.isPlaceSearching,
            placeSearchResults = uiState.placeSearchResults,
            placeSearchError = uiState.placeSearchError,
            onPlaceSearchQueryChanged = viewModel::onPlaceSearchQueryChanged,
            onPlaceSelected = { result ->
                viewModel.selectCoordinate(result.coordinate)
                coroutineScope.launch { cameraState.animateTo(cameraState.position.copy(target = result.coordinate.toPosition())) }
            },
            onShowCoordinatesChange = viewModel::setShowCoordinates,
            updateIntervalMillis = uiState.updateIntervalMillis,
            accuracyMeters = uiState.accuracyMeters,
            onUpdateIntervalChange = viewModel::setUpdateIntervalMillis,
            onAccuracyChange = viewModel::setAccuracyMeters,
            onToggleMapType = viewModel::toggleMapType,
            onOpenDeveloperOptions = {
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                }.onFailure { permissionMessage = developerOptionsUnavailable }
            },
            onUseCurrentLocation = {
                if (!context.hasLocationPermission()) {
                    pendingCurrentLocation = true
                    permissionLauncher.launch(
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                    )
                } else {
                    val coordinate = context.lastKnownCoordinate()
                    if (coordinate == null) permissionMessage = currentLocationUnavailable
                    else {
                        viewModel.selectCoordinate(coordinate)
                        coroutineScope.launch {
                            cameraState.animateTo(cameraState.position.copy(target = coordinate.toPosition()))
                        }
                    }
                }
            },
            onSaveFavorite = { saveFavoriteCoordinate = uiState.pendingCoordinate },
            onShowFavorites = { showFavorites = true },
            onShowRouteLibrary = { showRouteLibrary = true },
            onSaveRoute = { saveRouteName = true },
            onExportGpx = viewModel::exportPlannedRouteGpx,
            onBeginRoutePlanning = viewModel::beginRoutePlanning,
            onShowAutoJourney = { showAutoJourney = true },
            onRegenerateAutomaticJourney = viewModel::regenerateAutomaticJourney,
            onShowShapeRoute = { showShapeRoute = true },
            onPlanRoute = viewModel::planBicycleRoute,
            onEditRouteOrigin = viewModel::editRouteOrigin,
            onEditRouteDestination = viewModel::editRouteDestination,
            onAddRouteWaypoint = { selectingRouteWaypoint = true },
            onCancelRouteWaypointSelection = { selectingRouteWaypoint = false },
            onRemoveRouteWaypoint = viewModel::removeRouteWaypoint,
            onMoveRouteWaypoint = viewModel::moveRouteWaypoint,
            onSwapRouteEndpoints = viewModel::swapRouteEndpoints,
            onClearRoute = viewModel::clearRoute,
            onStart = {
                pendingRouteStart = false
                val permissions = context.requiredRuntimePermissions(notificationPermissionHandled)
                if (permissions.isEmpty()) {
                    serviceStartErrorMessage = null
                    applyServiceStartOutcome(context.startMockService(
                        uiState.pendingCoordinate, uiState.updateIntervalMillis, uiState.accuracyMeters,
                    ))
                } else {
                    permissionLauncher.launch(permissions.toTypedArray())
                }
            },
            onStartRoute = {
                uiState.plannedRoute?.points?.let { points ->
                    val permissions = context.requiredRuntimePermissions(notificationPermissionHandled)
                    if (permissions.isEmpty()) {
                        pendingRecentRoute = true
                        serviceStartErrorMessage = null
                        val outcome = context.startRouteService(
                            points,
                            routeOptions,
                            uiState.updateIntervalMillis,
                            uiState.accuracyMeters,
                        )
                        if (outcome != ForegroundServiceStartOutcome.Started) {
                            pendingRecentRoute = false
                        }
                        applyServiceStartOutcome(outcome)
                    } else {
                        pendingRouteStart = true
                        permissionLauncher.launch(permissions.toTypedArray())
                    }
                }
            },
            onPauseResumeRoute = {
                context.startService(
                    if (isRoutePaused) MockLocationForegroundService.resumeRouteIntent(context, routeState.sessionToken)
                    else MockLocationForegroundService.pauseRouteIntent(context, routeState.sessionToken),
                )
            },
            onApply = {
                context.startService(
                    MockLocationForegroundService.updateIntent(context, uiState.pendingCoordinate),
                )
            },
            onStop = {
                context.startService(MockLocationForegroundService.stopIntent(context, routeState.sessionToken))
            },
            modifier = Modifier
                .align(if (compactLayout) Alignment.CenterEnd else Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

private fun RouteServiceState.progressOrNull(): RouteProgress? = when (this) {
    RouteServiceState.Idle -> null
    is RouteStarting -> progress
    is RouteRunning -> progress
    is RoutePaused -> progress
    is RouteCompleted -> progress
    is RouteFailed -> lastProgress
}

@Composable
private fun MapPicker(
    modifier: Modifier,
    mapType: MapDisplayType,
    mapRenderKey: Int,
    loadingState: MapLoadingState,
    pendingCoordinate: Coordinate,
    routePoints: List<Coordinate>,
    routeOrigin: Coordinate?,
    routeDestination: Coordinate?,
    routeWaypoints: List<Coordinate>,
    showRouteControlPoints: Boolean,
    activeRouteCoordinate: Coordinate?,
    cameraState: CameraState,
    onMapLoaded: () -> Unit,
    onMapLoadFailed: () -> Unit,
    onCameraIdle: (CameraPosition) -> Unit,
    onCoordinateSelected: (Coordinate) -> Unit,
    onRetry: () -> Unit,
) {
    val mapDescription = stringResource(R.string.map_picker_description)
    val tapScope = rememberCoroutineScope()
    val mapOptions = MapOptions(
        ornamentOptions = OrnamentOptions(
            padding = WindowInsets.safeDrawing.asPaddingValues(),
        ),
    )
    Box(modifier = modifier.semantics { contentDescription = mapDescription }) {
        key(mapRenderKey) {
            MaplibreMap(
                modifier = Modifier.fillMaxSize(),
                baseStyle = BaseStyle.Uri(mapType.styleUrl),
                cameraState = cameraState,
                options = mapOptions,
                onMapLoadFinished = onMapLoaded,
                onMapLoadFailed = { onMapLoadFailed() },
                onMapClick = { position, _ ->
                        onCoordinateSelected(Coordinate(position.latitude, position.longitude))
                    tapScope.launch { cameraState.animateTo(cameraState.position.copy(target = position)) }
                    ClickResult.Consume
                },
            ) {
                if (routePoints.size >= 2) RouteLine(routePoints)
                SelectedLocationMarker(pendingCoordinate)
                activeRouteCoordinate?.let { RouteActiveMarker(it) }
            }
        }
        if (loadingState == MapLoadingState.Ready && showRouteControlPoints) {
            val controlPoints = routeWaypoints.takeIf { it.size >= 2 }
                ?: listOfNotNull(routeOrigin, routeDestination)
            val visibleControlPoints = controlPoints.dropClosingDuplicate()
            cameraState.position
            cameraState.projection?.let { projection ->
                visibleControlPoints.forEachIndexed { index, coordinate ->
                    val position = projection.screenLocationFromPosition(coordinate.toPosition())
                    RouteControlMarker(
                        label = routePointLabel(index),
                        color = when (index) {
                            0 -> BloomWalkSage
                            visibleControlPoints.lastIndex -> BloomWalkCoral
                            else -> BloomWalkGold
                        },
                        modifier = Modifier.offset(x = position.x - 14.dp, y = position.y - 14.dp),
                    )
                }
            }
        }
        when (loadingState) {
            MapLoadingState.Loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            MapLoadingState.Error -> Surface(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.map_load_failed), color = MaterialTheme.colorScheme.error)
                    Button(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.action_retry))
                    }
                }
            }
            MapLoadingState.Ready -> Unit
        }
    }
    // CameraState survives overlay updates; committing only after movement settles avoids work
    // on every drag frame and keeps pan/zoom smooth.
    androidx.compose.runtime.LaunchedEffect(cameraState.isCameraMoving) {
        if (!cameraState.isCameraMoving) onCameraIdle(cameraState.position)
    }
}

internal fun List<Coordinate>.previewCameraPosition(fallback: CameraPosition): CameraPosition {
    if (size < 2) return fallback
    val minLatitude = minOf { it.latitude }
    val maxLatitude = maxOf { it.latitude }
    val minLongitude = minOf { it.longitude }
    val maxLongitude = maxOf { it.longitude }
    val centreLatitude = (minLatitude + maxLatitude) / 2.0
    val centreLongitude = (minLongitude + maxLongitude) / 2.0
    val longitudeScale = cos(Math.toRadians(centreLatitude)).coerceAtLeast(0.2)
    val span = maxOf(
        maxLatitude - minLatitude,
        (maxLongitude - minLongitude) * longitudeScale,
    ).coerceAtLeast(0.0005)
    return fallback.copy(
        target = Position(latitude = centreLatitude, longitude = centreLongitude),
        zoom = (log2(360.0 / span) - 1.8).coerceIn(3.0, 17.0),
        tilt = 0.0,
    )
}

@Composable
private fun RouteLine(points: List<Coordinate>) {
    val geoJson = remember(points) { points.toLineStringGeoJson() }
    val data = remember(geoJson) { GeoJsonData.JsonString(geoJson) }
    val source = rememberGeoJsonSource(data)
    LaunchedEffect(source, data) { source.setData(data) }
    LineLayer(
        id = "planned-bicycle-route-casing",
        source = source,
        color = const(Color.White),
        width = const(7.dp),
    )
    LineLayer(
        id = "planned-bicycle-route",
        source = source,
        color = const(BloomWalkCoral),
        width = const(4.dp),
    )
}

@Composable
private fun RouteControlMarker(label: String, color: Color, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.size(28.dp),
        shape = CircleShape,
        color = color,
        contentColor = Color.White,
        shadowElevation = 3.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun RouteActiveMarker(coordinate: Coordinate) {
    val data = remember(coordinate) { GeoJsonData.JsonString(coordinate.toPointGeoJson()) }
    val source = rememberGeoJsonSource(data)
    LaunchedEffect(source, data) { source.setData(data) }
    CircleLayer(
        id = "route-active-position",
        source = source,
        color = const(BloomWalkSage),
        radius = const(8.dp),
        strokeColor = const(Color.White),
        strokeWidth = const(3.dp),
    )
}

@Composable
private fun SelectedLocationMarker(coordinate: Coordinate) {
    val data = remember(coordinate) { GeoJsonData.JsonString(coordinate.toPointGeoJson()) }
    val source = rememberGeoJsonSource(data)
    LaunchedEffect(source, data) { source.setData(data) }
    CircleLayer(
        id = "selected-location-halo",
        source = source,
        color = const(BloomWalkGold.copy(alpha = 0.30f)),
        radius = const(18.dp),
        strokeColor = const(Color.Black.copy(alpha = 0.8f)),
        strokeWidth = const(2.dp),
    )
    CircleLayer(
        id = "selected-location-marker",
        source = source,
        color = const(BloomWalkGold),
        radius = const(9.dp),
        strokeColor = const(Color.Black),
        strokeWidth = const(3.dp),
    )
}

@Composable
private fun rememberMapCameraState(camera: MapCamera): CameraState = rememberCameraState(
    firstPosition = CameraPosition(
        target = camera.coordinate.toPosition(),
        zoom = camera.zoom.toDouble(),
        tilt = camera.tilt.toDouble(),
        bearing = camera.bearing.toDouble(),
    ),
)

private fun Coordinate.toPosition(): Position = Position(latitude = latitude, longitude = longitude)

internal fun routePointLabel(index: Int): String {
    require(index in 0 until 26) { "Route point index must fit A-Z" }
    return ('A'.code + index).toChar().toString()
}
private fun List<Coordinate>.dropClosingDuplicate(): List<Coordinate> =
    if (size > 2 && first() == last()) dropLast(1) else this
internal fun Double.formatCoordinate(): String = String.format(Locale.US, "%.6f", this)
internal fun Double.formatDuration(): String {
    val totalMinutes = (this / 60.0).toInt().coerceAtLeast(1)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours == 0) "${minutes}m" else "${hours}h ${minutes}m"
}

private fun List<Coordinate>.toLineStringGeoJson(): String = joinToString(
    prefix = """{"type":"Feature","geometry":{"type":"LineString","coordinates":[""",
    postfix = "]}}",
    separator = ",",
) { coordinate -> "[${coordinate.longitude},${coordinate.latitude}]" }

private fun Coordinate.toPointGeoJson(): String =
    """{"type":"Feature","geometry":{"type":"Point","coordinates":[$longitude,$latitude]}}"""

private val MapDisplayType.styleUrl: String
    get() = when (this) {
        MapDisplayType.Light -> "https://tiles.openfreemap.org/styles/positron"
        MapDisplayType.Dark -> "https://tiles.openfreemap.org/styles/dark"
    }
