package com.sora.mockgps.feature.map

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sora.mockgps.R
import com.sora.mockgps.core.model.Coordinate
import com.sora.mockgps.feature.favorites.domain.FavoriteLocation
import com.sora.mockgps.route.PlannedRoute
import com.sora.mockgps.service.MockLocationForegroundService
import com.sora.mockgps.service.MockServiceState
import com.sora.mockgps.service.RouteCompleted
import com.sora.mockgps.service.RouteFailed
import com.sora.mockgps.service.RoutePaused
import com.sora.mockgps.service.RouteProgress
import com.sora.mockgps.service.RouteRunning
import com.sora.mockgps.service.RouteServiceState
import com.sora.mockgps.service.RouteStarting
import java.util.Locale
import kotlin.math.cos
import kotlin.math.log2
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.format
import org.maplibre.compose.expressions.dsl.span
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position

/** A full-screen map picker with controls kept clear of the map's centre. */
@Composable
fun MapScreen(viewModel: MapViewModel = viewModel()) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val favorites by viewModel.favorites.collectAsState()
    val savedRoutes by viewModel.savedRoutes.collectAsState()
    val recentRoutes by viewModel.recentRoutes.collectAsState()
    val serviceState by MockLocationForegroundService.state.collectAsState()
    val routeState by MockLocationForegroundService.routeState.collectAsState()
    val cameraState = rememberMapCameraState(uiState.camera)
    val coroutineScope = rememberCoroutineScope()
    var permissionMessage by remember { mutableStateOf<String?>(null) }
    var notificationPermissionHandled by rememberSaveable { mutableStateOf(false) }
    var pendingRouteStart by rememberSaveable { mutableStateOf(false) }
    var routeOptions by remember { mutableStateOf(RouteSimulationOptions()) }
    var saveFavoriteCoordinate by remember { mutableStateOf<Coordinate?>(null) }
    var showFavorites by remember { mutableStateOf(false) }
    var renameFavorite by remember { mutableStateOf<FavoriteLocation?>(null) }
    var deleteFavorite by remember { mutableStateOf<FavoriteLocation?>(null) }
    var showRouteLibrary by remember { mutableStateOf(false) }
    var saveRouteName by remember { mutableStateOf(false) }
    var pendingRouteExport by remember { mutableStateOf<RouteExport?>(null) }

    val createRouteFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val export = pendingRouteExport
        pendingRouteExport = null
        if (uri != null && export != null) {
            runCatching { context.writeText(uri, export.content) }
                .onFailure { Toast.makeText(context, it.message ?: "Unable to export route.", Toast.LENGTH_LONG).show() }
        }
        viewModel.consumeRouteOperationResult()
    }
    val importGpxLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { selected ->
            runCatching { context.readText(selected) }
                .onSuccess(viewModel::importGpx)
                .onFailure { Toast.makeText(context, it.message ?: "Unable to read GPX.", Toast.LENGTH_LONG).show() }
        }
    }
    val importBackupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { selected ->
            runCatching { context.readText(selected) }
                .onSuccess { viewModel.restoreRouteBackup(it) }
                .onFailure { Toast.makeText(context, it.message ?: "Unable to read backup.", Toast.LENGTH_LONG).show() }
        }
    }

    val locationPermissionRequired = stringResource(R.string.location_permission_required)
    val notificationPermissionDenied = stringResource(R.string.notification_permission_denied)
    val serviceStartFailed = stringResource(R.string.foreground_service_start_failed)
    val developerOptionsUnavailable = stringResource(R.string.developer_options_unavailable)
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val shouldStartRoute = pendingRouteStart
        pendingRouteStart = false
        notificationPermissionHandled = true
        if (!context.hasLocationPermission()) {
            permissionMessage = locationPermissionRequired
        } else {
            permissionMessage = if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !context.isGranted(Manifest.permission.POST_NOTIFICATIONS)
            ) notificationPermissionDenied else null
            if (!shouldStartRoute) {
                context.startMockService(uiState.pendingCoordinate) { permissionMessage = serviceStartFailed }
            } else {
                val route = uiState.plannedRoute?.points
                if (route == null) {
                    permissionMessage = serviceStartFailed
                } else {
                    context.startRouteService(route, routeOptions) { permissionMessage = serviceStartFailed }
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
    val serviceStateText = when (val currentRouteState = routeState) {
        is RouteStarting -> stringResource(R.string.route_state_starting)
        is RouteRunning -> stringResource(R.string.route_state_running)
        is RoutePaused -> stringResource(R.string.route_state_paused)
        is RouteCompleted -> stringResource(R.string.route_state_completed)
        is RouteFailed -> stringResource(R.string.route_state_failed, currentRouteState.message)
        RouteServiceState.Idle -> when (val state = serviceState) {
        MockServiceState.Idle -> stringResource(R.string.state_idle)
        is MockServiceState.Starting -> stringResource(R.string.state_starting)
        is MockServiceState.Active -> stringResource(R.string.state_active)
        is MockServiceState.Error -> stringResource(R.string.state_error, state.message)
        }
    }
    val routeProgress = routeState.progressOrNull()
    val activeCoordinate = routeProgress?.coordinate ?: (serviceState as? MockServiceState.Active)?.coordinate
    val favoriteSavedMessage = uiState.favoriteMessage?.let {
        stringResource(R.string.favorite_saved, it)
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

    LaunchedEffect(uiState.plannedRoute) {
        uiState.plannedRoute?.points?.let { points ->
            cameraState.animateTo(points.previewCameraPosition(cameraState.position))
        }
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
            onSelect = { favorite ->
                showFavorites = false
                val favoriteCoordinate = Coordinate(favorite.latitude, favorite.longitude)
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
            onRename = { renameFavorite = it },
            onDelete = { deleteFavorite = it },
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
            onDelete = { viewModel.deleteSavedRoute(it.id) },
            onImportGpx = { importGpxLauncher.launch(arrayOf("application/gpx+xml", "text/xml", "application/xml")) },
            onImportBackup = { importBackupLauncher.launch(arrayOf("application/json")) },
            onExportBackup = viewModel::exportRouteBackup,
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

    BackHandler(
        enabled = uiState.isRoutePlanningMode &&
            !isRouteSession && !showFavorites && renameFavorite == null && deleteFavorite == null,
        onBack = viewModel::navigateBackRoutePlanning,
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
            routePoints = uiState.plannedRoute?.points.orEmpty(),
            routeOrigin = uiState.routeOrigin,
            routeDestination = uiState.routeDestination,
            routeWaypoints = uiState.routeWaypoints,
            activeRouteCoordinate = activeCoordinate.takeIf { isRouteSession },
            cameraState = cameraState,
            onMapLoaded = viewModel::onMapLoaded,
            onMapLoadFailed = viewModel::onMapLoadFailed,
            onCameraIdle = viewModel::onCameraIdle,
            onRetry = viewModel::retryMap,
        )
        MapHeader(
            title = stringResource(R.string.map_title),
            serviceState = serviceStateText,
            mapType = uiState.mapType,
            mapControlsEnabled = isMapReady,
            onToggleMapType = viewModel::toggleMapType,
            onOpenDeveloperOptions = {
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                }.onFailure { permissionMessage = developerOptionsUnavailable }
            },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
        MapControlPanel(
            pendingCoordinate = uiState.pendingCoordinate,
            activeCoordinate = activeCoordinate,
            permissionMessage = permissionMessage,
            compactLayout = compactLayout,
            panelMaxWidth = panelMaxWidth,
            isMapReady = isMapReady,
            isStarting = isStarting,
            isActive = isActive,
            isRouteSession = isRouteSession,
            routePaused = isRoutePaused,
            routeProgress = routeProgress,
            routeResult = routeState.takeIf { it is RouteCompleted || it is RouteFailed },
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
            onSaveFavorite = { saveFavoriteCoordinate = uiState.pendingCoordinate },
            onShowFavorites = { showFavorites = true },
            onShowRouteLibrary = { showRouteLibrary = true },
            onSaveRoute = { saveRouteName = true },
            onExportGpx = viewModel::exportPlannedRouteGpx,
            onBeginRoutePlanning = viewModel::beginRoutePlanning,
            onSetRouteOrigin = { viewModel.setRouteOrigin(uiState.pendingCoordinate) },
            onSetRouteDestination = { viewModel.setRouteDestination(uiState.pendingCoordinate) },
            onPlanRoute = viewModel::planBicycleRoute,
            onEditRouteOrigin = viewModel::editRouteOrigin,
            onEditRouteDestination = viewModel::editRouteDestination,
            onAddRouteWaypoint = { viewModel.addRouteWaypoint(uiState.pendingCoordinate) },
            onRemoveRouteWaypoint = viewModel::removeRouteWaypoint,
            onMoveRouteWaypoint = viewModel::moveRouteWaypoint,
            onSwapRouteEndpoints = viewModel::swapRouteEndpoints,
            onClearRoute = viewModel::clearRoute,
            onStart = {
                pendingRouteStart = false
                val permissions = context.requiredRuntimePermissions(notificationPermissionHandled)
                if (permissions.isEmpty()) {
                    context.startMockService(uiState.pendingCoordinate) { permissionMessage = serviceStartFailed }
                } else {
                    permissionLauncher.launch(permissions.toTypedArray())
                }
            },
            onStartRoute = {
                uiState.plannedRoute?.points?.let { points ->
                    viewModel.recordPlannedRouteAsRecent()
                    val permissions = context.requiredRuntimePermissions(notificationPermissionHandled)
                    if (permissions.isEmpty()) {
                        context.startRouteService(points, routeOptions) { permissionMessage = serviceStartFailed }
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

@Composable
private fun MapHeader(
    title: String,
    serviceState: String,
    mapType: MapDisplayType,
    mapControlsEnabled: Boolean,
    onToggleMapType: () -> Unit,
    onOpenDeveloperOptions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.widthIn(max = 560.dp).fillMaxWidth().shadow(8.dp, MaterialTheme.shapes.large),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(
                        serviceState,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(
                    onClick = onToggleMapType,
                    enabled = mapControlsEnabled,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(
                        stringResource(
                            if (mapType == MapDisplayType.Light) R.string.action_dark_map
                            else R.string.action_light_map,
                        ),
                    )
                }
                TextButton(
                    onClick = onOpenDeveloperOptions,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.action_developer_settings)) }
            }
        }
    }
}

@Composable
private fun MapControlPanel(
    pendingCoordinate: Coordinate,
    activeCoordinate: Coordinate?,
    permissionMessage: String?,
    compactLayout: Boolean,
    panelMaxWidth: Dp,
    isMapReady: Boolean,
    isStarting: Boolean,
    isActive: Boolean,
    isRouteSession: Boolean,
    routePaused: Boolean,
    routeProgress: RouteProgress?,
    routeResult: RouteServiceState?,
    routeOptions: RouteSimulationOptions,
    onRouteOptionsChange: (RouteSimulationOptions) -> Unit,
    favoritesCount: Int,
    routePlanningStep: RoutePlanningStep,
    routeOrigin: Coordinate?,
    routeDestination: Coordinate?,
    routeWaypoints: List<Coordinate>,
    plannedRoute: PlannedRoute?,
    isPlanningRoute: Boolean,
    routeError: String?,
    onSaveFavorite: () -> Unit,
    onShowFavorites: () -> Unit,
    onShowRouteLibrary: () -> Unit,
    onSaveRoute: () -> Unit,
    onExportGpx: () -> Unit,
    onBeginRoutePlanning: () -> Unit,
    onSetRouteOrigin: () -> Unit,
    onSetRouteDestination: () -> Unit,
    onPlanRoute: () -> Unit,
    onEditRouteOrigin: () -> Unit,
    onEditRouteDestination: () -> Unit,
    onAddRouteWaypoint: () -> Unit,
    onRemoveRouteWaypoint: (Int) -> Unit,
    onMoveRouteWaypoint: (Int, Int) -> Unit,
    onSwapRouteEndpoints: () -> Unit,
    onClearRoute: () -> Unit,
    onStart: () -> Unit,
    onStartRoute: () -> Unit,
    onPauseResumeRoute: () -> Unit,
    onApply: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    Surface(
        modifier = modifier
            .widthIn(max = panelMaxWidth)
            .fillMaxWidth()
            .shadow(10.dp, MaterialTheme.shapes.large),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = if (compactLayout) 360.dp else 520.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            activeCoordinate?.let {
                Text(
                    stringResource(R.string.active_coordinate, it.latitude.formatCoordinate(), it.longitude.formatCoordinate()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            permissionMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            routeError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (routePlanningStep == RoutePlanningStep.Inactive) {
                Text(
                    stringResource(
                        R.string.selected_coordinate,
                        pendingCoordinate.latitude.formatCoordinate(),
                        pendingCoordinate.longitude.formatCoordinate(),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = onSaveFavorite, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.action_save_place), maxLines = 1)
                    }
                    TextButton(onClick = onShowFavorites, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.action_favorites, favoritesCount), maxLines = 1)
                    }
                }
                TextButton(onClick = onShowRouteLibrary, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.action_route_library))
                }
                if (!isStarting && !isActive) {
                    Button(
                        onClick = onStart,
                        enabled = isMapReady,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.action_start_mock), maxLines = 1) }
                    OutlinedButton(
                        onClick = onBeginRoutePlanning,
                        enabled = isMapReady,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.action_plan_bicycle_route), maxLines = 1) }
                }
                if (isActive && !isRouteSession && activeCoordinate != pendingCoordinate) {
                    Button(onClick = onApply, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.action_apply_new_location), maxLines = 1)
                    }
                }
                if (isStarting || isActive) {
                    OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.action_stop), maxLines = 1)
                    }
                }
                if (!compactLayout) {
                    Text(
                        stringResource(R.string.mock_app_setup_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.route_panel_title), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.route_panel_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!isStarting && !isActive) {
                        TextButton(onClick = onClearRoute, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    }
                }

                when (routePlanningStep) {
                    RoutePlanningStep.SelectStart -> {
                        RouteEndpointSummary(
                            label = stringResource(R.string.route_choose_start),
                            coordinate = pendingCoordinate,
                            supportingText = stringResource(R.string.route_move_reticle_start),
                        )
                        Button(
                            onClick = onSetRouteOrigin,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) { Text(stringResource(R.string.action_use_as_route_start), maxLines = 1) }
                        OutlinedButton(
                            onClick = onShowFavorites,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) { Text(stringResource(R.string.action_choose_start_favorite), maxLines = 1) }
                    }
                    RoutePlanningStep.SelectDestination -> {
                        RouteEndpointSummary(
                            label = stringResource(R.string.route_start_label),
                            coordinate = requireNotNull(routeOrigin),
                        )
                        RouteEndpointSummary(
                            label = stringResource(R.string.route_choose_destination),
                            coordinate = pendingCoordinate,
                            supportingText = stringResource(R.string.route_move_reticle_destination),
                        )
                        Button(
                            onClick = onSetRouteDestination,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) { Text(stringResource(R.string.action_use_as_route_destination), maxLines = 1) }
                        OutlinedButton(
                            onClick = onShowFavorites,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) { Text(stringResource(R.string.action_choose_destination_favorite), maxLines = 1) }
                    }
                    RoutePlanningStep.ReadyToPreview, RoutePlanningStep.Planning -> {
                        RouteEndpointSummary(stringResource(R.string.route_start_label), requireNotNull(routeOrigin))
                        RouteEndpointSummary(stringResource(R.string.route_destination_label), requireNotNull(routeDestination))
                        RouteWaypointEditor(routeWaypoints, onRemoveRouteWaypoint, onMoveRouteWaypoint)
                        Text(
                            stringResource(R.string.route_provider_notice),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = onPlanRoute,
                            enabled = !isPlanningRoute,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            if (isPlanningRoute) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Text(stringResource(R.string.action_preview_bicycle_route), maxLines = 1)
                            }
                        }
                        if (!isPlanningRoute) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = onAddRouteWaypoint, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                    Text(stringResource(R.string.action_add_route_stop))
                                }
                                TextButton(onClick = onSwapRouteEndpoints, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                    Text(stringResource(R.string.action_swap_endpoints))
                                }
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = onEditRouteOrigin, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                    Text(stringResource(R.string.action_reset_route_start), maxLines = 1)
                                }
                                TextButton(onClick = onEditRouteDestination, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                    Text(stringResource(R.string.action_change_destination), maxLines = 1)
                                }
                            }
                        } else {
                            Text(stringResource(R.string.route_planning), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    RoutePlanningStep.Preview -> {
                        plannedRoute?.let { route ->
                            Text(
                                stringResource(
                                    R.string.route_summary,
                                    String.format(Locale.US, "%.2f", route.distanceMeters / 1_000.0),
                                    String.format(Locale.US, "%.1f", routeOptions.speedKilometersPerHour),
                                    (route.distanceMeters / (routeOptions.speedKilometersPerHour / 3.6)).formatDuration(),
                                ),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (!isRouteSession) {
                            RouteSimulationControls(
                                options = routeOptions,
                                onOptionsChange = onRouteOptionsChange,
                            )
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = onAddRouteWaypoint, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                    Text(stringResource(R.string.action_add_route_stop))
                                }
                                TextButton(onClick = onSwapRouteEndpoints, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                    Text(stringResource(R.string.action_swap_endpoints))
                                }
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = onSaveRoute, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                    Text(stringResource(R.string.action_save_route))
                                }
                                TextButton(onClick = onShowRouteLibrary, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                    Text(stringResource(R.string.action_route_library))
                                }
                                TextButton(onClick = onExportGpx, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                    Text("GPX")
                                }
                            }
                        }
                        routeProgress?.let { progress ->
                            RouteProgressSummary(progress = progress, paused = routePaused)
                        }
                        when (routeResult) {
                            is RouteCompleted -> Text(
                                stringResource(R.string.route_completed_message),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            is RouteFailed -> Text(
                                stringResource(R.string.route_failed_message, routeResult.message),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            else -> Unit
                        }
                        if (!isStarting && !isActive) {
                            Button(
                                onClick = onStartRoute,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            ) { Text(stringResource(R.string.action_start_route_simulation), maxLines = 1) }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = onEditRouteDestination, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                    Text(stringResource(R.string.action_change_destination), maxLines = 1)
                                }
                                TextButton(onClick = onClearRoute, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                    Text(stringResource(R.string.action_clear_route), maxLines = 1)
                                }
                            }
                        }
                        if (isStarting) {
                            Text(stringResource(R.string.state_starting), style = MaterialTheme.typography.bodySmall)
                        }
                        if (isActive && isRouteSession) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = onPauseResumeRoute,
                                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                                ) {
                                    Text(stringResource(if (routePaused) R.string.action_resume_route else R.string.action_pause_route))
                                }
                                OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                    Text(stringResource(R.string.action_stop))
                                }
                            }
                        }
                    }
                    RoutePlanningStep.Inactive -> Unit
                }
            }
            TextButton(
                onClick = { uriHandler.openUri("https://openfreemap.org/") },
                contentPadding = PaddingValues(horizontal = 4.dp),
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.map_provider_attribution), style = MaterialTheme.typography.labelSmall) }
            if (routePlanningStep in setOf(
                    RoutePlanningStep.ReadyToPreview,
                    RoutePlanningStep.Planning,
                    RoutePlanningStep.Preview,
                )
            ) {
                TextButton(
                    onClick = { uriHandler.openUri("https://www.openstreetmap.org/fixthemap") },
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.route_provider_attribution), style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

@Composable
private fun RouteEndpointSummary(
    label: String,
    coordinate: Coordinate,
    supportingText: String? = null,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(
                "${coordinate.latitude.formatCoordinate()}, ${coordinate.longitude.formatCoordinate()}",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            supportingText?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RouteProgressSummary(progress: RouteProgress, paused: Boolean) {
    val fraction = (progress.travelledDistanceMeters / progress.totalDistanceMeters)
        .toFloat()
        .coerceIn(0f, 1f)
    val remainingDuration = if (progress.speedMetersPerSecond > 0.0) {
        (progress.remainingDistanceMeters / progress.speedMetersPerSecond).formatDuration()
    } else {
        "—"
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                stringResource(if (paused) R.string.route_state_paused else R.string.route_state_running),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                stringResource(R.string.route_progress_percent, (fraction * 100).toInt()),
                style = MaterialTheme.typography.labelLarge,
            )
        }
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(
                R.string.route_progress_detail,
                String.format(Locale.US, "%.2f", progress.travelledDistanceMeters / 1_000.0),
                String.format(Locale.US, "%.2f", progress.remainingDistanceMeters / 1_000.0),
                remainingDuration,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RouteWaypointEditor(
    waypoints: List<Coordinate>,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
) {
    waypoints.drop(1).dropLast(1).forEachIndexed { visibleIndex, coordinate ->
        val routeIndex = visibleIndex + 1
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.route_stop_label, visibleIndex + 1), style = MaterialTheme.typography.labelLarge)
                    Text(
                        "${coordinate.latitude.formatCoordinate()}, ${coordinate.longitude.formatCoordinate()}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(
                    onClick = { onMove(routeIndex, -1) },
                    enabled = routeIndex > 1,
                ) { Text("↑") }
                TextButton(
                    onClick = { onMove(routeIndex, 1) },
                    enabled = routeIndex < waypoints.lastIndex - 1,
                ) { Text("↓") }
                TextButton(onClick = { onRemove(routeIndex) }) { Text("×") }
            }
        }
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
    routePoints: List<Coordinate>,
    routeOrigin: Coordinate?,
    routeDestination: Coordinate?,
    routeWaypoints: List<Coordinate>,
    activeRouteCoordinate: Coordinate?,
    cameraState: CameraState,
    onMapLoaded: () -> Unit,
    onMapLoadFailed: () -> Unit,
    onCameraIdle: (CameraPosition) -> Unit,
    onRetry: () -> Unit,
) {
    val mapDescription = stringResource(R.string.map_picker_description)
    Box(modifier = modifier.semantics { contentDescription = mapDescription }) {
        key(mapRenderKey) {
            MaplibreMap(
                modifier = Modifier.fillMaxSize(),
                baseStyle = BaseStyle.Uri(mapType.styleUrl),
                cameraState = cameraState,
                onMapLoadFinished = onMapLoaded,
                onMapLoadFailed = { onMapLoadFailed() },
            ) {
                if (routePoints.size >= 2) RouteLine(routePoints)
                routeOrigin?.let { RouteEndpointMarker("route-start", "A", it, Color(0xFF2E7D32)) }
                routeDestination?.let { RouteEndpointMarker("route-destination", "B", it, Color(0xFFC62828)) }
                routeWaypoints.drop(1).dropLast(1).forEachIndexed { index, coordinate ->
                    RouteEndpointMarker("route-waypoint-$index", "${index + 1}", coordinate, Color(0xFF6A1B9A))
                }
                activeRouteCoordinate?.let { RouteActiveMarker(it) }
            }
        }
        CenterReticle(modifier = Modifier.align(Alignment.Center))
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
    val source = rememberGeoJsonSource(GeoJsonData.JsonString(geoJson))
    LineLayer(
        id = "planned-bicycle-route-casing",
        source = source,
        color = const(Color.White),
        width = const(7.dp),
    )
    LineLayer(
        id = "planned-bicycle-route",
        source = source,
        color = const(Color(0xFF6750A4)),
        width = const(4.dp),
    )
}

@Composable
private fun RouteEndpointMarker(id: String, label: String, coordinate: Coordinate, color: Color) {
    val source = rememberGeoJsonSource(GeoJsonData.JsonString(coordinate.toPointGeoJson()))
    CircleLayer(
        id = "$id-circle",
        source = source,
        color = const(color),
        radius = const(12.dp),
        strokeColor = const(Color.White),
        strokeWidth = const(3.dp),
    )
    SymbolLayer(
        id = "$id-label",
        source = source,
        textField = format(span(label)),
        textColor = const(Color.White),
        textSize = const(12.sp),
        textAllowOverlap = const(true),
        textIgnorePlacement = const(true),
    )
}

@Composable
private fun RouteActiveMarker(coordinate: Coordinate) {
    val source = rememberGeoJsonSource(GeoJsonData.JsonString(coordinate.toPointGeoJson()))
    CircleLayer(
        id = "route-active-position",
        source = source,
        color = const(Color(0xFF1565C0)),
        radius = const(8.dp),
        strokeColor = const(Color.White),
        strokeWidth = const(3.dp),
    )
}

@Composable
private fun CenterReticle(modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val reticleDescription = stringResource(R.string.map_reticle_description)
    Canvas(
        modifier = modifier.size(42.dp).semantics { contentDescription = reticleDescription },
    ) {
        val center = this.center
        val lineLength = 18.dp.toPx()
        val shadow = Color.Black.copy(alpha = 0.52f)
        drawCircle(shadow, radius = 10.dp.toPx(), style = Stroke(width = 5.dp.toPx()))
        drawLine(shadow, androidx.compose.ui.geometry.Offset(center.x - lineLength, center.y), androidx.compose.ui.geometry.Offset(center.x + lineLength, center.y), 5.dp.toPx())
        drawLine(shadow, androidx.compose.ui.geometry.Offset(center.x, center.y - lineLength), androidx.compose.ui.geometry.Offset(center.x, center.y + lineLength), 5.dp.toPx())
        drawCircle(Color.White, radius = 10.dp.toPx(), style = Stroke(width = 2.dp.toPx()))
        drawLine(Color.White, androidx.compose.ui.geometry.Offset(center.x - lineLength, center.y), androidx.compose.ui.geometry.Offset(center.x + lineLength, center.y), 2.dp.toPx())
        drawLine(Color.White, androidx.compose.ui.geometry.Offset(center.x, center.y - lineLength), androidx.compose.ui.geometry.Offset(center.x, center.y + lineLength), 2.dp.toPx())
        drawCircle(accent, radius = 3.dp.toPx())
    }
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
private fun Double.formatCoordinate(): String = String.format(Locale.US, "%.6f", this)
private fun Double.formatDuration(): String {
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

private fun Context.requiredRuntimePermissions(notificationPermissionHandled: Boolean): List<String> = buildList {
    if (!hasLocationPermission()) {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        !notificationPermissionHandled && !isGranted(Manifest.permission.POST_NOTIFICATIONS)
    ) add(Manifest.permission.POST_NOTIFICATIONS)
}

private fun Context.hasLocationPermission(): Boolean =
    isGranted(Manifest.permission.ACCESS_FINE_LOCATION) || isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)

private fun Context.startMockService(coordinate: Coordinate, onFailure: () -> Unit) {
    runCatching { startForegroundService(MockLocationForegroundService.startIntent(this, coordinate)) }
        .onFailure { onFailure() }
}

private fun Context.startRouteService(
    points: List<Coordinate>,
    options: RouteSimulationOptions,
    onFailure: () -> Unit,
) {
    runCatching {
        startForegroundService(
            MockLocationForegroundService.startRouteIntent(
                context = this,
                points = points,
                movementProfile = options.movementProfile(),
                accelerationModel = options.accelerationModel(),
                executionMode = options.mode,
                gpsDrift = options.gpsDrift(),
            ),
        )
    }.onFailure { onFailure() }
}

private fun Context.isGranted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private fun Context.readText(uri: Uri): String = contentResolver.openInputStream(uri)?.bufferedReader()?.use {
    it.readText()
} ?: error("The selected file could not be opened.")

private fun Context.writeText(uri: Uri, content: String) {
    val stream = contentResolver.openOutputStream(uri, "wt") ?: error("The selected file could not be written.")
    stream.bufferedWriter().use { it.write(content) }
}
