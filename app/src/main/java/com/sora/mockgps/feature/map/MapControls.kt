package com.sora.mockgps.feature.map

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sora.mockgps.R
import com.sora.mockgps.core.model.Coordinate
import com.sora.mockgps.route.PlannedRoute
import com.sora.mockgps.service.RouteCompleted
import com.sora.mockgps.service.RouteFailed
import com.sora.mockgps.service.RouteProgress
import com.sora.mockgps.service.RouteServiceState
import java.util.Locale
import org.maplibre.compose.expressions.dsl.format


@Composable
internal fun MapHeader(
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
internal fun MapControlPanel(
    pendingCoordinate: Coordinate,
    activeCoordinate: Coordinate?,
    showCoordinates: Boolean,
    permissionMessage: String?,
    compactLayout: Boolean,
    panelMaxWidth: Dp,
    isMapReady: Boolean,
    isStarting: Boolean,
    isActive: Boolean,
    isRouteSession: Boolean,
    routePaused: Boolean,
    routeProgress: RouteProgress?,
    routeResult: RouteServiceState?,
    routeOptions: RouteSimulationOptions,
    onRouteOptionsChange: (RouteSimulationOptions) -> Unit,
    favoritesCount: Int,
    routePlanningStep: RoutePlanningStep,
    routeOrigin: Coordinate?,
    routeDestination: Coordinate?,
    routeWaypoints: List<Coordinate>,
    plannedRoute: PlannedRoute?,
    isPlanningRoute: Boolean,
    routeError: String?,
    placeSearchQuery: String,
    placeSearchResults: List<com.sora.mockgps.feature.search.PlaceSearchResult>,
    placeSearchError: PlaceSearchError?,
    onPlaceSearchQueryChanged: (String) -> Unit,
    onPlaceSelected: (com.sora.mockgps.feature.search.PlaceSearchResult) -> Unit,
    onShowCoordinatesChange: (Boolean) -> Unit,
    updateIntervalMillis: Long,
    accuracyMeters: Float,
    onUpdateIntervalChange: (Long) -> Unit,
    onAccuracyChange: (Float) -> Unit,
    onUseCurrentLocation: () -> Unit,
    onSaveFavorite: () -> Unit,
    onShowFavorites: () -> Unit,
    onShowRouteLibrary: () -> Unit,
    onSaveRoute: () -> Unit,
    onExportGpx: () -> Unit,
    onBeginRoutePlanning: () -> Unit,
    onSetRouteOrigin: () -> Unit,
    onSetRouteDestination: () -> Unit,
    onPlanRoute: () -> Unit,
    onEditRouteOrigin: () -> Unit,
    onEditRouteDestination: () -> Unit,
    onAddRouteWaypoint: () -> Unit,
    onRemoveRouteWaypoint: (Int) -> Unit,
    onMoveRouteWaypoint: (Int, Int) -> Unit,
    onSwapRouteEndpoints: () -> Unit,
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
                if (showCoordinates) Text(
                    stringResource(R.string.selected_coordinate, pendingCoordinate.latitude.formatCoordinate(), pendingCoordinate.longitude.formatCoordinate()),
                    style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                OutlinedTextField(
                    value = placeSearchQuery,
                    onValueChange = onPlaceSearchQueryChanged,
                    label = { Text(stringResource(R.string.place_search_label)) },
                    supportingText = { Text(stringResource(R.string.place_search_privacy)) },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                )
                placeSearchError?.let { error -> Text(stringResource(when (error) {
                    PlaceSearchError.Network -> R.string.place_search_error_network
                    PlaceSearchError.RateLimited -> R.string.place_search_error_rate
                    PlaceSearchError.InvalidResponse -> R.string.place_search_error_invalid
                }), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                placeSearchResults.forEach { result ->
                    TextButton(onClick = { onPlaceSelected(result) }, modifier = Modifier.fillMaxWidth()) {
                        Text(result.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                TextButton(onClick = { onShowCoordinatesChange(!showCoordinates) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(if (showCoordinates) R.string.action_hide_coordinates else R.string.action_show_coordinates))
                }
                TextButton(onClick = onUseCurrentLocation, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.action_use_current_location))
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { onUpdateIntervalChange(if (updateIntervalMillis >= 2_000L) 1_000L else 2_000L) }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.setting_update_interval, updateIntervalMillis / 1_000L))
                    }
                    TextButton(onClick = { onAccuracyChange(if (accuracyMeters >= 10f) 5f else 10f) }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.setting_accuracy, accuracyMeters.toInt()))
                    }
                }
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
                TextButton(onClick = onShowRouteLibrary, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.action_route_library))
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
                        RouteWaypointEditor(routeWaypoints, onRemoveRouteWaypoint, onMoveRouteWaypoint)
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
                                TextButton(onClick = onAddRouteWaypoint, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                    Text(stringResource(R.string.action_add_route_stop))
                                }
                                TextButton(onClick = onSwapRouteEndpoints, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                    Text(stringResource(R.string.action_swap_endpoints))
                                }
                            }
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
                                    String.format(Locale.US, "%.1f", routeOptions.speedKilometersPerHour),
                                    (route.distanceMeters / (routeOptions.speedKilometersPerHour / 3.6)).formatDuration(),
                                ),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (!isRouteSession) {
                            RouteSimulationControls(
                                options = routeOptions,
                                onOptionsChange = onRouteOptionsChange,
                            )
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = onAddRouteWaypoint, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                    Text(stringResource(R.string.action_add_route_stop))
                                }
                                TextButton(onClick = onSwapRouteEndpoints, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                    Text(stringResource(R.string.action_swap_endpoints))
                                }
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = onSaveRoute, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                    Text(stringResource(R.string.action_save_route))
                                }
                                TextButton(onClick = onShowRouteLibrary, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                    Text(stringResource(R.string.action_route_library))
                                }
                                TextButton(onClick = onExportGpx, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                    Text("GPX")
                                }
                            }
                        }
                        routeProgress?.let { progress ->
                            RouteProgressSummary(progress = progress, paused = routePaused)
                        }
                        when (routeResult) {
                            is RouteCompleted -> Text(
                                stringResource(R.string.route_completed_message),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            is RouteFailed -> Text(
                                stringResource(R.string.route_failed_message, routeResult.message),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            else -> Unit
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
private fun RouteProgressSummary(progress: RouteProgress, paused: Boolean) {
    val fraction = (progress.travelledDistanceMeters / progress.totalDistanceMeters)
        .toFloat()
        .coerceIn(0f, 1f)
    val remainingDuration = if (progress.speedMetersPerSecond > 0.0) {
        (progress.remainingDistanceMeters / progress.speedMetersPerSecond).formatDuration()
    } else {
        "—"
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
private fun RouteWaypointEditor(
    waypoints: List<Coordinate>,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
) {
    waypoints.drop(1).dropLast(1).forEachIndexed { visibleIndex, coordinate ->
        val routeIndex = visibleIndex + 1
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.route_stop_label, visibleIndex + 1), style = MaterialTheme.typography.labelLarge)
                    Text(
                        "${coordinate.latitude.formatCoordinate()}, ${coordinate.longitude.formatCoordinate()}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(
                    onClick = { onMove(routeIndex, -1) },
                    enabled = routeIndex > 1,
                ) { Text("↑") }
                TextButton(
                    onClick = { onMove(routeIndex, 1) },
                    enabled = routeIndex < waypoints.lastIndex - 1,
                ) { Text("↓") }
                TextButton(onClick = { onRemove(routeIndex) }) { Text("×") }
            }
        }
    }
}
