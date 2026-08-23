package com.sora.mockgps.feature.map

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sora.mockgps.R
import com.sora.mockgps.core.model.Coordinate
import com.sora.mockgps.feature.favorites.domain.FavoriteLocation
import com.sora.mockgps.route.PlannedRoute
import com.sora.mockgps.service.MockLocationForegroundService
import com.sora.mockgps.service.MockServiceState
import java.util.Locale
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
    var pendingRouteStart by remember { mutableStateOf<List<Coordinate>?>(null) }
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
        notificationPermissionHandled = true
        if (!context.hasLocationPermission()) {
            permissionMessage = locationPermissionRequired
        } else {
            permissionMessage = if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !context.isGranted(Manifest.permission.POST_NOTIFICATIONS)
            ) notificationPermissionDenied else null
            val route = pendingRouteStart
            if (route == null) {
                context.startMockService(uiState.pendingCoordinate) { permissionMessage = serviceStartFailed }
            } else {
                context.startRouteService(route) { permissionMessage = serviceStartFailed }
                pendingRouteStart = null
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
            isMapReady = isMapReady,
            isStarting = isStarting,
            isActive = isActive,
            isRouteSession = isRouteSession,
            routePaused = routePaused,
            favoritesCount = favorites.size,
            routeOrigin = uiState.routeOrigin,
            plannedRoute = uiState.plannedRoute,
            isPlanningRoute = uiState.isPlanningRoute,
            routeError = uiState.routeError,
            onSaveFavorite = { saveFavoriteCoordinate = uiState.pendingCoordinate },
            onShowFavorites = { showFavorites = true },
            onSetRouteOrigin = { viewModel.setRouteOrigin(uiState.pendingCoordinate) },
            onPlanRoute = { viewModel.planBicycleRoute(uiState.pendingCoordinate) },
            onClearRoute = viewModel::clearRoute,
            onStart = {
                pendingRouteStart = null
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
                        pendingRouteStart = points
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
                .align(Alignment.BottomCenter)
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
    isMapReady: Boolean,
    isStarting: Boolean,
    isActive: Boolean,
    isRouteSession: Boolean,
    routePaused: Boolean,
    favoritesCount: Int,
    routeOrigin: Coordinate?,
    plannedRoute: PlannedRoute?,
    isPlanningRoute: Boolean,
    routeError: String?,
    onSaveFavorite: () -> Unit,
    onShowFavorites: () -> Unit,
    onSetRouteOrigin: () -> Unit,
    onPlanRoute: () -> Unit,
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
            .widthIn(max = if (compactLayout) 440.dp else 560.dp)
            .fillMaxWidth()
            .shadow(10.dp, MaterialTheme.shapes.large),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(onClick = onSaveFavorite, enabled = !isRouteSession, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.action_save_place), maxLines = 1)
                }
                TextButton(onClick = onShowFavorites, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.action_favorites, favoritesCount), maxLines = 1)
                }
                TextButton(
                    onClick = when {
                        plannedRoute != null -> onClearRoute
                        routeOrigin == null -> onSetRouteOrigin
                        else -> onPlanRoute
                    },
                    enabled = !isStarting && !isActive && !isPlanningRoute,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        stringResource(
                            when {
                                plannedRoute != null -> R.string.action_clear_route
                                routeOrigin == null -> R.string.action_set_route_start
                                else -> R.string.action_plan_bicycle_route
                            },
                        ),
                        maxLines = 1,
                    )
                }
            }
            routeOrigin?.takeIf { plannedRoute == null }?.let { origin ->
                Text(
                    stringResource(
                        R.string.route_start_selected,
                        origin.latitude.formatCoordinate(),
                        origin.longitude.formatCoordinate(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isPlanningRoute) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.route_planning), style = MaterialTheme.typography.bodySmall)
                }
            }
            plannedRoute?.let { route ->
                Text(
                    stringResource(
                        R.string.route_summary,
                        String.format(Locale.US, "%.2f", route.distanceMeters / 1_000.0),
                        route.simulatedDurationSeconds.formatDuration(),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!isActive) {
                    Button(
                        onClick = if (plannedRoute == null) onStart else onStartRoute,
                        enabled = isMapReady && !isStarting,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) {
                        Text(
                            stringResource(
                                if (plannedRoute == null) R.string.action_start_mock
                                else R.string.action_start_bicycle_route,
                            ),
                            maxLines = 1,
                        )
                    }
                }
                if (isActive && isRouteSession) {
                    Button(
                        onClick = onPauseResumeRoute,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) {
                        Text(
                            stringResource(
                                if (routePaused) R.string.action_resume_route else R.string.action_pause_route,
                            ),
                        )
                    }
                }
                if (isActive && !isRouteSession && activeCoordinate != pendingCoordinate) {
                    Button(onClick = onApply, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.action_apply_new_location), maxLines = 1)
                    }
                }
                if (isStarting || isActive) {
                    OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.action_stop), maxLines = 1)
                    }
                }
            }
            if (!compactLayout && routeOrigin == null) {
                Text(
                    stringResource(R.string.mock_app_setup_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!compactLayout && routeOrigin != null) {
                Text(
                    stringResource(R.string.route_provider_notice),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(
                    onClick = { uriHandler.openUri("https://openfreemap.org/") },
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.attribution_openfreemap), style = MaterialTheme.typography.labelSmall) }
                TextButton(
                    onClick = { uriHandler.openUri("https://www.openstreetmap.org/copyright") },
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.attribution_openstreetmap), style = MaterialTheme.typography.labelSmall) }
                if (routeOrigin != null) {
                    TextButton(
                        onClick = { uriHandler.openUri("https://www.openstreetmap.org/fixthemap") },
                        contentPadding = PaddingValues(horizontal = 4.dp),
                        modifier = Modifier.heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.route_fix_map), style = MaterialTheme.typography.labelSmall) }
                }
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
