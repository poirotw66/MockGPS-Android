package com.sora.mockgps.core.location

import android.annotation.SuppressLint
import android.location.Location
import android.location.LocationManager
import com.google.android.gms.location.FusedLocationProviderClient
import com.sora.mockgps.core.model.MockPayload
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Google Play services mock backend. Mock mode is always disabled by [stop]. */
@SuppressLint("MissingPermission")
class FusedMockEngine(
    private val fusedLocationClient: FusedLocationProviderClient,
) : MockLocationEngine {
    override val name: String = "fused-location"

    private var started = false
    private var mockModeRequested = false

    override suspend fun start() {
        if (started) return
        mockModeRequested = true
        try {
            fusedLocationClient.setMockMode(true).awaitResult()
            started = true
        } catch (failure: Throwable) {
            try {
                withContext(NonCancellable) { fusedLocationClient.setMockMode(false).awaitResult() }
                mockModeRequested = false
            } catch (cleanupFailure: Throwable) {
                failure.addSuppressed(cleanupFailure)
            } finally {
                started = false
            }
            throw failure
        }
    }

    override suspend fun push(payload: MockPayload) {
        check(started) { "Fused mock engine has not been started" }
        fusedLocationClient.setMockLocation(payload.toFusedLocation()).awaitResult()
    }

    override suspend fun stop() {
        if (!mockModeRequested) return
        fusedLocationClient.setMockMode(false).awaitResult()
        started = false
        mockModeRequested = false
    }
}

private fun MockPayload.toFusedLocation(): Location = Location(LocationManager.GPS_PROVIDER).apply {
    latitude = coordinate.latitude
    longitude = coordinate.longitude
    accuracy = accuracyMeters
    time = wallClockMillis
    elapsedRealtimeNanos = this@toFusedLocation.elapsedRealtimeNanos
    altitudeMeters?.let { altitude = it }
    speedMetersPerSecond?.let { speed = it }
    bearingDegrees?.let { bearing = it }
}

private suspend fun <T> com.google.android.gms.tasks.Task<T>.awaitResult(): T =
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
