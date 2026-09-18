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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sora.mockgps.R
import com.sora.mockgps.core.model.Coordinate
import com.sora.mockgps.feature.favorites.domain.FavoriteLocation
import com.sora.mockgps.feature.routes.domain.SavedRouteSummary
import com.sora.mockgps.route.JoystickSpeed
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.OrnamentOptions
import org.maplibre.compose.expressions.dsl.Feature
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.format
import org.maplibre.compose.expressions.dsl.offset
import org.maplibre.compose.expressions.dsl.span
import org.maplibre.compose.expressions.value.StringValue
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.ClickResult
import org.maplibre.spatialk.geojson.Position

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
    var pendingAutoJourney by remember { mutableStateOf<AutoJourneyOptions?>(null) }
    var isResolvingCurrentLocation by remember { mutableStateOf(false) }
    var routeOptions by rememberSaveable(stateSaver = RouteSimulationOptionsSaver) {
        mutableStateOf(RouteSimulationOptions())
    }
    var saveFavoriteCoordinate by remember { mutableStateOf<Coordinate?>(null) }
    var showFavorites by remember { mutableStateOf(false) }
    var showSetupGuide by remember { mutableStateOf(false) }
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
    var showLandmarks by rememberSaveable { mutableStateOf(true) }
    var confirmClearFavorites by remember { mutableStateOf(false) }
    var confirmClearRecentLocations by remember { mutableStateOf(false) }
    var confirmClearRecents by remember { mutableStateOf(false) }
    var joystickSpeed by rememberSaveable { mutableStateOf(JoystickSpeed.Walk) }
    var joystickMagnitude by rememberSaveable { mutableFloatStateOf(0f) }
    var lastJoystickBearing by rememberSaveable { mutableFloatStateOf(0f) }
    var joystickMode by rememberSaveable { mutableStateOf(false) }

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
    val importFavoritesBackupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { selected ->
            runCatching { context.readText(selected) }
                .onSuccess { viewModel.restoreFavoritesBackup(it) }
                .onFailure { Toast.makeText(context, readBackupFailed, Toast.LENGTH_LONG).show() }
        }
    }

    val locationPermissionRequired = stringResource(R.string.location_permission_required)
    val notificationPermissionDenied = stringResource(R.string.notification_permission_denied)
    val serviceStartNotAllowed = stringResource(R.string.foreground_service_start_not_allowed)
    val serviceStartSecurityFailed = stringResource(R.string.foreground_service_security_failed)
    val serviceStartFailed = stringResource(R.string.foreground_service_start_failed)
    val developerOptionsUnavailable = stringResource(R.string.developer_options_unavailable)
    val batterySettingsUnavailable = stringResource(R.string.battery_settings_unavailable)
    fun openDeveloperOptions() {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        }.onFailure { permissionMessage = developerOptionsUnavailable }
    }
    fun openBatterySettings() {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }.onFailure { permissionMessage = batterySettingsUnavailable }
    }

    LaunchedEffect(uiState.settingsReady, uiState.setupGuideDismissed) {
        if (uiState.settingsReady && !uiState.setupGuideDismissed) {
            showSetupGuide = true
        }
    }
    fun resolveCurrentLocation(onResolved: (Coordinate) -> Unit) {
        if (!context.hasLocationPermission()) {
            permissionMessage = locationPermissionRequired
            return
        }
        permissionMessage = null
        isResolvingCurrentLocation = true
        coroutineScope.launch {
            val coordinate = runCatching { context.resolveCurrentCoordinate() }.getOrNull()
            isResolvingCurrentLocation = false
            if (coordinate == null) {
                permissionMessage = currentLocationUnavailable
            } else {
                onResolved(coordinate)
            }
        }
    }
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
        val autoJourney = pendingAutoJourney
        pendingRouteStart = false
        pendingCurrentLocation = false
        pendingAutoJourney = null
        notificationPermissionHandled = true
        if (!context.hasLocationPermission()) {
            permissionMessage = locationPermissionRequired
        } else {
            permissionMessage = if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !context.isGranted(Manifest.permission.POST_NOTIFICATIONS)
            ) notificationPermissionDenied else null
            if (autoJourney != null) {
                resolveCurrentLocation { coordinate ->
                    viewModel.generateAutomaticJourney(autoJourney.copy(centerCoordinate = coordinate))
                }
            } else if (shouldUseCurrentLocation) {
                resolveCurrentLocation { coordinate ->
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
    val showJoystick = joystickMode && isActive && !isRouteSession
    val sessionToken = routeState.sessionToken
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

    LaunchedEffect(isRouteSession) {
        if (isRouteSession && joystickMode) joystickMode = false
    }

    LaunchedEffect(showJoystick) {
        if (showJoystick) {
            context.setJoystickVector(lastJoystickBearing, joystickMagnitude, joystickSpeed, sessionToken)
        } else if (joystickMagnitude > 0f) {
            joystickMagnitude = 0f
            context.setJoystickVector(lastJoystickBearing, 0f, joystickSpeed, sessionToken)
        }
    }

    LaunchedEffect(activeCoordinate, joystickMagnitude) {
        if (joystickMagnitude > 0f) {
            activeCoordinate?.let(viewModel::selectCoordinate)
        }
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
    LaunchedEffect(serviceState, routeState, joystickMagnitude) {
        if (joystickMagnitude > 0f) return@LaunchedEffect
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
                openDeveloperOptions()
                MockLocationForegroundService.consumeError()
            },
        )
    }

    if (showSetupGuide) {
        SetupGuideDialog(
            onOpenDeveloperOptions = { openDeveloperOptions() },
            onOpenBatterySettings = { openBatterySettings() },
            onDismiss = {
                showSetupGuide = false
                viewModel.dismissSetupGuide()
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
            onExportBackup = {
                showFavorites = false
                viewModel.exportFavoritesBackup()
            },
            onImportBackup = {
                showFavorites = false
                importFavoritesBackupLauncher.launch(arrayOf("application/json"))
            },
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
            if (options.region != JourneyRegion.CurrentLocation) {
                viewModel.generateAutomaticJourney(options)
            } else if (!context.hasLocationPermission()) {
                pendingAutoJourney = options
                permissionLauncher.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                )
            } else {
                resolveCurrentLocation { coordinate ->
                    viewModel.generateAutomaticJourney(options.copy(centerCoordinate = coordinate))
                }
            }
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
            showLandmarks = showLandmarks,
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
        val goToCurrentLocation: () -> Unit = {
            if (!context.hasLocationPermission()) {
                pendingCurrentLocation = true
                permissionLauncher.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                )
            } else {
                resolveCurrentLocation { coordinate ->
                    viewModel.selectCoordinate(coordinate)
                    coroutineScope.launch {
                        cameraState.animateTo(cameraState.position.copy(target = coordinate.toPosition()))
                    }
                }
            }
        }
        val currentLocationLabel = stringResource(R.string.action_use_current_location)
        if (showJoystick) {
            JoystickOverlay(
                enabled = true,
                selectedSpeed = joystickSpeed,
                onSpeedChange = { speed ->
                    joystickSpeed = speed
                    if (joystickMagnitude > 0f) {
                        context.setJoystickVector(
                            lastJoystickBearing,
                            joystickMagnitude,
                            speed,
                            sessionToken,
                        )
                    }
                },
                onVectorChange = { bearing, magnitude ->
                    lastJoystickBearing = bearing
                    joystickMagnitude = magnitude
                    context.setJoystickVector(bearing, magnitude, joystickSpeed, sessionToken)
                },
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(
                        start = 16.dp,
                        bottom = if (compactLayout) 24.dp else 156.dp,
                    ),
            )
        }
        Surface(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(
                    end = if (compactLayout) (panelMaxWidth + 20.dp) else 16.dp,
                    bottom = if (compactLayout) 24.dp else 156.dp,
                ),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
            tonalElevation = 4.dp,
            shadowElevation = 6.dp,
        ) {
            IconButton(
                onClick = goToCurrentLocation,
                enabled = !isResolvingCurrentLocation,
                modifier = Modifier
                    .size(48.dp)
                    .semantics { contentDescription = currentLocationLabel },
            ) {
                if (isResolvingCurrentLocation) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        imageVector = Icons.Filled.LocationOn,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
        MapControlPanel(
            pendingCoordinate = uiState.pendingCoordinate,
            activeCoordinate = activeCoordinate,
            showCoordinates = uiState.showCoordinates,
            showLandmarks = showLandmarks,
            permissionMessage = serviceStartErrorMessage ?: permissionMessage,
            isResolvingCurrentLocation = isResolvingCurrentLocation,
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
            automaticJourneyRecoveryKind = uiState.automaticJourneyRecoveryKind,
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
            onShowLandmarksChange = { showLandmarks = it },
            updateIntervalMillis = uiState.updateIntervalMillis,
            accuracyMeters = uiState.accuracyMeters,
            onCycleUpdateInterval = viewModel::cycleUpdateIntervalMillis,
            onCycleAccuracy = viewModel::cycleAccuracyMeters,
            onToggleMapType = viewModel::toggleMapType,
            onOpenDeveloperOptions = { openDeveloperOptions() },
            onOpenBatterySettings = { openBatterySettings() },
            onShowSetupGuide = { showSetupGuide = true },
            onUseCurrentLocation = goToCurrentLocation,
            onSaveFavorite = { saveFavoriteCoordinate = uiState.pendingCoordinate },
            onShowFavorites = { showFavorites = true },
            onShowRouteLibrary = { showRouteLibrary = true },
            onSaveRoute = { saveRouteName = true },
            onExportGpx = viewModel::exportPlannedRouteGpx,
            onBeginRoutePlanning = {
                joystickMode = false
                viewModel.beginRoutePlanning()
            },
            onShowAutoJourney = {
                joystickMode = false
                showAutoJourney = true
            },
            onRegenerateAutomaticJourney = viewModel::regenerateAutomaticJourney,
            onShowShapeRoute = {
                joystickMode = false
                showShapeRoute = true
            },
            joystickMode = joystickMode,
            joystickSpeed = joystickSpeed,
            onJoystickSpeedChange = { speed ->
                joystickSpeed = speed
                if (showJoystick && joystickMagnitude > 0f) {
                    context.setJoystickVector(lastJoystickBearing, joystickMagnitude, speed, sessionToken)
                }
            },
            onStartJoystick = {
                joystickMode = true
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
            onEnableJoystick = { joystickMode = true },
            onExitJoystick = {
                if (joystickMagnitude > 0f) {
                    context.setJoystickVector(lastJoystickBearing, 0f, joystickSpeed, sessionToken)
                    joystickMagnitude = 0f
                }
                joystickMode = false
            },
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
                joystickMode = false
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
                joystickMode = false
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

