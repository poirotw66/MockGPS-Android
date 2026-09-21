package com.sora.mockgps.feature.map

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.material.icons.filled.Place
import com.sora.mockgps.route.JoystickSpeed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.sora.mockgps.R
import com.sora.mockgps.core.model.Coordinate
import com.sora.mockgps.feature.search.PlaceSearchResult
import com.sora.mockgps.feature.search.PlaceSearchSource
import com.sora.mockgps.route.PlannedRoute
import com.sora.mockgps.service.RouteCompleted
import com.sora.mockgps.service.RouteFailed
import com.sora.mockgps.service.RouteProgress
import com.sora.mockgps.service.RouteServiceState
import java.util.Locale

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun MapControlPanel(
    pendingCoordinate: Coordinate,
    activeCoordinate: Coordinate?,
    showCoordinates: Boolean,
    showLandmarks: Boolean,
    permissionMessage: String?,
    isResolvingCurrentLocation: Boolean,
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
    automaticJourneyRecoveryKind: AutomaticJourneyRecoveryKind?,
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
    onCycleUpdateInterval: () -> Unit,
    onCycleAccuracy: () -> Unit,
    onToggleMapType: () -> Unit,
    onOpenDeveloperOptions: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onShowSetupGuide: () -> Unit,
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
    joystickMode: Boolean,
    joystickSpeed: JoystickSpeed,
    onJoystickSpeedChange: (JoystickSpeed) -> Unit,
    onStartJoystick: () -> Unit,
    onEnableJoystick: () -> Unit,
    onExitJoystick: () -> Unit,
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
    val detailSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val dockActions = remember(
        routePlanningStep,
        isSelectingRouteWaypoint,
        isMapReady,
        isStarting,
        isPlanningRoute,
        isActive,
        isRouteSession,
        routePaused,
        pendingCoordinate,
        activeCoordinate,
    ) {
        resolveMapDockActions(
            routePlanningStep = routePlanningStep,
            isSelectingRouteWaypoint = isSelectingRouteWaypoint,
            isMapReady = isMapReady,
            isStarting = isStarting,
            isPlanningRoute = isPlanningRoute,
            isActive = isActive,
            isRouteSession = isRouteSession,
            routePaused = routePaused,
            pendingCoordinate = pendingCoordinate,
            activeCoordinate = activeCoordinate,
        )
    }
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
                MapDockButton(Icons.Filled.Place, R.string.map_group_joystick) { activeDetail = MapDetailGroup.Joystick }
                MapDockButton(Icons.Filled.MoreVert, R.string.map_group_more) { activeDetail = MapDetailGroup.More }
            }
            if (dockActions.showPendingApplyHint) {
                Text(
                    stringResource(R.string.pending_apply_hint),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                    maxLines = 2,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val primary = dockActions.primary
                if (primary != null) {
                    Button(
                        onClick = {
                            when (primary) {
                                MapPrimaryAction.CancelWaypointSelection -> onCancelRouteWaypointSelection()
                                MapPrimaryAction.CancelRoutePlanning -> onClearRoute()
                                MapPrimaryAction.PreviewRoute -> onPlanRoute()
                                MapPrimaryAction.StartRoute -> onStartRoute()
                                MapPrimaryAction.PauseRoute, MapPrimaryAction.ResumeRoute -> onPauseResumeRoute()
                                MapPrimaryAction.ApplyLocation -> onApply()
                                MapPrimaryAction.StartMock -> onStart()
                            }
                        },
                        enabled = dockActions.primaryEnabled,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = MaterialTheme.shapes.large,
                    ) {
                        if (isPlanningRoute && primary == MapPrimaryAction.PreviewRoute) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Text(stringResource(primary.labelRes), maxLines = 1)
                        }
                    }
                }
                if (dockActions.showStop) {
                    if (primary == null) {
                        Button(
                            onClick = onStop,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            shape = MaterialTheme.shapes.large,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Text(stringResource(R.string.action_stop), maxLines = 1)
                        }
                    } else {
                        OutlinedButton(onClick = onStop, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.action_stop))
                        }
                    }
                }
            }
        }
    }
    activeDetail?.let { detailGroup ->
        ModalBottomSheet(
            onDismissRequest = { activeDetail = null },
            sheetState = detailSheetState,
            containerColor = MaterialTheme.colorScheme.surface,
        ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
                Text(
                    stringResource(
                        when (detailGroup) {
                            MapDetailGroup.Search -> R.string.map_group_search
                            MapDetailGroup.Places -> R.string.map_group_places
                            MapDetailGroup.Route -> R.string.map_group_route
                            MapDetailGroup.Joystick -> R.string.map_group_joystick
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
            if (isResolvingCurrentLocation) {
                Text(
                    stringResource(R.string.current_location_resolving),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
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
                ) {
                    Text(
                        stringResource(
                            when (automaticJourneyRecoveryKind) {
                                AutomaticJourneyRecoveryKind.AnotherShape -> R.string.action_try_another_shape
                                AutomaticJourneyRecoveryKind.AnotherLandmark,
                                null,
                                -> R.string.action_try_another_landmark
                            },
                        ),
                    )
                }
            }
            if (routePlanningStep == RoutePlanningStep.Inactive) {
                if (detailGroup == MapDetailGroup.Search) {
                    if (showCoordinates) Text(
                        stringResource(R.string.selected_coordinate, pendingCoordinate.latitude.formatCoordinate(), pendingCoordinate.longitude.formatCoordinate()),
                        style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    // Keep quick actions above results so they stay reachable when the list grows.
                    TextButton(onClick = { onShowCoordinatesChange(!showCoordinates) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(if (showCoordinates) R.string.action_hide_coordinates else R.string.action_show_coordinates))
                    }
                    TextButton(onClick = {
                        activeDetail = null
                        onUseCurrentLocation()
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.action_use_current_location))
                    }
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
                        TextButton(onClick = onCycleUpdateInterval, modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(
                                    R.string.setting_update_interval,
                                    formatUpdateIntervalLabel(updateIntervalMillis),
                                ),
                            )
                        }
                        TextButton(onClick = onCycleAccuracy, modifier = Modifier.weight(1f)) {
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
                    OutlinedButton(
                        onClick = onOpenBatterySettings,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.action_open_battery_settings))
                    }
                    TextButton(
                        onClick = onShowSetupGuide,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text(stringResource(R.string.action_show_setup_guide))
                    }
                    Text(
                        stringResource(R.string.setup_guide_is_mock),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                        Text(stringResource(R.string.action_open_route_library))
                    }
                }
                if (detailGroup == MapDetailGroup.Joystick) {
                    Text(
                        stringResource(R.string.joystick_panel_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (isRouteSession) {
                        Text(
                            stringResource(R.string.joystick_unavailable_during_route),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        Text(stringResource(R.string.joystick_speed_label), style = MaterialTheme.typography.labelLarge)
                        JoystickSpeed.entries.chunked(3).forEach { rowSpeeds ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                rowSpeeds.forEach { speed ->
                                    FilterChip(
                                        selected = joystickSpeed == speed,
                                        onClick = { onJoystickSpeedChange(speed) },
                                        label = {
                                            Text(
                                                stringResource(speed.labelResource()),
                                                style = MaterialTheme.typography.labelSmall,
                                            )
                                        },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                repeat(3 - rowSpeeds.size) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                        when {
                            joystickMode && isActive -> {
                                Text(
                                    stringResource(R.string.joystick_active_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                OutlinedButton(
                                    onClick = {
                                        activeDetail = null
                                        onExitJoystick()
                                    },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                ) { Text(stringResource(R.string.action_exit_joystick)) }
                                OutlinedButton(
                                    onClick = onStop,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                ) { Text(stringResource(R.string.action_stop)) }
                            }
                            isActive && !isRouteSession -> {
                                Button(
                                    onClick = {
                                        activeDetail = null
                                        onEnableJoystick()
                                    },
                                    enabled = isMapReady && !isStarting,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                ) { Text(stringResource(R.string.action_enable_joystick)) }
                            }
                            else -> {
                                Button(
                                    onClick = {
                                        activeDetail = null
                                        onStartJoystick()
                                    },
                                    enabled = isMapReady && !isStarting,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                ) { Text(stringResource(R.string.action_start_joystick)) }
                            }
                        }
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

internal fun formatUpdateIntervalLabel(intervalMillis: Long): String =
    if (intervalMillis % 1_000L == 0L) {
        (intervalMillis / 1_000L).toString()
    } else {
        String.format(Locale.US, "%.1f", intervalMillis / 1_000.0)
    }

