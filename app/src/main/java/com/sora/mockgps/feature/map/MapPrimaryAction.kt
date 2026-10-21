package com.sora.mockgps.feature.map

import androidx.annotation.StringRes
import com.sora.mockgps.R
import com.sora.mockgps.core.model.Coordinate

/**
 * Dock action for a static mock-location session.
 * Route preview, start, pause, and resume stay in the route sheet.
 */
internal sealed class MapPrimaryAction {
    data object StartMock : MapPrimaryAction()
    data object ApplyLocation : MapPrimaryAction()

    @get:StringRes
    val labelRes: Int
        get() = when (this) {
            StartMock -> R.string.action_start_mock
            ApplyLocation -> R.string.action_apply_new_location
        }
}

internal data class MapDockActions(
    val primary: MapPrimaryAction?,
    val showStop: Boolean,
    val primaryEnabled: Boolean,
    val showPendingApplyHint: Boolean,
)

internal fun resolveMapDockActions(
    isMapReady: Boolean,
    isStarting: Boolean,
    isActive: Boolean,
    isRouteSession: Boolean,
    pendingCoordinate: Coordinate,
    activeCoordinate: Coordinate?,
): MapDockActions {
    val pendingDiffers = isActive &&
        !isRouteSession &&
        activeCoordinate != null &&
        activeCoordinate != pendingCoordinate
    val primary = when {
        pendingDiffers -> MapPrimaryAction.ApplyLocation
        isActive -> null
        else -> MapPrimaryAction.StartMock
    }
    val primaryEnabled = isMapReady && !isStarting
    return MapDockActions(
        primary = primary,
        showStop = isActive,
        primaryEnabled = primaryEnabled,
        showPendingApplyHint = pendingDiffers,
    )
}
