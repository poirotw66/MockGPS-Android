package com.sora.mockgps.feature.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.app.ForegroundServiceStartNotAllowedException
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.sora.mockgps.core.io.readBoundedUtf8
import com.sora.mockgps.core.model.Coordinate
import com.sora.mockgps.route.JoystickSpeed
import com.sora.mockgps.service.MockLocationForegroundService
import com.sora.mockgps.service.ServiceSessionToken
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

internal fun Context.requiredRuntimePermissions(notificationPermissionHandled: Boolean): List<String> = buildList {
    if (!hasLocationPermission()) {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        !notificationPermissionHandled && !isGranted(Manifest.permission.POST_NOTIFICATIONS)
    ) add(Manifest.permission.POST_NOTIFICATIONS)
}

internal fun Context.hasLocationPermission(): Boolean =
    isGranted(Manifest.permission.ACCESS_FINE_LOCATION) || isGranted(Manifest.permission.ACCESS_COARSE_LOCATION)

internal fun Context.lastKnownCoordinate(): Coordinate? {
    if (!hasLocationPermission()) return null
    val manager = getSystemService(LocationManager::class.java) ?: return null
    return selectBestKnownLocation(manager.collectLastKnownLocations())?.toCoordinate()
}

@SuppressLint("MissingPermission")
internal suspend fun Context.resolveCurrentCoordinate(
    timeoutMillis: Long = CURRENT_LOCATION_REQUEST_TIMEOUT_MILLIS,
): Coordinate? {
    if (!hasLocationPermission()) return null
    val manager = getSystemService(LocationManager::class.java) ?: return null
    val lastKnown = selectBestKnownLocation(manager.collectLastKnownLocations())
    if (lastKnown != null && lastKnown.isFresh(maxAgeMillis = CURRENT_LOCATION_FRESH_MAX_AGE_MILLIS)) {
        return lastKnown.toCoordinate()
    }
    val appContext = applicationContext
    val requested = withTimeoutOrNull(timeoutMillis) {
        runCatching {
            LocationServices.getFusedLocationProviderClient(appContext)
                .getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, CancellationTokenSource().token)
                .awaitLocationTask()
        }.getOrNull()
    }
    return requested?.toCoordinate() ?: lastKnown?.toCoordinate()
}

private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitLocationTask(): T =
    suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { value ->
            if (continuation.isActive) continuation.resume(value)
        }
        addOnFailureListener { error ->
            if (continuation.isActive) continuation.resumeWithException(error)
        }
        addOnCanceledListener {
            continuation.cancel()
        }
    }

internal fun Context.startMockService(
    coordinate: Coordinate,
    updateIntervalMillis: Long,
    accuracyMeters: Float,
): ForegroundServiceStartOutcome = foregroundServiceStartOutcome {
        startForegroundService(
            MockLocationForegroundService.startIntent(this, coordinate, updateIntervalMillis, accuracyMeters),
        )
}

internal fun Context.setJoystickVector(
    bearing: Float,
    magnitude: Float,
    speed: JoystickSpeed,
    sessionToken: ServiceSessionToken?,
) {
    startService(
        MockLocationForegroundService.setJoystickIntent(this, bearing, magnitude, speed, sessionToken),
    )
}

internal fun Context.startRouteService(
    points: List<Coordinate>,
    options: RouteSimulationOptions,
    updateIntervalMillis: Long,
    accuracyMeters: Float,
): ForegroundServiceStartOutcome = foregroundServiceStartOutcome {
        startForegroundService(
            MockLocationForegroundService.startRouteIntent(
                context = this,
                points = points,
                movementProfile = options.movementProfile(),
                accelerationModel = options.accelerationModel(),
                executionMode = options.mode,
                gpsDrift = options.gpsDrift(),
                updateIntervalMillis = updateIntervalMillis,
                accuracyMeters = accuracyMeters,
            ),
        )
}

internal sealed interface ForegroundServiceStartOutcome {
    data object Started : ForegroundServiceStartOutcome
    data class NotAllowed(val cause: Throwable) : ForegroundServiceStartOutcome
    data class SecurityOrSetup(val cause: SecurityException) : ForegroundServiceStartOutcome
    data class Failed(val cause: Throwable) : ForegroundServiceStartOutcome
}

internal inline fun foregroundServiceStartOutcome(
    start: () -> Unit,
): ForegroundServiceStartOutcome = try {
    start()
    ForegroundServiceStartOutcome.Started
} catch (failure: Throwable) {
    classifyForegroundServiceStartFailure(failure)
}

internal fun classifyForegroundServiceStartFailure(failure: Throwable): ForegroundServiceStartOutcome = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && failure is ForegroundServiceStartNotAllowedException ->
        ForegroundServiceStartOutcome.NotAllowed(failure)
    failure is SecurityException -> ForegroundServiceStartOutcome.SecurityOrSetup(failure)
    else -> ForegroundServiceStartOutcome.Failed(failure)
}

internal fun Context.isGranted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

internal fun Context.readText(uri: Uri): String = contentResolver.openInputStream(uri)?.use { stream ->
    stream.readBoundedUtf8(MAX_SAF_IMPORT_BYTES)
} ?: error("The selected file could not be opened.")

internal fun Context.writeText(uri: Uri, content: String) {
    require(content.toByteArray(Charsets.UTF_8).size <= MAX_SAF_EXPORT_BYTES) { "Export is too large." }
    val stream = contentResolver.openOutputStream(uri, "wt") ?: error("The selected file could not be written.")
    stream.bufferedWriter().use { it.write(content) }
}

private const val MAX_SAF_IMPORT_BYTES = 2 * 1024 * 1024
private const val MAX_SAF_EXPORT_BYTES = 2 * 1024 * 1024
