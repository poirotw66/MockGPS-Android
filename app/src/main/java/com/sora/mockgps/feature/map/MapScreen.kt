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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
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

/**
 * Lets the user move the map beneath a fixed centre reticle. The coordinate is committed only
 * after camera movement ends, so it is safe to pass directly to the foreground service.
 */
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
            context.startMockService(uiState.pendingCoordinate) {
                permissionMessage = serviceStartFailed
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

    Column(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.map_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.service_state, serviceStateText))

        MapPicker(
            uiState = uiState,
            cameraState = cameraState,
            onMapLoaded = viewModel::onMapLoaded,
            onMapLoadFailed = viewModel::onMapLoadFailed,
            onCameraIdle = viewModel::onCameraIdle,
            onRetry = viewModel::retryMap,
        )

        Text(
            stringResource(
                R.string.selected_coordinate,
                uiState.pendingCoordinate.latitude.formatCoordinate(),
                uiState.pendingCoordinate.longitude.formatCoordinate(),
            ),
            style = MaterialTheme.typography.bodyLarge,
        )
        val activeCoordinate = (serviceState as? MockServiceState.Active)?.coordinate
        if (activeCoordinate != null) {
            Text(
                stringResource(
                    R.string.active_coordinate,
                    activeCoordinate.latitude.formatCoordinate(),
                    activeCoordinate.longitude.formatCoordinate(),
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = viewModel::toggleMapType, modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(
                        if (uiState.mapType == MapDisplayType.Light) R.string.action_dark_map
                        else R.string.action_light_map,
                    ),
                )
            }
            OutlinedButton(
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                    }.onFailure {
                        permissionMessage = developerOptionsUnavailable
                    }
                },
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.action_open_developer_options)) }
        }

        permissionMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = {
                val permissions = context.requiredRuntimePermissions(notificationPermissionHandled)
                if (permissions.isEmpty()) {
                    context.startMockService(uiState.pendingCoordinate) {
                        permissionMessage = serviceStartFailed
                    }
                } else {
                    permissionLauncher.launch(permissions.toTypedArray())
                }
            },
            enabled = isMapReady && !isStarting && !isActive,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.action_start_mock)) }
        if (isActive && activeCoordinate != uiState.pendingCoordinate) {
            Button(
                onClick = {
                    context.startService(
                        MockLocationForegroundService.updateIntent(context, uiState.pendingCoordinate),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.action_apply_new_location)) }
        }
        Button(
            onClick = { context.startService(MockLocationForegroundService.stopIntent(context)) },
            enabled = isStarting || isActive,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.action_stop)) }
        Text(stringResource(R.string.mock_app_setup_hint), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun MapPicker(
    uiState: MapUiState,
    cameraState: CameraState,
    onMapLoaded: () -> Unit,
    onMapLoadFailed: () -> Unit,
    onCameraIdle: (CameraPosition) -> Unit,
    onRetry: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    Box(modifier = Modifier.fillMaxWidth().height(320.dp)) {
        key(uiState.mapRenderKey) {
            MaplibreMap(
                modifier = Modifier.fillMaxSize(),
                baseStyle = BaseStyle.Uri(uiState.mapType.styleUrl),
                cameraState = cameraState,
                onMapLoadFinished = onMapLoaded,
                onMapLoadFailed = { onMapLoadFailed() },
            )
        }
        CenterReticle(modifier = Modifier.align(Alignment.Center))
        Row(modifier = Modifier.align(Alignment.BottomStart)) {
            TextButton(
                onClick = { uriHandler.openUri("https://openfreemap.org/") },
                contentPadding = PaddingValues(horizontal = 4.dp),
            ) {
                Text(stringResource(R.string.attribution_openfreemap), style = MaterialTheme.typography.labelSmall)
            }
            TextButton(
                onClick = { uriHandler.openUri("https://www.openstreetmap.org/copyright") },
                contentPadding = PaddingValues(horizontal = 4.dp),
            ) {
                Text(stringResource(R.string.attribution_openstreetmap), style = MaterialTheme.typography.labelSmall)
            }
        }

        when (uiState.loadingState) {
            MapLoadingState.Loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            MapLoadingState.Error -> Column(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.map_load_failed), color = MaterialTheme.colorScheme.error)
                Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
            }
            MapLoadingState.Ready -> Unit
        }
    }

    // CameraState is held across recomposition; this callback occurs after a pan, zoom,
    // or programmatic move has settled rather than on every drag frame.
    androidx.compose.runtime.LaunchedEffect(cameraState.isCameraMoving) {
        if (!cameraState.isCameraMoving) {
            onCameraIdle(cameraState.position)
        }
    }
}

@Composable
private fun CenterReticle(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(34.dp)) {
        val stroke = Stroke(width = 2.dp.toPx())
        val center = this.center
        drawCircle(color = Color.White, radius = 9.dp.toPx(), style = stroke)
        drawLine(Color.White, start = androidx.compose.ui.geometry.Offset(center.x - 15.dp.toPx(), center.y), end = androidx.compose.ui.geometry.Offset(center.x + 15.dp.toPx(), center.y), strokeWidth = 2.dp.toPx())
        drawLine(Color.White, start = androidx.compose.ui.geometry.Offset(center.x, center.y - 15.dp.toPx()), end = androidx.compose.ui.geometry.Offset(center.x, center.y + 15.dp.toPx()), strokeWidth = 2.dp.toPx())
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
    runCatching {
        startForegroundService(MockLocationForegroundService.startIntent(this, coordinate))
    }.onFailure { onFailure() }
}

private fun Context.isGranted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
