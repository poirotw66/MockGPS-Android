package com.sora.mockgps.feature.map

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.sora.mockgps.service.MockLocationForegroundService
import com.sora.mockgps.service.MockServiceState
import java.util.Locale
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraState
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position

/** A full-screen map picker with controls kept clear of the map's centre. */
@Composable
fun MapScreen(viewModel: MapViewModel = viewModel()) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val serviceState by MockLocationForegroundService.state.collectAsState()
    val cameraState = rememberMapCameraState(uiState.camera)
    var permissionMessage by remember { mutableStateOf<String?>(null) }
    var notificationPermissionHandled by rememberSaveable { mutableStateOf(false) }

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
            context.startMockService(uiState.pendingCoordinate) { permissionMessage = serviceStartFailed }
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

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val compactLayout = maxWidth > maxHeight
        MapPicker(
            modifier = Modifier.fillMaxSize(),
            mapType = uiState.mapType,
            mapRenderKey = uiState.mapRenderKey,
            loadingState = uiState.loadingState,
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
            onStart = {
                val permissions = context.requiredRuntimePermissions(notificationPermissionHandled)
                if (permissions.isEmpty()) {
                    context.startMockService(uiState.pendingCoordinate) { permissionMessage = serviceStartFailed }
                } else {
                    permissionLauncher.launch(permissions.toTypedArray())
                }
            },
            onApply = {
                context.startService(
                    MockLocationForegroundService.updateIntent(context, uiState.pendingCoordinate),
                )
            },
            onStop = { context.startService(MockLocationForegroundService.stopIntent(context)) },
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
    onStart: () -> Unit,
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!isActive) {
                    Button(
                        onClick = onStart,
                        enabled = isMapReady && !isStarting,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.action_start_mock), maxLines = 1) }
                }
                if (isActive && activeCoordinate != pendingCoordinate) {
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
            if (!compactLayout) {
                Text(
                    stringResource(R.string.mock_app_setup_hint),
                    style = MaterialTheme.typography.bodySmall,
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
            )
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

private fun Context.isGranted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
