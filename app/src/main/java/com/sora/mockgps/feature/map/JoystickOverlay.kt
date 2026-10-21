package com.sora.mockgps.feature.map

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
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
internal fun JoystickSpeedCard(
    selectedSpeed: JoystickSpeed,
    enabled: Boolean,
    onSpeedChange: (JoystickSpeed) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.widthIn(max = 420.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
        shadowElevation = 6.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.joystick_speed_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                JoystickSpeed.entries.forEach { speed ->
                    JoystickSpeedChoice(
                        label = speed.label(),
                        icon = speed.markerIcon,
                        selected = selectedSpeed == speed,
                        enabled = enabled,
                        onClick = { onSpeedChange(speed) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun JoystickOverlay(
    enabled: Boolean,
    selectedSpeed: JoystickSpeed,
    onVectorChange: (bearingDegrees: Float, magnitude: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val joystickDescription = stringResource(R.string.joystick_description)
    val baseRadiusPx = with(LocalDensity.current) { (JoystickBaseSize / 2).toPx() }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    val outline = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.85f)
    val padColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
    val knobColor = MaterialTheme.colorScheme.primary
    Box(
            modifier = modifier
                .semantics { contentDescription = joystickDescription }
                .size(JoystickBaseSize)
                .clip(CircleShape)
                .background(padColor)
                .border(1.dp, outline, CircleShape)
                .pointerInput(enabled, baseRadiusPx) {
                    if (!enabled) return@pointerInput
                    // The pad used to be a Material surface. That surface owns the hit target and
                    // drops the drag, so the map neither pans nor receives a joystick vector.
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        var offset = Offset.Zero
                        drag(down.id) { change ->
                            val raw = offset + change.positionChange()
                            val distance = hypot(raw.x.toDouble(), raw.y.toDouble()).toFloat()
                            offset = if (distance <= baseRadiusPx || distance == 0f) {
                                raw
                            } else {
                                raw / distance * baseRadiusPx
                            }
                            change.consume()
                            dragOffset = offset
                            val clampedDistance = hypot(offset.x.toDouble(), offset.y.toDouble()).toFloat()
                            onVectorChange(
                                offsetToBearingDegrees(offset),
                                (clampedDistance / baseRadiusPx).coerceIn(0f, 1f),
                            )
                        }
                        val bearing = offsetToBearingDegrees(offset)
                        offset = Offset.Zero
                        dragOffset = Offset.Zero
                        onVectorChange(bearing, 0f)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .offset { IntOffset(dragOffset.x.roundToInt(), dragOffset.y.roundToInt()) }
                    .size(JoystickKnobSize)
                    .clip(CircleShape)
                    .background(knobColor),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = selectedSpeed.markerIcon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
}

@Composable
private fun JoystickSpeedChoice(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(
            width = if (selected) 0.dp else 1.5.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        ),
        shadowElevation = if (selected) 2.dp else 0.dp,
    ) {
        Row(
            modifier = Modifier.heightIn(min = 40.dp).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(text = label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
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
