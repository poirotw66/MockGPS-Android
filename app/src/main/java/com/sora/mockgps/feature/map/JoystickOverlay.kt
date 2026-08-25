package com.sora.mockgps.feature.map

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.sora.mockgps.R
import com.sora.mockgps.route.JoystickSpeed
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt

private val JoystickBaseSize = 112.dp
private val JoystickKnobSize = 40.dp

@Composable
internal fun JoystickOverlay(
    enabled: Boolean,
    selectedSpeed: JoystickSpeed,
    onSpeedChange: (JoystickSpeed) -> Unit,
    onVectorChange: (bearingDegrees: Float, magnitude: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val joystickDescription = stringResource(R.string.joystick_description)
    val baseRadiusPx = with(LocalDensity.current) { (JoystickBaseSize / 2).toPx() }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }

    Column(
        modifier = modifier.semantics { contentDescription = joystickDescription },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            // First row kept compact on the map overlay; full set is in the Joystick sheet.
            listOf(JoystickSpeed.Walk, JoystickSpeed.Run, JoystickSpeed.Bicycle).forEach { speed ->
                FilterChip(
                    selected = selectedSpeed == speed,
                    onClick = { if (enabled) onSpeedChange(speed) },
                    label = {
                        Text(
                            text = speed.label(),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    enabled = enabled,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(JoystickSpeed.Car, JoystickSpeed.HighSpeedRail, JoystickSpeed.Airplane).forEach { speed ->
                FilterChip(
                    selected = selectedSpeed == speed,
                    onClick = { if (enabled) onSpeedChange(speed) },
                    label = {
                        Text(
                            text = speed.label(),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    },
                    enabled = enabled,
                )
            }
        }
        Box(
            modifier = Modifier
                .size(JoystickBaseSize)
                .pointerInput(enabled, baseRadiusPx) {
                    if (!enabled) return@pointerInput
                    detectDragGestures(
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val raw = dragOffset + dragAmount
                            val distance = hypot(raw.x.toDouble(), raw.y.toDouble()).toFloat()
                            dragOffset = if (distance <= baseRadiusPx || distance == 0f) {
                                raw
                            } else {
                                raw / distance * baseRadiusPx
                            }
                            val clampedDistance = hypot(dragOffset.x.toDouble(), dragOffset.y.toDouble()).toFloat()
                            onVectorChange(
                                offsetToBearingDegrees(dragOffset),
                                (clampedDistance / baseRadiusPx).coerceIn(0f, 1f),
                            )
                        },
                        onDragEnd = {
                            val bearing = offsetToBearingDegrees(dragOffset)
                            dragOffset = Offset.Zero
                            onVectorChange(bearing, 0f)
                        },
                        onDragCancel = {
                            val bearing = offsetToBearingDegrees(dragOffset)
                            dragOffset = Offset.Zero
                            onVectorChange(bearing, 0f)
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                tonalElevation = 4.dp,
                shadowElevation = 6.dp,
            ) {}
            Surface(
                modifier = Modifier
                    .offset {
                        IntOffset(dragOffset.x.roundToInt(), dragOffset.y.roundToInt())
                    }
                    .size(JoystickKnobSize),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                shadowElevation = 2.dp,
            ) {}
        }
    }
}

/** North = 0°, east = 90° (clockwise), matching navigation bearing. */
private fun offsetToBearingDegrees(offset: Offset): Float {
    if (offset == Offset.Zero) return 0f
    val degrees = Math.toDegrees(atan2(offset.x.toDouble(), -offset.y.toDouble())).toFloat()
    return ((degrees % 360f) + 360f) % 360f
}

@Composable
private fun JoystickSpeed.label(): String = when (this) {
    JoystickSpeed.Walk -> stringResource(R.string.joystick_speed_walk)
    JoystickSpeed.Run -> stringResource(R.string.joystick_speed_run)
    JoystickSpeed.Bicycle -> stringResource(R.string.joystick_speed_bicycle)
    JoystickSpeed.Car -> stringResource(R.string.joystick_speed_car)
    JoystickSpeed.HighSpeedRail -> stringResource(R.string.joystick_speed_high_speed_rail)
    JoystickSpeed.Airplane -> stringResource(R.string.joystick_speed_airplane)
}
