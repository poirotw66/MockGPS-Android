package com.sora.mockgps.feature.map

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sora.mockgps.R
import com.sora.mockgps.core.model.Coordinate
import com.sora.mockgps.feature.favorites.domain.FavoriteLocation
import com.sora.mockgps.route.PlannedRoute
import com.sora.mockgps.service.MockLocationForegroundService
import com.sora.mockgps.service.MockServiceState
import java.util.Locale
import kotlin.math.cos
import kotlin.math.log2
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.LineLayer
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
    val serviceState by MockLocationForegroundService.state.collectAsState()
    val cameraState = rememberMapCameraState(uiState.camera)
    val coroutineScope = rememberCoroutineScope()
    var permissionMessage by remember { mutableStateOf<String?>(null) }
    var notificationPermissionHandled by rememberSaveable { mutableStateOf(false) }
    var routePaused by rememberSaveable { mutableStateOf(false) }
    var pendingRouteStart by rememberSaveable { mutableStateOf(false) }
    var saveFavoriteCoordinate by remember { mutableStateOf<Coordinate?>(null) }
    var showFavorites by remember { mutableStateOf(false) }
    var renameFavorite by remember { mutableStateOf<FavoriteLocation?>(null) }
    var deleteFavorite by remember { mutableStateOf<FavoriteLocation?>(null) }

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
                    context.startRouteService(route) { permissionMessage = serviceStartFailed }
                }
            }
        }
    }

    val isStarting = serviceState is MockServiceState.Starting
    val isActive = serviceState is MockServiceState.Active
    val isMapReady = uiState.loadingState == MapLoadingState.Ready
    val serviceStateText = when (val state = serviceState) {
        MockServiceState.Idle -> stringResource(R.string.state_idle)
        is MockServiceState.Starting -> stringResource(R.string.state_starting)
        is MockServiceState.Active -> stringResource(R.string.state_active)
        is MockServiceState.Error -> stringResource(R.string.state_error, state.message)
    }
    val activeCoordinate = (serviceState as? MockServiceState.Active)?.coordinate
    val isRouteSession = uiState.plannedRoute != null && (isStarting || isActive)
    val favoriteSavedMessage = uiState.favoriteMessage?.let {
        stringResource(R.string.favorite_saved, it)
    }

    LaunchedEffect(favoriteSavedMessage) {
        favoriteSavedMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.consumeFavoriteMessage()
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
            routePaused = routePaused,
            favoritesCount = favorites.size,
            routePlanningStep = uiState.routePlanningStep,
            routeOrigin = uiState.routeOrigin,
            routeDestination = uiState.routeDestination,
            plannedRoute = uiState.plannedRoute,
            isPlanningRoute = uiState.isPlanningRoute,
            routeError = uiState.routeError,
            onSaveFavorite = { saveFavoriteCoordinate = uiState.pendingCoordinate },
            onShowFavorites = { showFavorites = true },
            onBeginRoutePlanning = viewModel::beginRoutePlanning,
            onSetRouteOrigin = { viewModel.setRouteOrigin(uiState.pendingCoordinate) },
            onSetRouteDestination = { viewModel.setRouteDestination(uiState.pendingCoordinate) },
            onPlanRoute = viewModel::planBicycleRoute,
            onEditRouteOrigin = viewModel::editRouteOrigin,
            onEditRouteDestination = viewModel::editRouteDestination,
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
                    val permissions = context.requiredRuntimePermissions(notificationPermissionHandled)
                    if (permissions.isEmpty()) {
                        context.startRouteService(points) { permissionMessage = serviceStartFailed }
                    } else {
                        pendingRouteStart = true
                        permissionLauncher.launch(permissions.toTypedArray())
                    }
                    routePaused = false
                }
            },
            onPauseResumeRoute = {
                context.startService(
                    if (routePaused) MockLocationForegroundService.resumeRouteIntent(context)
                    else MockLocationForegroundService.pauseRouteIntent(context),
                )
                routePaused = !routePaused
            },
            onApply = {
                context.startService(
                    MockLocationForegroundService.updateIntent(context, uiState.pendingCoordinate),
                )
            },
            onStop = {
                context.startService(MockLocationForegroundService.stopIntent(context))
                routePaused = false
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
    favoritesCount: Int,
    routePlanningStep: RoutePlanningStep,
    routeOrigin: Coordinate?,
    routeDestination: Coordinate?,
    plannedRoute: PlannedRoute?,
    isPlanningRoute: Boolean,
    routeError: String?,
    onSaveFavorite: () -> Unit,
    onShowFavorites: () -> Unit,
    onBeginRoutePlanning: () -> Unit,
    onSetRouteOrigin: () -> Unit,
    onSetRouteDestination: () -> Unit,
    onPlanRoute: () -> Unit,
    onEditRouteOrigin: () -> Unit,
    onEditRouteDestination: () -> Unit,
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
                                    route.simulatedDurationSeconds.formatDuration(),
                                ),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
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
private fun MapPicker(
    modifier: Modifier,
    mapType: MapDisplayType,
    mapRenderKey: Int,
    loadingState: MapLoadingState,
    routePoints: List<Coordinate>,
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

private fun Context.startRouteService(points: List<Coordinate>, onFailure: () -> Unit) {
    runCatching {
        startForegroundService(MockLocationForegroundService.startRouteIntent(this, points))
    }.onFailure { onFailure() }
}

private fun Context.isGranted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
