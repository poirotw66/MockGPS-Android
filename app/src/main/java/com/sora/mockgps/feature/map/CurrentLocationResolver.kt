package com.sora.mockgps.feature.map

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Looper
import androidx.annotation.RequiresApi
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.sora.mockgps.core.model.Coordinate
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

internal data class CandidateFix(
    val latitude: Double,
    val longitude: Double,
    val timeMillis: Long,
    val isMock: Boolean,
) {
    fun toCoordinate(): Coordinate = Coordinate(latitude, longitude)
}

/** Newest non-mock fix whose age is inside [maxAgeMillis]. Mock fixes are never a real position. */
internal fun freshestRealFix(
    fixes: Iterable<CandidateFix>,
    nowMillis: Long,
    maxAgeMillis: Long,
): CandidateFix? = fixes
    .filter { fix -> !fix.isMock && nowMillis - fix.timeMillis in 0..maxAgeMillis }
    .maxByOrNull { it.timeMillis }

internal fun Location.isMockFix(): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && isMock) return true
    @Suppress("DEPRECATION")
    return isFromMockProvider
}

internal fun Location.toCoordinate(): Coordinate = Coordinate(latitude, longitude)

internal fun Location.toCandidateFix(): CandidateFix = CandidateFix(
    latitude = latitude,
    longitude = longitude,
    timeMillis = time,
    isMock = isMockFix(),
)

@SuppressLint("MissingPermission")
internal fun LocationManager.collectLastKnownLocations(): List<Location> =
    listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        .mapNotNull { provider -> runCatching { getLastKnownLocation(provider) }.getOrNull() }

/**
 * Reads the device position, never a fix this app injected.
 *
 * Fused getCurrentLocation returns null or the mock fix after this process has enabled mock mode.
 * Hardware GPS and network are requested together, and a fresh GPS fix wins over a faster coarse fix.
 */
@SuppressLint("MissingPermission")
internal suspend fun Context.resolveCurrentCoordinate(
    timeoutMillis: Long = CURRENT_LOCATION_REQUEST_TIMEOUT_MILLIS,
): Coordinate? {
    if (!hasLocationPermission()) return null
    val manager = getSystemService(LocationManager::class.java) ?: return null
    if (!manager.isLocationEnabled) return null
    val cached = manager.collectLastKnownLocations().map { it.toCandidateFix() }
    freshestRealFix(cached, System.currentTimeMillis(), CURRENT_LOCATION_FRESH_MAX_AGE_MILLIS)
        ?.let { return it.toCoordinate() }
    val requested = requestFirstRealLocation(
        timeoutMillis = timeoutMillis,
        gps = { manager.awaitCurrentLocation(LocationManager.GPS_PROVIDER) },
        others = listOf(
            { manager.awaitCurrentLocation(LocationManager.NETWORK_PROVIDER) },
            { awaitFusedCurrentLocation() },
        ),
    )
    if (requested != null) return requested.toCoordinate()
    return freshestRealFix(
        manager.collectLastKnownLocations().map { it.toCandidateFix() },
        System.currentTimeMillis(),
        CURRENT_LOCATION_FALLBACK_MAX_AGE_MILLIS,
    )?.toCoordinate()
}

/**
 * Returns the first real fix. When GPS is still running, a coarse fix waits briefly so a satellite
 * fix can replace it. A GPS result that is only the test provider is ignored by the caller filter.
 */
internal suspend fun requestFirstRealLocation(
    timeoutMillis: Long,
    gps: suspend () -> Location?,
    others: List<suspend () -> Location?>,
): Location? = withTimeoutOrNull(timeoutMillis) {
    coroutineScope {
        val channel = Channel<Location>(capacity = others.size + 1)
        val gpsJob = launch {
            val location = runCatching { gps() }.getOrNull()
            if (location != null && !location.isMockFix()) channel.trySend(location)
        }
        val otherJobs = others.map { source ->
            launch {
                val location = runCatching { source() }.getOrNull()
                if (location != null && !location.isMockFix()) channel.trySend(location)
            }
        }
        launch {
            gpsJob.join()
            otherJobs.forEach { it.join() }
            channel.close()
        }
        val first = channel.receiveCatching().getOrNull() ?: return@coroutineScope null
        val preferred = if (first.provider == LocationManager.GPS_PROVIDER || !gpsJob.isActive) {
            first
        } else {
            withTimeoutOrNull(REAL_GPS_PREFERENCE_MILLIS) {
                var pending: Location? = null
                while (pending?.provider != LocationManager.GPS_PROVIDER) {
                    pending = channel.receiveCatching().getOrNull() ?: break
                }
                pending?.takeIf { it.provider == LocationManager.GPS_PROVIDER }
            } ?: first
        }
        gpsJob.cancel()
        otherJobs.forEach { it.cancel() }
        preferred
    }
}

@SuppressLint("MissingPermission")
private suspend fun LocationManager.awaitCurrentLocation(provider: String): Location? {
    if (!isProviderUsable(provider)) return null
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        awaitCurrentLocationApi30(provider)
    } else {
        awaitSingleUpdate(provider)
    }
}

private fun LocationManager.isProviderUsable(provider: String): Boolean =
    runCatching { isProviderEnabled(provider) }.getOrDefault(false)

@RequiresApi(Build.VERSION_CODES.R)
@SuppressLint("MissingPermission")
private suspend fun LocationManager.awaitCurrentLocationApi30(provider: String): Location? =
    suspendCancellableCoroutine { continuation ->
        val signal = CancellationSignal()
        val resumed = AtomicBoolean(false)
        fun finish(location: Location?) {
            if (resumed.compareAndSet(false, true) && continuation.isActive) continuation.resume(location)
        }
        continuation.invokeOnCancellation { signal.cancel() }
        try {
            getCurrentLocation(provider, signal, InlineExecutor) { location -> finish(location) }
        } catch (failure: SecurityException) {
            finish(null)
        } catch (failure: IllegalArgumentException) {
            finish(null)
        }
    }

@SuppressLint("MissingPermission")
private suspend fun LocationManager.awaitSingleUpdate(provider: String): Location? =
    suspendCancellableCoroutine { continuation ->
        val resumed = AtomicBoolean(false)
        lateinit var listener: LocationListener
        fun finish(location: Location?) {
            if (!resumed.compareAndSet(false, true)) return
            runCatching { removeUpdates(listener) }
            if (continuation.isActive) continuation.resume(location)
        }
        listener = object : LocationListener {
            override fun onLocationChanged(location: Location) = finish(location)
            @Deprecated("Deprecated in Java")
            @Suppress("OVERRIDE_DEPRECATION")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = finish(null)
        }
        continuation.invokeOnCancellation {
            if (resumed.compareAndSet(false, true)) runCatching { removeUpdates(listener) }
        }
        try {
            requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
        } catch (failure: SecurityException) {
            finish(null)
        } catch (failure: IllegalArgumentException) {
            finish(null)
        }
    }

private suspend fun Context.awaitFusedCurrentLocation(): Location? {
    val cancellation = CancellationTokenSource()
    return try {
        suspendCancellableCoroutine { continuation ->
            val resumed = AtomicBoolean(false)
            fun finish(location: Location?) {
                if (resumed.compareAndSet(false, true) && continuation.isActive) continuation.resume(location)
            }
            continuation.invokeOnCancellation { cancellation.cancel() }
            LocationServices.getFusedLocationProviderClient(applicationContext)
                .getCurrentLocation(
                    CurrentLocationRequest.Builder()
                        .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                        .setMaxUpdateAgeMillis(0L)
                        .setDurationMillis(CURRENT_LOCATION_REQUEST_TIMEOUT_MILLIS)
                        .build(),
                    cancellation.token,
                )
                .addOnSuccessListener { location -> finish(location) }
                .addOnFailureListener { finish(null) }
                .addOnCanceledListener { finish(null) }
        }
    } finally {
        cancellation.cancel()
    }
}

private object InlineExecutor : Executor {
    override fun execute(command: Runnable) = command.run()
}

internal const val CURRENT_LOCATION_FRESH_MAX_AGE_MILLIS = 60_000L
internal const val CURRENT_LOCATION_FALLBACK_MAX_AGE_MILLIS = 120_000L
internal const val CURRENT_LOCATION_REQUEST_TIMEOUT_MILLIS = 10_000L
private const val REAL_GPS_PREFERENCE_MILLIS = 1_500L
