package com.sora.mockgps.core.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class RemoteConfigSource {
    None,
    Network,
    Cache,
    Unavailable,
}

/**
 * Public capability flags from GET /v1/config. Missing or expired config keeps local features
 * working, turns ads and new purchases off, and shows online geo as unavailable. This repository
 * never reads or writes entitlements.
 */
data class RemoteCapabilityFlags(
    val searchEnabled: Boolean = false,
    val routingEnabled: Boolean = false,
    val purchasesEnabled: Boolean = false,
    val adsEnabled: Boolean = false,
    val source: RemoteConfigSource = RemoteConfigSource.None,
    val capabilities: GatewayCapabilities? = null,
) {
    val onlineGeoAvailable: Boolean get() = searchEnabled || routingEnabled
}

class GatewayConfigRepository(
    private val fetch: suspend () -> GatewayCapabilities = {
        error("Gateway config fetch is not configured.")
    },
    private val clock: () -> Long = System::currentTimeMillis,
    private val refreshIntervalMillis: Long = REFRESH_INTERVAL_MILLIS,
    private val maxCacheAgeMillis: Long = MAX_CACHE_AGE_MILLIS,
) {
    private val mutex = Mutex()
    private val mutableFlags = MutableStateFlow(RemoteCapabilityFlags())
    val flags: StateFlow<RemoteCapabilityFlags> = mutableFlags.asStateFlow()
    private var cached: GatewayCapabilities? = null
    private var lastAttemptMillis: Long = -1L
    private var lastSuccessMillis: Long = -1L
    var fetchCount: Int = 0
        private set

    suspend fun refreshOnForeground(force: Boolean = false) {
        mutex.withLock {
            val now = clock()
            val usableCache = cached != null && now - cached!!.fetchedAtMillis <= maxCacheAgeMillis
            val successIsFresh = lastSuccessMillis >= 0L &&
                now - lastSuccessMillis < refreshIntervalMillis
            if (!force && usableCache && successIsFresh) {
                publish(now)
                return
            }
            val failedRecently = lastAttemptMillis >= 0L &&
                now - lastAttemptMillis < FAILURE_RETRY_MILLIS
            if (!force && !usableCache && failedRecently) {
                publish(now)
                return
            }
            lastAttemptMillis = now
            fetchCount += 1
            try {
                val fresh = fetch()
                cached = fresh.copy(fetchedAtMillis = now)
                lastSuccessMillis = now
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Keep a still-valid cache; otherwise fall back to local-only flags.
            }
            publish(now)
        }
    }

    private fun publish(now: Long) {
        val snapshot = cached
        val usable = snapshot != null && now - snapshot.fetchedAtMillis <= maxCacheAgeMillis
        if (!usable) {
            if (snapshot != null) cached = null
            mutableFlags.value = RemoteCapabilityFlags(
                source = if (lastAttemptMillis < 0L) {
                    RemoteConfigSource.None
                } else {
                    RemoteConfigSource.Unavailable
                },
            )
            return
        }
        requireNotNull(snapshot)
        val source = if (lastSuccessMillis == lastAttemptMillis && lastSuccessMillis >= 0L) {
            RemoteConfigSource.Network
        } else {
            RemoteConfigSource.Cache
        }
        mutableFlags.value = RemoteCapabilityFlags(
            searchEnabled = snapshot.searchEnabled,
            routingEnabled = snapshot.routingEnabled,
            purchasesEnabled = snapshot.purchasesEnabled,
            adsEnabled = snapshot.adsEnabled,
            source = source,
            capabilities = snapshot,
        )
    }

    companion object {
        const val REFRESH_INTERVAL_MILLIS = 6 * 60 * 60 * 1000L
        const val MAX_CACHE_AGE_MILLIS = 24 * 60 * 60 * 1000L
        const val FAILURE_RETRY_MILLIS = 30_000L
    }
}
