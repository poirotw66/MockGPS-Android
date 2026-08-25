package com.sora.mockgps.feature.map

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.sora.mockgps.R
import com.sora.mockgps.core.model.Coordinate
import com.sora.mockgps.feature.search.PlaceSearchResult
import com.sora.mockgps.feature.search.PlaceSearchSource
import com.sora.mockgps.feature.search.looksLikeLandmarkNickname
import com.sora.mockgps.route.PlannedRoute
import com.sora.mockgps.service.RouteCompleted
import com.sora.mockgps.service.RouteFailed
import com.sora.mockgps.service.RouteProgress
import com.sora.mockgps.service.RouteServiceState
import java.util.Locale
import org.maplibre.compose.expressions.dsl.format


@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun MapControlPanel(
    pendingCoordinate: Coordinate,
    activeCoordinate: Coordinate?,
    showCoordinates: Boolean,
    showLandmarks: Boolean,
    permissionMessage: String?,
    compactLayout: Boolean,
    panelMaxWidth: Dp,
    mapType: MapDisplayType,
    isMapReady: Boolean,
    isStarting: Boolean,
    isActive: Boolean,
    isRouteSession: Boolean,
    routePaused: Boolean,
    routeProgress: RouteProgress?,
    routeResult: RouteServiceState?,
    isSelectingRouteWaypoint: Boolean,
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
    automaticJourneyRecoveryAvailable: Boolean,
    activeRouteName: String?,
    placeSearchQuery: String,
    isPlaceSearching: Boolean,
    placeSearchResults: List<com.sora.mockgps.feature.search.PlaceSearchResult>,
    placeSearchError: PlaceSearchError?,
    onPlaceSearchQueryChanged: (String) -> Unit,
    onPlaceSelected: (com.sora.mockgps.feature.search.PlaceSearchResult) -> Unit,
    onShowCoordinatesChange: (Boolean) -> Unit,
    onShowLandmarksChange: (Boolean) -> Unit,
    updateIntervalMillis: Long,
    accuracyMeters: Float,
    onUpdateIntervalChange: (Long) -> Unit,
    onAccuracyChange: (Float) -> Unit,
    onToggleMapType: () -> Unit,
    onOpenDeveloperOptions: () -> Unit,
    onUseCurrentLocation: () -> Unit,
    onSaveFavorite: () -> Unit,
    onShowFavorites: () -> Unit,
    onShowRouteLibrary: () -> Unit,
    onSaveRoute: () -> Unit,
    onExportGpx: () -> Unit,
    onBeginRoutePlanning: () -> Unit,
    onShowAutoJourney: () -> Unit,
    onRegenerateAutomaticJourney: () -> Unit,
    onShowShapeRoute: () -> Unit,
    onPlanRoute: () -> Unit,
    onEditRouteOrigin: () -> Unit,
    onEditRouteDestination: () -> Unit,
    onAddRouteWaypoint: () -> Unit,
    onCancelRouteWaypointSelection: () -> Unit,
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
    var activeDetail by remember { mutableStateOf<MapDetailGroup?>(null) }
    val primaryActionIsStop = routePlanningStep == RoutePlanningStep.Inactive &&
        isActive && activeCoordinate == pendingCoordinate
    val primaryActionEnabled = isSelectingRouteWaypoint || isMapReady && !isStarting && !isPlanningRoute &&
        !(routePlanningStep == RoutePlanningStep.Preview && isActive && !isRouteSession)
    BackHandler(enabled = activeDetail != null) { activeDetail = null }
    Surface(
        modifier = modifier
            .widthIn(max = panelMaxWidth)
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f),
                shape = MaterialTheme.shapes.extraLarge,
            ),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
        tonalElevation = 5.dp,
        shadowElevation = 10.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MapDockButton(Icons.Filled.Search, R.string.map_group_search) { activeDetail = MapDetailGroup.Search }
                MapDockButton(Icons.Filled.Favorite, R.string.map_group_places) { activeDetail = MapDetailGroup.Places }
                MapDockButton(Icons.Filled.PlayArrow, R.string.map_group_route) { activeDetail = MapDetailGroup.Route }
                MapDockButton(Icons.Filled.MoreVert, R.string.map_group_more) { activeDetail = MapDetailGroup.More }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = {
                        if (isSelectingRouteWaypoint) {
                            onCancelRouteWaypointSelection()
                        } else when (routePlanningStep) {
                            RoutePlanningStep.SelectStart,
                            RoutePlanningStep.SelectDestination,
                            -> onClearRoute()
                            RoutePlanningStep.ReadyToPreview, RoutePlanningStep.Planning -> onPlanRoute()
                            RoutePlanningStep.Preview -> when {
                                isRouteSession -> onPauseResumeRoute()
                                else -> onStartRoute()
                            }
                            RoutePlanningStep.Inactive -> when {
                                isActive && activeCoordinate != pendingCoordinate -> onApply()
                                isActive -> onStop()
                                else -> onStart()
                            }
                        }
                    },
                    enabled = primaryActionEnabled,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = MaterialTheme.shapes.large,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (primaryActionIsStop) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    ),
                ) {
                    if (isPlanningRoute && !isSelectingRouteWaypoint) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else Text(
                        stringResource(
                            if (isSelectingRouteWaypoint) {
                                R.string.action_cancel_route_stop_selection
                            } else when (routePlanningStep) {
                                RoutePlanningStep.SelectStart,
                                RoutePlanningStep.SelectDestination,
                                -> R.string.action_cancel
                                RoutePlanningStep.ReadyToPreview, RoutePlanningStep.Planning -> R.string.action_preview_bicycle_route
                                RoutePlanningStep.Preview -> if (isRouteSession) {
                                    if (routePaused) R.string.action_resume_route else R.string.action_pause_route
                                } else R.string.action_start_route_simulation
                                RoutePlanningStep.Inactive -> when {
                                    isActive && activeCoordinate != pendingCoordinate -> R.string.action_apply_new_location
                                    isActive -> R.string.action_stop
                                    else -> R.string.action_start_mock
                                }
                            },
                        ),
                        maxLines = 1,
                    )
                }
                if (isActive && !primaryActionIsStop) {
                    OutlinedButton(onClick = onStop, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.action_stop))
                    }
                }
            }
        }
    }
    activeDetail?.let { detailGroup ->
        ModalBottomSheet(
            onDismissRequest = { activeDetail = null },
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
                Text(
                    stringResource(
                        when (detailGroup) {
                            MapDetailGroup.Search -> R.string.map_group_search
                            MapDetailGroup.Places -> R.string.map_group_places
                            MapDetailGroup.Route -> R.string.map_group_route
                            MapDetailGroup.More -> R.string.map_group_more
                        },
                    ),
                    style = MaterialTheme.typography.titleMedium,
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
            if (automaticJourneyRecoveryAvailable &&
                (activeDetail == null || activeDetail == MapDetailGroup.Route)
            ) {
                OutlinedButton(
                    onClick = onRegenerateAutomaticJourney,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.action_try_another_landmark)) }
            }
            if (routePlanningStep == RoutePlanningStep.Inactive) {
                if (detailGroup == MapDetailGroup.Search) {
                    if (showCoordinates) Text(
                        stringResource(R.string.selected_coordinate, pendingCoordinate.latitude.formatCoordinate(), pendingCoordinate.longitude.formatCoordinate()),
                        style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    PlaceSearchContent(
                        query = placeSearchQuery,
                        isSearching = isPlaceSearching,
                        results = placeSearchResults,
                        error = placeSearchError,
                        onQueryChanged = onPlaceSearchQueryChanged,
                        onPlaceSelected = { result ->
                            activeDetail = null
                            onPlaceSelected(result)
                        },
                    )
                    TextButton(onClick = { onShowCoordinatesChange(!showCoordinates) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(if (showCoordinates) R.string.action_hide_coordinates else R.string.action_show_coordinates))
                    }
                    TextButton(onClick = {
                        activeDetail = null
                        onUseCurrentLocation()
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.action_use_current_location))
                    }
                }
                if (detailGroup == MapDetailGroup.More) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.setting_show_landmarks), modifier = Modifier.weight(1f))
                        Switch(checked = showLandmarks, onCheckedChange = onShowLandmarksChange)
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onUpdateIntervalChange(if (updateIntervalMillis >= 2_000L) 1_000L else 2_000L) }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.setting_update_interval, updateIntervalMillis / 1_000L))
                        }
                        TextButton(onClick = { onAccuracyChange(if (accuracyMeters >= 10f) 5f else 10f) }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.setting_accuracy, accuracyMeters.toInt()))
                        }
                    }
                    OutlinedButton(
                        onClick = onToggleMapType,
                        enabled = isMapReady,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text(
                            stringResource(
                                if (mapType == MapDisplayType.Light) R.string.action_dark_map
                                else R.string.action_light_map,
                            ),
                        )
                    }
                    OutlinedButton(
                        onClick = onOpenDeveloperOptions,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Filled.Settings, contentDescription = null)
                        Text(
                            stringResource(R.string.action_open_developer_options),
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    if (!compactLayout) {
                        Text(
                            stringResource(R.string.mock_app_setup_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (detailGroup == MapDetailGroup.Places) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(onClick = {
                            activeDetail = null
                            onSaveFavorite()
                        }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.action_save_place), maxLines = 1)
                        }
                        TextButton(onClick = {
                            activeDetail = null
                            onShowFavorites()
                        }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.action_favorites, favoritesCount), maxLines = 1)
                        }
                    }
                    TextButton(onClick = {
                        activeDetail = null
                        onShowRouteLibrary()
                    }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.action_route_library))
                    }
                }
                if (detailGroup == MapDetailGroup.Route && !isStarting && !isActive) {
                    Button(
                        onClick = {
                            activeDetail = null
                            onShowAutoJourney()
                        },
                        enabled = isMapReady,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.action_auto_journey), maxLines = 1) }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                activeDetail = null
                                onBeginRoutePlanning()
                            },
                            enabled = isMapReady,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        ) { Text(stringResource(R.string.action_plan_bicycle_route), maxLines = 1) }
                        OutlinedButton(
                            onClick = {
                                activeDetail = null
                                onShowShapeRoute()
                            },
                            enabled = isMapReady,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        ) { Text(stringResource(R.string.action_shape_route), maxLines = 1) }
                    }
                    Button(
                        onClick = onStart,
                        enabled = isMapReady,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text(stringResource(R.string.action_start_mock), maxLines = 1) }
                }
                if (detailGroup == MapDetailGroup.Route && isActive && !isRouteSession && activeCoordinate != pendingCoordinate) {
                    Button(onClick = onApply, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.action_apply_new_location), maxLines = 1)
                    }
                }
                if (detailGroup == MapDetailGroup.Route && (isStarting || isActive)) {
                    OutlinedButton(onClick = onStop, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.action_stop), maxLines = 1)
                    }
                }
            } else if (detailGroup == MapDetailGroup.Route) {
                activeRouteName?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
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
                                TextButton(onClick = {
                                    activeDetail = null
                                    onAddRouteWaypoint()
                                }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
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
                            RouteWaypointEditor(routeWaypoints, onRemoveRouteWaypoint, onMoveRouteWaypoint)
                        }
                        if (!isRouteSession) {
                            RouteSimulationControls(
                                options = routeOptions,
                                onOptionsChange = onRouteOptionsChange,
                            )
                            Text(
                                stringResource(R.string.route_replace_title),
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = onBeginRoutePlanning, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                    Text(stringResource(R.string.action_plan_bicycle_route), maxLines = 1)
                                }
                                TextButton(onClick = onShowAutoJourney, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                    Text(stringResource(R.string.action_auto_journey), maxLines = 1)
                                }
                                TextButton(onClick = onShowShapeRoute, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                                    Text(stringResource(R.string.action_shape_route), maxLines = 1)
                                }
                            }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = {
                                    activeDetail = null
                                    onAddRouteWaypoint()
                                }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
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
}

private enum class MapDetailGroup {
    Search,
    Places,
    Route,
    More,
}

@Composable
internal fun PlaceSearchContent(
    query: String,
    isSearching: Boolean,
    results: List<PlaceSearchResult>,
    error: PlaceSearchError?,
    onQueryChanged: (String) -> Unit,
    onPlaceSelected: (PlaceSearchResult) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val coordinateResults = results.filter { it.source == PlaceSearchSource.Coordinate }
    val landmarkResults = results.filter { it.source == PlaceSearchSource.Landmark }
    val remoteResults = results.filter { it.source == PlaceSearchSource.Remote }
    val showNicknameHint = !isSearching &&
        looksLikeLandmarkNickname(query) &&
        coordinateResults.isEmpty() &&
        landmarkResults.isEmpty()
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChanged,
        label = { Text(stringResource(R.string.place_search_label)) },
        supportingText = { Text(stringResource(R.string.place_search_privacy)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQueryChanged("") }) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.action_clear_search),
                    )
                }
            }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(
            onSearch = {
                keyboardController?.hide()
                focusManager.clearFocus()
            },
        ),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    if (isSearching) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(
                stringResource(R.string.place_search_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else if (query.trim().length >= 2 && results.isEmpty() && error == null) {
        Text(
            stringResource(R.string.place_search_empty),
            modifier = Modifier.padding(vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    error?.let { searchError ->
        Text(
            stringResource(
                when (searchError) {
                    PlaceSearchError.Network -> R.string.place_search_error_network
                    PlaceSearchError.RateLimited -> R.string.place_search_error_rate
                    PlaceSearchError.InvalidResponse -> R.string.place_search_error_invalid
                },
            ),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (coordinateResults.isNotEmpty()) {
        PlaceSearchSectionHeader(stringResource(R.string.place_search_section_coordinates))
        coordinateResults.forEach { result ->
            PlaceSearchResultButton(result, onPlaceSelected)
        }
    }
    if (landmarkResults.isNotEmpty()) {
        PlaceSearchSectionHeader(stringResource(R.string.place_search_section_landmarks))
        landmarkResults.forEach { result ->
            PlaceSearchResultButton(result, onPlaceSelected)
        }
    }
    if (remoteResults.isNotEmpty()) {
        PlaceSearchSectionHeader(stringResource(R.string.place_search_section_places))
        remoteResults.forEach { result ->
            PlaceSearchResultButton(result, onPlaceSelected)
        }
    }
    if (showNicknameHint) {
        Text(
            stringResource(R.string.place_search_hint_landmark),
            modifier = Modifier.padding(vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PlaceSearchSectionHeader(title: String) {
    Text(
        title,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PlaceSearchResultButton(
    result: PlaceSearchResult,
    onPlaceSelected: (PlaceSearchResult) -> Unit,
) {
    TextButton(
        onClick = { onPlaceSelected(result) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(result.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MapDockButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: Int,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
        ) {
            IconButton(onClick = onClick, modifier = Modifier.size(44.dp)) {
                Icon(
                    imageVector = icon,
                    contentDescription = stringResource(label),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(21.dp),
                )
            }
        }
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
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
