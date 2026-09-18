package com.sora.mockgps.feature.map

import androidx.annotation.StringRes
import com.sora.mockgps.R
import com.sora.mockgps.core.model.Coordinate

/**
 * Single primary dock action derived from route planning + mock session state.
 * Stop is never the primary when Apply / Pause / Resume is available — it stays secondary.
 */
internal sealed class MapPrimaryAction {
    data object StartMock : MapPrimaryAction()
    data object ApplyLocation : MapPrimaryAction()
    data object CancelRoutePlanning : MapPrimaryAction()
    data object CancelWaypointSelection : MapPrimaryAction()
    data object PreviewRoute : MapPrimaryAction()
    data object StartRoute : MapPrimaryAction()
    data object PauseRoute : MapPrimaryAction()
    data object ResumeRoute : MapPrimaryAction()

    @get:StringRes
    val labelRes: Int
        get() = when (this) {
            StartMock -> R.string.action_start_mock
            ApplyLocation -> R.string.action_apply_new_location
            CancelRoutePlanning -> R.string.action_cancel
            CancelWaypointSelection -> R.string.action_cancel_route_stop_selection
            PreviewRoute -> R.string.action_preview_bicycle_route
            StartRoute -> R.string.action_start_route_simulation
            PauseRoute -> R.string.action_pause_route
            ResumeRoute -> R.string.action_resume_route
        }
}

internal data class MapDockActions(
    val primary: MapPrimaryAction?,
    val showStop: Boolean,
    val primaryEnabled: Boolean,
    val showPendingApplyHint: Boolean,
)

internal fun resolveMapDockActions(
    routePlanningStep: RoutePlanningStep,
    isSelectingRouteWaypoint: Boolean,
    isMapReady: Boolean,
    isStarting: Boolean,
    isPlanningRoute: Boolean,
    isActive: Boolean,
    isRouteSession: Boolean,
    routePaused: Boolean,
    pendingCoordinate: Coordinate,
    activeCoordinate: Coordinate?,
): MapDockActions {
    val pendingDiffers = isActive &&
        !isRouteSession &&
        activeCoordinate != null &&
        activeCoordinate != pendingCoordinate
    val primary = when {
        isSelectingRouteWaypoint -> MapPrimaryAction.CancelWaypointSelection
        routePlanningStep == RoutePlanningStep.SelectStart ||
            routePlanningStep == RoutePlanningStep.SelectDestination -> MapPrimaryAction.CancelRoutePlanning
        routePlanningStep == RoutePlanningStep.ReadyToPreview ||
            routePlanningStep == RoutePlanningStep.Planning -> MapPrimaryAction.PreviewRoute
        routePlanningStep == RoutePlanningStep.Preview -> when {
            isRouteSession && routePaused -> MapPrimaryAction.ResumeRoute
            isRouteSession -> MapPrimaryAction.PauseRoute
            else -> MapPrimaryAction.StartRoute
        }
        pendingDiffers -> MapPrimaryAction.ApplyLocation
        isActive -> null // Stop is the only session control while pending matches active
        else -> MapPrimaryAction.StartMock
    }
    val showStop = isActive && primary != null || isActive && primary == null
    val primaryEnabled = when (primary) {
        null -> false
        MapPrimaryAction.CancelWaypointSelection -> true
        MapPrimaryAction.PreviewRoute -> isMapReady && !isStarting && !isPlanningRoute
        MapPrimaryAction.StartRoute -> isMapReady && !isStarting && !isPlanningRoute &&
            !(isActive && !isRouteSession)
        else -> isMapReady && !isStarting && !isPlanningRoute
    }
    return MapDockActions(
        primary = primary,
        showStop = isActive,
        primaryEnabled = primaryEnabled,
        showPendingApplyHint = pendingDiffers,
    )
}
