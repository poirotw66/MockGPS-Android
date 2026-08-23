package com.sora.mockgps.feature.spike

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.sora.mockgps.R
import com.sora.mockgps.core.model.Coordinate
import com.sora.mockgps.service.MockLocationForegroundService
import com.sora.mockgps.service.MockServiceState

/** Temporary no-map UI used to verify the complete foreground-service lifecycle. */
@Composable
fun MockLocationSpikeScreen() {
    val context = LocalContext.current
    val serviceState by MockLocationForegroundService.state.collectAsState()
    var latitudeText by rememberSaveable { mutableStateOf(MockLocationForegroundService.DEFAULT_COORDINATE.latitude.toString()) }
    var longitudeText by rememberSaveable { mutableStateOf(MockLocationForegroundService.DEFAULT_COORDINATE.longitude.toString()) }
    var permissionMessage by remember { mutableStateOf<String?>(null) }
    var notificationPermissionHandled by rememberSaveable { mutableStateOf(false) }
    val coordinate = parseCoordinate(latitudeText, longitudeText)
    val locationPermissionRequiredMessage = stringResource(R.string.location_permission_required)
    val notificationPermissionDeniedMessage = stringResource(R.string.notification_permission_denied)
    val serviceStartFailedMessage = stringResource(R.string.foreground_service_start_failed)

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        notificationPermissionHandled = true
        val locationGranted = context.isGranted(Manifest.permission.ACCESS_FINE_LOCATION) ||
            context.isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!locationGranted) {
            permissionMessage = locationPermissionRequiredMessage
        } else {
            permissionMessage = if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !context.isGranted(Manifest.permission.POST_NOTIFICATIONS)
            ) notificationPermissionDeniedMessage else null
            coordinate?.let {
                startMockService(context, it) { permissionMessage = serviceStartFailedMessage }
            }
        }
    }

    val isBusy = serviceState is MockServiceState.Starting
    val isActive = serviceState is MockServiceState.Active
    val stateText = when (val state = serviceState) {
        MockServiceState.Idle -> stringResource(R.string.state_idle)
        is MockServiceState.Starting -> stringResource(R.string.state_starting)
        is MockServiceState.Active -> stringResource(R.string.state_active)
        is MockServiceState.Error -> stringResource(R.string.state_error, state.message)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.spike_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.service_state, stateText))
        Text(stringResource(R.string.spike_description))
        OutlinedTextField(
            value = latitudeText,
            onValueChange = { latitudeText = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.latitude)) },
            singleLine = true,
        )
        OutlinedTextField(
            value = longitudeText,
            onValueChange = { longitudeText = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.longitude)) },
            singleLine = true,
        )
        if (coordinate == null) {
            Text(stringResource(R.string.invalid_coordinate), color = MaterialTheme.colorScheme.error)
        }
        permissionMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(
            onClick = {
                val requiredPermissions = requiredRuntimePermissions(context, notificationPermissionHandled)
                if (requiredPermissions.isNotEmpty()) {
                    permissionLauncher.launch(requiredPermissions.toTypedArray())
                } else if (coordinate != null) {
                    startMockService(context, coordinate) { permissionMessage = serviceStartFailedMessage }
                }
            },
            enabled = !isBusy && !isActive && coordinate != null,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.action_start_mock)) }
        Button(
            onClick = { context.startService(MockLocationForegroundService.stopIntent(context)) },
            enabled = isBusy || isActive,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.action_stop)) }
        val developerOptionsUnavailableMessage = stringResource(R.string.developer_options_unavailable)
        Button(
            onClick = {
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                }.onFailure {
                    permissionMessage = developerOptionsUnavailableMessage
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(R.string.action_open_developer_options)) }
        Text(stringResource(R.string.mock_app_setup_hint))
    }
}

private fun parseCoordinate(latitude: String, longitude: String): Coordinate? {
    val lat = latitude.toDoubleOrNull() ?: return null
    val lon = longitude.toDoubleOrNull() ?: return null
    return Coordinate(lat, lon).takeIf {
        it.latitude.isFinite() && it.latitude in -90.0..90.0 &&
            it.longitude.isFinite() && it.longitude in -180.0..180.0
    }
}

private fun requiredRuntimePermissions(
    context: Context,
    notificationPermissionHandled: Boolean,
): List<String> = buildList {
    if (!context.isGranted(Manifest.permission.ACCESS_FINE_LOCATION) &&
        !context.isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)
    ) {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        !notificationPermissionHandled &&
        !context.isGranted(Manifest.permission.POST_NOTIFICATIONS)
    ) add(Manifest.permission.POST_NOTIFICATIONS)
}

private fun startMockService(
    context: Context,
    coordinate: Coordinate,
    onFailure: () -> Unit,
) {
    runCatching {
        context.startForegroundService(MockLocationForegroundService.startIntent(context, coordinate))
    }.onFailure { onFailure() }
}

private fun Context.isGranted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
