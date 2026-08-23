package com.sora.mockgps.core.location

import android.annotation.SuppressLint
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import androidx.annotation.RequiresApi
import com.sora.mockgps.core.model.MockPayload

/** Android framework test-provider backend, intentionally limited to GPS_PROVIDER. */
class FrameworkMockEngine(
    private val locationManager: LocationManager,
) : MockLocationEngine {
    override val name: String = "framework-gps"

    private var started = false
    private var providerRegistered = false

    override suspend fun start() {
        if (started) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                locationManager.addTestProvider(LocationManager.GPS_PROVIDER, providerProperties())
            } else {
                addLegacyTestProvider()
            }
            providerRegistered = true
            locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
            started = true
        } catch (failure: Throwable) {
            // A partially registered provider must not survive a failed start.
            cleanupAfterFailedStart(failure)
            throw failure
        }
    }

    override suspend fun push(payload: MockPayload) {
        check(started) { "Framework mock engine has not been started" }
        locationManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, payload.toAndroidLocation())
    }

    override suspend fun stop() {
        if (!started && !providerRegistered) return

        var firstFailure: Throwable? = null
        var providerRemoved = !providerRegistered
        try {
            if (providerRegistered) {
                locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false)
            }
        } catch (failure: Throwable) {
            firstFailure = failure
        } finally {
            try {
                if (providerRegistered) {
                    locationManager.removeTestProvider(LocationManager.GPS_PROVIDER)
                    providerRemoved = true
                }
            } catch (failure: Throwable) {
                if (firstFailure == null) firstFailure = failure else firstFailure.addSuppressed(failure)
            } finally {
                started = false
                providerRegistered = !providerRemoved
            }
        }
        firstFailure?.let { throw it }
    }

    private fun cleanupAfterFailedStart(originalFailure: Throwable) {
        if (!providerRegistered) return
        try {
            locationManager.removeTestProvider(LocationManager.GPS_PROVIDER)
            providerRegistered = false
        } catch (cleanupFailure: Throwable) {
            originalFailure.addSuppressed(cleanupFailure)
        } finally {
            started = false
        }
    }

    /** ProviderProperties constants are compile-time inlined, so this remains safe on API 26-30. */
    @SuppressLint("InlinedApi")
    @Suppress("DEPRECATION")
    private fun addLegacyTestProvider() {
        locationManager.addTestProvider(
            LocationManager.GPS_PROVIDER,
            false,
            true,
            false,
            false,
            true,
            true,
            true,
            ProviderProperties.POWER_USAGE_LOW,
            ProviderProperties.ACCURACY_FINE,
        )
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun providerProperties(): ProviderProperties = ProviderProperties.Builder()
        .setAccuracy(ProviderProperties.ACCURACY_FINE)
        .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
        .setHasMonetaryCost(false)
        .setHasAltitudeSupport(true)
        .setHasSpeedSupport(true)
        .setHasBearingSupport(true)
        .build()
}

private fun MockPayload.toAndroidLocation(): Location = Location(LocationManager.GPS_PROVIDER).apply {
    latitude = coordinate.latitude
    longitude = coordinate.longitude
    accuracy = accuracyMeters
    time = wallClockMillis
    elapsedRealtimeNanos = this@toAndroidLocation.elapsedRealtimeNanos
    altitudeMeters?.let { altitude = it }
    speedMetersPerSecond?.let { speed = it }
    bearingDegrees?.let { bearing = it }
}
