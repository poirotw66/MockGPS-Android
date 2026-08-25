package com.sora.mockgps.feature.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.sora.mockgps.R
import com.sora.mockgps.route.RouteTransportMode

@Composable
internal fun AutoJourneyDialog(
    onDismiss: () -> Unit,
    onGenerate: (AutoJourneyOptions) -> Unit,
) {
    var options by rememberSaveable(stateSaver = AutoJourneyOptionsSaver) {
        mutableStateOf(AutoJourneyOptions())
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.auto_journey_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OptionLabel(R.string.auto_journey_region)
                RegionOptionGrid(options.region) { options = options.copy(region = it) }
                OptionLabel(R.string.auto_journey_duration)
                OptionRow(JourneyDuration.entries, options.duration, { it.label() }) {
                    options = options.copy(duration = it)
                }
                OptionLabel(R.string.auto_journey_transport)
                OptionRow(RouteTransportMode.entries, options.transportMode, { it.label() }) {
                    options = options.copy(transportMode = it)
                }
                OptionLabel(R.string.auto_journey_route_style)
                OptionRow(AutoJourneyRouteStyle.entries, options.routeStyle, { it.label() }) {
                    options = options.copy(routeStyle = it)
                }
                Text(
                    stringResource(
                        when (options.routeStyle) {
                            AutoJourneyRouteStyle.PerfectShape -> R.string.auto_journey_notice_perfect_shape
                            AutoJourneyRouteStyle.RoadAdapted -> R.string.auto_journey_notice
                        },
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onGenerate(options) }) {
                Text(stringResource(R.string.action_generate_journey))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
internal fun ShapeRouteDialog(
    onDismiss: () -> Unit,
    onGenerate: (RouteShape) -> Unit,
) {
    var shape by rememberSaveable(stateSaver = RouteShapeSaver) {
        mutableStateOf(RouteShape.Heart)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.shape_route_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.shape_route_notice))
                ShapeOptionGrid(shape) { shape = it }
            }
        },
        confirmButton = {
            TextButton(onClick = { onGenerate(shape) }) {
                Text(stringResource(R.string.action_draw_shape))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

private val AutoJourneyOptionsSaver = listSaver<AutoJourneyOptions, String>(
    save = { options ->
        listOf(
            options.region.name,
            options.duration.name,
            options.transportMode.name,
            options.routeStyle.name,
        )
    },
    restore = { values ->
        AutoJourneyOptions(
            region = JourneyRegion.valueOf(values[0]),
            duration = JourneyDuration.valueOf(values[1]),
            transportMode = RouteTransportMode.valueOf(values[2]),
            routeStyle = values.getOrNull(3)?.let(AutoJourneyRouteStyle::valueOf)
                ?: AutoJourneyRouteStyle.RoadAdapted,
        )
    },
)

private val RouteShapeSaver = Saver<RouteShape, String>(
    save = { shape -> shape.name },
    restore = { name -> RouteShape.valueOf(name) },
)

@Composable
private fun RegionOptionGrid(selected: JourneyRegion, onSelected: (JourneyRegion) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        JourneyRegion.entries.chunked(2).forEach { rowRegions ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rowRegions.forEach { region ->
                    FilterChip(
                        selected = selected == region,
                        onClick = { onSelected(region) },
                        label = { Text(region.label()) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(2 - rowRegions.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ShapeOptionGrid(selected: RouteShape, onSelected: (RouteShape) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        RouteShape.entries.chunked(3).forEach { rowShapes ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rowShapes.forEach { shape ->
                    FilterChip(
                        selected = selected == shape,
                        onClick = { onSelected(shape) },
                        label = { Text(shape.label()) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(3 - rowShapes.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun OptionLabel(resourceId: Int) {
    Text(stringResource(resourceId))
}

@Composable
private fun <T> OptionRow(
    values: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelected: (T) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        values.forEach { value ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelected(value) },
                label = { Text(label(value)) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun JourneyRegion.label(): String = stringResource(
    when (this) {
        JourneyRegion.CurrentLocation -> R.string.region_current_location
        JourneyRegion.Taiwan -> R.string.region_taiwan
        JourneyRegion.Japan -> R.string.region_japan
        JourneyRegion.SouthKorea -> R.string.region_south_korea
    },
)

@Composable
private fun JourneyDuration.label(): String = stringResource(
    when (this) {
        JourneyDuration.Short -> R.string.duration_30_minutes
        JourneyDuration.Medium -> R.string.duration_60_minutes
        JourneyDuration.Long -> R.string.duration_120_minutes
    },
)

@Composable
private fun RouteTransportMode.label(): String = stringResource(
    when (this) {
        RouteTransportMode.Walk -> R.string.transport_walk
        RouteTransportMode.Bicycle -> R.string.transport_bicycle
        RouteTransportMode.Drive -> R.string.transport_drive
    },
)

@Composable
private fun AutoJourneyRouteStyle.label(): String = stringResource(
    when (this) {
        AutoJourneyRouteStyle.PerfectShape -> R.string.route_style_perfect_shape
        AutoJourneyRouteStyle.RoadAdapted -> R.string.route_style_road_adapted
    },
)

@Composable
private fun RouteShape.label(): String = stringResource(
    when (this) {
        RouteShape.Heart -> R.string.shape_heart
        RouteShape.Star -> R.string.shape_star
        RouteShape.Circle -> R.string.shape_circle
        RouteShape.Cat -> R.string.shape_cat
        RouteShape.Dog -> R.string.shape_dog
        RouteShape.Rabbit -> R.string.shape_rabbit
        RouteShape.Fish -> R.string.shape_fish
        RouteShape.Butterfly -> R.string.shape_butterfly
        RouteShape.ChristmasTree -> R.string.shape_christmas_tree
    },
)