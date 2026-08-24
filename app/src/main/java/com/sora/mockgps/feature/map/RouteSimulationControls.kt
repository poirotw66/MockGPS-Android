package com.sora.mockgps.feature.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sora.mockgps.R
import com.sora.mockgps.route.AccelerationModel
import com.sora.mockgps.route.GpsDriftConfiguration
import com.sora.mockgps.route.MovementProfile
import com.sora.mockgps.route.RouteExecutionMode

internal enum class MovementPreset(val kilometersPerHour: Double) {
    Walk(5.0), Run(10.0), Bicycle(18.0), Drive(50.0), Custom(18.0),
}

internal data class RouteSimulationOptions(
    val preset: MovementPreset = MovementPreset.Bicycle,
    val customSpeedText: String = "18",
    val mode: RouteExecutionMode = RouteExecutionMode.StopAtEnd,
    val smoothMovement: Boolean = true,
    val gpsDriftEnabled: Boolean = false,
) {
    val speedKilometersPerHour: Double
        get() = if (preset == MovementPreset.Custom) {
            customSpeedText.toDoubleOrNull()?.coerceIn(0.1, MovementProfile.MAXIMUM_KILOMETERS_PER_HOUR) ?: 18.0
        } else {
            preset.kilometersPerHour
        }

    fun movementProfile(): MovementProfile = when (preset) {
        MovementPreset.Walk -> MovementProfile.Walk
        MovementPreset.Run -> MovementProfile.Run
        MovementPreset.Bicycle -> MovementProfile.Bicycle
        MovementPreset.Drive -> MovementProfile.Driving()
        MovementPreset.Custom -> MovementProfile.Custom(speedKilometersPerHour)
    }

    fun accelerationModel(): AccelerationModel = if (smoothMovement) {
        AccelerationModel(accelerationMetersPerSecondSquared = 1.5, decelerationMetersPerSecondSquared = 2.5)
    } else {
        AccelerationModel.Instant
    }

    fun gpsDrift(): GpsDriftConfiguration = if (gpsDriftEnabled) {
        GpsDriftConfiguration(maximumHorizontalMeters = 4.0, seed = 42L)
    } else {
        GpsDriftConfiguration()
    }
}

@Composable
internal fun RouteSimulationControls(
    options: RouteSimulationOptions,
    onOptionsChange: (RouteSimulationOptions) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.route_speed_title), style = MaterialTheme.typography.labelLarge)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(MovementPreset.Walk, MovementPreset.Run, MovementPreset.Bicycle).forEach { preset ->
                FilterChip(
                    selected = options.preset == preset,
                    onClick = { onOptionsChange(options.copy(preset = preset)) },
                    label = { Text(preset.label()) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(MovementPreset.Drive, MovementPreset.Custom).forEach { preset ->
                FilterChip(
                    selected = options.preset == preset,
                    onClick = { onOptionsChange(options.copy(preset = preset)) },
                    label = { Text(preset.label()) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (options.preset == MovementPreset.Custom) {
            OutlinedTextField(
                value = options.customSpeedText,
                onValueChange = { value ->
                    if (value.length <= 6 && value.all { it.isDigit() || it == '.' }) {
                        onOptionsChange(options.copy(customSpeedText = value))
                    }
                },
                label = { Text(stringResource(R.string.route_custom_speed)) },
                suffix = { Text("km/h") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(stringResource(R.string.route_end_behavior), style = MaterialTheme.typography.labelLarge)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RouteExecutionMode.entries.forEach { mode ->
                FilterChip(
                    selected = options.mode == mode,
                    onClick = { onOptionsChange(options.copy(mode = mode)) },
                    label = { Text(mode.label()) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        RouteToggle(
            label = stringResource(R.string.route_smooth_movement),
            checked = options.smoothMovement,
            onCheckedChange = { onOptionsChange(options.copy(smoothMovement = it)) },
        )
        RouteToggle(
            label = stringResource(R.string.route_gps_drift),
            checked = options.gpsDriftEnabled,
            onCheckedChange = { onOptionsChange(options.copy(gpsDriftEnabled = it)) },
        )
    }
}

@Composable
private fun RouteToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun MovementPreset.label(): String = when (this) {
    MovementPreset.Walk -> stringResource(R.string.route_speed_walk)
    MovementPreset.Run -> stringResource(R.string.route_speed_run)
    MovementPreset.Bicycle -> stringResource(R.string.route_speed_bicycle)
    MovementPreset.Drive -> stringResource(R.string.route_speed_drive)
    MovementPreset.Custom -> stringResource(R.string.route_speed_custom)
}

@Composable
private fun RouteExecutionMode.label(): String = when (this) {
    RouteExecutionMode.StopAtEnd -> stringResource(R.string.route_mode_stop)
    RouteExecutionMode.Loop -> stringResource(R.string.route_mode_loop)
    RouteExecutionMode.Reverse -> stringResource(R.string.route_mode_reverse)
}
