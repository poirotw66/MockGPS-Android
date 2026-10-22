package com.sora.mockgps.feature.map

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sora.mockgps.R
import com.sora.mockgps.core.model.Coordinate
import com.sora.mockgps.route.JoystickSpeed
import com.sora.mockgps.service.RouteProgress
import java.util.Locale

internal enum class MapDetailGroup {
    Search,
    Places,
    Route,
    Joystick,
    More,
}

@androidx.annotation.StringRes
internal fun JoystickSpeed.labelResource(): Int = when (this) {
    JoystickSpeed.Walk -> R.string.joystick_speed_walk
    JoystickSpeed.Run -> R.string.joystick_speed_run
    JoystickSpeed.Bicycle -> R.string.joystick_speed_bicycle
    JoystickSpeed.Car -> R.string.joystick_speed_car
    JoystickSpeed.HighSpeedRail -> R.string.joystick_speed_high_speed_rail
    JoystickSpeed.Airplane -> R.string.joystick_speed_airplane
}

@Composable
internal fun MapDockButton(
    icon: ImageVector,
    label: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
            },
        ) {
            IconButton(onClick = onClick, modifier = Modifier.size(44.dp)) {
                Icon(
                    imageVector = icon,
                    contentDescription = stringResource(label),
                    tint = if (selected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
        )
    }
}

@Composable
internal fun RouteEndpointSummary(
    label: String,
    coordinate: Coordinate,
    supportingText: String? = null,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
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
internal fun RouteTripStrip(
    progress: RouteProgress,
    paused: Boolean,
    routeName: String?,
    onPauseResume: () -> Unit,
    onStop: () -> Unit,
) {
    val fraction = (progress.travelledDistanceMeters / progress.totalDistanceMeters)
        .toFloat()
        .coerceIn(0f, 1f)
    val remainingSeconds = if (progress.speedMetersPerSecond > 0.0) {
        progress.remainingDistanceMeters / progress.speedMetersPerSecond
    } else {
        0.0
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        routeName?.let { name ->
            Text(
                name,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column {
                Text(
                    stringResource(if (paused) R.string.route_state_paused else R.string.route_trip_remaining_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    remainingSeconds.formatTripClock(),
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            Text(
                stringResource(R.string.route_progress_percent, (fraction * 100).toInt()),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onPauseResume,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) {
                Text(stringResource(if (paused) R.string.action_resume_route else R.string.action_pause_route))
            }
            Button(
                onClick = onStop,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) {
                Text(stringResource(R.string.action_stop))
            }
        }
    }
}

@Composable
internal fun RouteProgressSummary(progress: RouteProgress, paused: Boolean) {
    val fraction = (progress.travelledDistanceMeters / progress.totalDistanceMeters)
        .toFloat()
        .coerceIn(0f, 1f)
    val remainingDuration = if (progress.speedMetersPerSecond > 0.0) {
        (progress.remainingDistanceMeters / progress.speedMetersPerSecond).formatDuration()
    } else {
        "-"
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
internal fun RouteWaypointEditor(
    waypoints: List<Coordinate>,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
) {
    waypoints.drop(1).dropLast(1).forEachIndexed { visibleIndex, coordinate ->
        val routeIndex = visibleIndex + 1
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.route_stop_label, routePointLabel(routeIndex)), style = MaterialTheme.typography.labelLarge)
                    Text(
                        "${coordinate.latitude.formatCoordinate()}, ${coordinate.longitude.formatCoordinate()}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(
                    onClick = { onMove(routeIndex, -1) },
                    enabled = routeIndex > 1,
                ) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.action_move_stop_up))
                }
                IconButton(
                    onClick = { onMove(routeIndex, 1) },
                    enabled = routeIndex < waypoints.lastIndex - 1,
                ) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.action_move_stop_down))
                }
                IconButton(onClick = { onRemove(routeIndex) }) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.action_remove_route_stop))
                }
            }
        }
    }
}
