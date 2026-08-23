package com.sora.mockgps.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import com.google.android.gms.location.LocationServices
import com.sora.mockgps.R
import com.sora.mockgps.core.location.FrameworkMockEngine
import com.sora.mockgps.core.location.FusedMockEngine
import com.sora.mockgps.core.location.MockLocationCoordinator
import com.sora.mockgps.core.model.Coordinate
import com.sora.mockgps.core.model.MockClock
import com.sora.mockgps.core.model.MockPayloadFactory
import com.sora.mockgps.core.model.MockPayloadOptions
import com.sora.mockgps.core.model.MockResult
import com.sora.mockgps.route.RoutePlayback
import com.sora.mockgps.route.RoutePlaybackMode
import com.sora.mockgps.route.RoutePlaybackSnapshot
import com.sora.mockgps.route.RoutePolyline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns a single static-location or route-playback session. It never restarts itself after process death.
 * Starting this service is only supported from a visible user interaction.
 */
class MockLocationForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sessionMutex = Mutex()
    private val cleanupMutex = Mutex()
    private var sessionJob: Job? = null
    @Volatile
    private var sessionCoordinate: Coordinate? = null
    @Volatile
    private var routePlayback: RoutePlayback? = null
    private var cleanedUp = true

    private val coordinator by lazy {
        MockLocationCoordinator(
            listOf(
                FrameworkMockEngine(getSystemService(Context.LOCATION_SERVICE) as LocationManager),
                FusedMockEngine(LocationServices.getFusedLocationProviderClient(this)),
            ),
        )
    }
    private val payloadFactory = MockPayloadFactory(SystemMockClock)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP, ACTION_STOP_ROUTE -> requestStop()
            ACTION_START -> startSession(intent.coordinateOrDefault())
            ACTION_UPDATE -> updateSession(intent.coordinateOrDefault())
            ACTION_START_ROUTE -> intent.routeRequestOrNull()?.let { request ->
                startRouteSession(request.route, request.speedMetersPerSecond)
            } ?: rejectRouteStart()
            ACTION_PAUSE_ROUTE -> pauseRoute()
            ACTION_RESUME_ROUTE -> resumeRoute()
            else -> requestStop()
        }
        return START_NOT_STICKY
    }

    private fun startSession(coordinate: Coordinate) {
        if (sessionJob?.isActive == true) return

        try {
            MockLocationNotification.createChannel(this)
            // Android requires this immediately after a foreground-service start, before I/O.
            promoteToForeground(coordinate)
        } catch (failure: Throwable) {
            publishState(MockServiceState.Error(failure.userFacingMessage(getString(R.string.mock_operation_failed))))
            stopSelf()
            return
        }

        sessionCoordinate = coordinate
        routePlayback = null
        sessionJob = serviceScope.launch {
            sessionMutex.withLock {
                cleanedUp = false
                publishState(MockServiceState.Starting(coordinate))
                var terminalError: String? = null
                try {
                    when (val result = coordinator.start()) {
                        is MockResult.Failure -> throw MockSessionException(result.error.toString())
                        is MockResult.Success -> Unit
                    }
                    publishState(MockServiceState.Active(coordinate))

                    while (isActive) {
                        val payloadCoordinate = sessionCoordinate ?: coordinate
                        val payload = when (val result = payloadFactory.create(payloadCoordinate)) {
                            is MockResult.Success -> result.value
                            is MockResult.Failure -> throw MockSessionException(result.error.toString())
                        }
                        when (val result = coordinator.push(payload)) {
                            is MockResult.Success -> Unit
                            is MockResult.Failure -> throw MockSessionException(result.error.toString())
                        }
                        delay(UPDATE_INTERVAL_MILLIS)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    terminalError = failure.userFacingMessage(getString(R.string.mock_operation_failed))
                } finally {
                    finishSession(terminalError)
                }
            }
        }
    }

    /** Updates an existing session only after an explicit UI confirmation. */
    private fun updateSession(coordinate: Coordinate) {
        if (sessionJob?.isActive != true) return
        if (routePlayback != null) return
        sessionCoordinate = coordinate
        publishState(MockServiceState.Active(coordinate))
        runCatching { promoteToForeground(coordinate) }
    }

    private fun startRouteSession(route: RoutePolyline, speedMetersPerSecond: Double) {
        if (sessionJob?.isActive == true) return
        val playback = try {
            RoutePlayback(route, speedMetersPerSecond)
        } catch (failure: IllegalArgumentException) {
            publishState(MockServiceState.Error(failure.userFacingMessage(getString(R.string.mock_operation_failed))))
            stopSelf()
            return
        }
        startRouteSession(playback)
    }

    private fun startRouteSession(playback: RoutePlayback) {
        if (sessionJob?.isActive == true) return
        val initialPosition = playback.snapshot().position
        val initialCoordinate = initialPosition.coordinate
        try {
            MockLocationNotification.createChannel(this)
            promoteToForeground(initialCoordinate)
        } catch (failure: Throwable) {
            publishState(MockServiceState.Error(failure.userFacingMessage(getString(R.string.mock_operation_failed))))
            stopSelf()
            return
        }

        sessionCoordinate = initialCoordinate
        routePlayback = playback
        sessionJob = serviceScope.launch {
            sessionMutex.withLock {
                cleanedUp = false
                publishState(MockServiceState.Starting(initialCoordinate))
                var terminalError: String? = null
                try {
                    when (val result = coordinator.start()) {
                        is MockResult.Failure -> throw MockSessionException(result.error.toString())
                        is MockResult.Success -> Unit
                    }
                    playback.start(SystemClock.elapsedRealtime())

                    while (isActive) {
                        val snapshot = playback.advance(SystemClock.elapsedRealtime())
                        sessionCoordinate = snapshot.position.coordinate
                        when (snapshot.mode) {
                            RoutePlaybackMode.PAUSED -> {
                                publishRoutePaused(snapshot)
                                delay(PAUSED_POLL_INTERVAL_MILLIS)
                                continue
                            }
                            RoutePlaybackMode.STOPPED -> break
                            else -> Unit
                        }

                        val payload = when (
                            val result = payloadFactory.create(
                                coordinate = snapshot.position.coordinate,
                                options = MockPayloadOptions(
                                    speedMetersPerSecond = snapshot.speedMetersPerSecond.toFloat(),
                                    bearingDegrees = snapshot.position.bearingDegrees,
                                ),
                            )
                        ) {
                            is MockResult.Success -> result.value
                            is MockResult.Failure -> throw MockSessionException(result.error.toString())
                        }
                        when (val result = coordinator.push(payload)) {
                            is MockResult.Success -> Unit
                            is MockResult.Failure -> throw MockSessionException(result.error.toString())
                        }

                        if (snapshot.mode == RoutePlaybackMode.REACHED_END) {
                            promoteToForeground(snapshot.position.coordinate)
                            publishState(MockServiceState.Active(snapshot.position.coordinate))
                            break
                        }
                        val current = playback.snapshot()
                        when (current.mode) {
                            RoutePlaybackMode.PAUSED -> publishRoutePaused(current)
                            RoutePlaybackMode.STOPPED -> break
                            else -> publishRouteActive(current)
                        }
                        promoteToForeground(sessionCoordinate ?: snapshot.position.coordinate)
                        delay(UPDATE_INTERVAL_MILLIS)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    terminalError = failure.userFacingMessage(getString(R.string.mock_operation_failed))
                } finally {
                    finishSession(terminalError)
                }
            }
        }
    }

    private fun pauseRoute() {
        val playback = routePlayback ?: return
        val snapshot = playback.pause(SystemClock.elapsedRealtime())
        if (snapshot.mode != RoutePlaybackMode.PAUSED) return
        sessionCoordinate = snapshot.position.coordinate
        publishRoutePaused(snapshot)
        runCatching { promoteToForeground(snapshot.position.coordinate) }
    }

    private fun resumeRoute() {
        val playback = routePlayback ?: return
        val snapshot = playback.resume(SystemClock.elapsedRealtime())
        if (snapshot.mode != RoutePlaybackMode.RUNNING) return
        sessionCoordinate = snapshot.position.coordinate
        publishRouteActive(snapshot)
        runCatching { promoteToForeground(snapshot.position.coordinate) }
    }

    private fun publishRouteActive(snapshot: RoutePlaybackSnapshot) {
        publishState(MockServiceState.Active(snapshot.position.coordinate))
    }

    private fun publishRoutePaused(snapshot: RoutePlaybackSnapshot) {
        publishState(MockServiceState.Active(snapshot.position.coordinate))
    }

    private fun rejectRouteStart() {
        if (sessionJob?.isActive == true) return
        publishState(MockServiceState.Error(getString(R.string.mock_operation_failed)))
        stopSelf()
    }

    private fun requestStop() {
        val job = sessionJob
        serviceScope.launch {
            job?.cancelAndJoin()
            // A coroutine cancelled before entering its body does not execute its finally block.
            // This second, idempotent pass covers that race and preserves any cleanup error.
            finishSession(
                (mutableState.value as? MockServiceState.Error)?.message,
            )
        }
    }

    private suspend fun cleanup(): String? = cleanupMutex.withLock {
        if (cleanedUp) return@withLock null
        when (val result = coordinator.stop()) {
            is MockResult.Success -> {
                cleanedUp = true
                null
            }
            is MockResult.Failure -> result.error.toString()
        }
    }

    private suspend fun finishSession(terminalError: String?) {
        var cancellation: CancellationException? = null
        var cleanupError: String? = null

        // A transient provider/Play-services failure must not leave mock mode enabled.
        // Engines that fail stop remain registered in the coordinator, so retry once.
        var cleanupAttempt = 0
        while (cleanupAttempt < CLEANUP_ATTEMPTS) {
            cleanupAttempt++
            cleanupError = try {
                withContext(NonCancellable) { cleanup() }
            } catch (cancelled: CancellationException) {
                cancellation = cancelled
                getString(R.string.mock_cleanup_cancelled)
            } catch (failure: Throwable) {
                failure.userFacingMessage(getString(R.string.mock_operation_failed))
            }
            if (cleanupError == null || cancellation != null) break
        }

        val finalError = listOfNotNull(terminalError, cleanupError).joinToString(separator = "\n").ifBlank { null }
        publishState(finalError?.let(MockServiceState::Error) ?: MockServiceState.Idle)
        routePlayback?.stop()
        routePlayback = null
        sessionCoordinate = null
        demoteAndStop()
        cancellation?.let { throw it }
    }

    private fun promoteToForeground(coordinate: Coordinate) {
        val notification = MockLocationNotification.build(this, coordinate.latitude, coordinate.longitude)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                MockLocationNotification.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(MockLocationNotification.NOTIFICATION_ID, notification)
        }
    }

    private fun demoteAndStop() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        sessionJob?.cancel()
        // Normal Stop/failure paths finish cleanup before stopSelf(). Do not block the main
        // thread here: Google Task listeners may need it in order to finish FLP cleanup.
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun Intent.coordinateOrDefault(): Coordinate {
        val latitude = getDoubleExtra(EXTRA_LATITUDE, DEFAULT_COORDINATE.latitude)
        val longitude = getDoubleExtra(EXTRA_LONGITUDE, DEFAULT_COORDINATE.longitude)
        return Coordinate(latitude, longitude).takeIf {
            it.latitude.isFinite() && it.latitude in -90.0..90.0 &&
                it.longitude.isFinite() && it.longitude in -180.0..180.0
        } ?: DEFAULT_COORDINATE
    }

    private class MockSessionException(message: String) : IllegalStateException(message)

    companion object {
        const val ACTION_START = "com.sora.mockgps.action.START"
        const val ACTION_STOP = "com.sora.mockgps.action.STOP"
        const val ACTION_UPDATE = "com.sora.mockgps.action.UPDATE"
        const val ACTION_START_ROUTE = "com.sora.mockgps.action.START_ROUTE"
        const val ACTION_PAUSE_ROUTE = "com.sora.mockgps.action.PAUSE_ROUTE"
        const val ACTION_RESUME_ROUTE = "com.sora.mockgps.action.RESUME_ROUTE"
        const val ACTION_STOP_ROUTE = "com.sora.mockgps.action.STOP_ROUTE"
        const val EXTRA_LATITUDE = "com.sora.mockgps.extra.LATITUDE"
        const val EXTRA_LONGITUDE = "com.sora.mockgps.extra.LONGITUDE"
        const val EXTRA_ROUTE_LATITUDES = "com.sora.mockgps.extra.ROUTE_LATITUDES"
        const val EXTRA_ROUTE_LONGITUDES = "com.sora.mockgps.extra.ROUTE_LONGITUDES"
        const val EXTRA_ROUTE_SPEED_METERS_PER_SECOND = "com.sora.mockgps.extra.ROUTE_SPEED_METERS_PER_SECOND"
        const val UPDATE_INTERVAL_MILLIS = 1_000L
        const val PAUSED_POLL_INTERVAL_MILLIS = 200L
        private const val CLEANUP_ATTEMPTS = 2
        val DEFAULT_COORDINATE = Coordinate(25.033964, 121.564468)

        private val mutableState = MutableStateFlow<MockServiceState>(MockServiceState.Idle)
        val state: StateFlow<MockServiceState> = mutableState.asStateFlow()

        fun startIntent(context: Context, coordinate: Coordinate = DEFAULT_COORDINATE): Intent =
            Intent(context, MockLocationForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_LATITUDE, coordinate.latitude)
                putExtra(EXTRA_LONGITUDE, coordinate.longitude)
            }

        fun stopIntent(context: Context): Intent =
            Intent(context, MockLocationForegroundService::class.java).apply { action = ACTION_STOP }

        fun updateIntent(context: Context, coordinate: Coordinate): Intent =
            Intent(context, MockLocationForegroundService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_LATITUDE, coordinate.latitude)
                putExtra(EXTRA_LONGITUDE, coordinate.longitude)
            }

        fun startRouteIntent(
            context: Context,
            points: List<Coordinate>,
            speedMetersPerSecond: Double = RoutePlayback.BICYCLE_SPEED_METERS_PER_SECOND,
        ): Intent {
            require(points.size <= MAX_ROUTE_POINTS) { "Route has too many points" }
            val route = RoutePolyline(points)
            require(speedMetersPerSecond.isFinite() && speedMetersPerSecond > 0.0) { "Speed must be positive" }
            return Intent(context, MockLocationForegroundService::class.java).apply {
                action = ACTION_START_ROUTE
                putExtra(EXTRA_ROUTE_LATITUDES, route.points.map(Coordinate::latitude).toDoubleArray())
                putExtra(EXTRA_ROUTE_LONGITUDES, route.points.map(Coordinate::longitude).toDoubleArray())
                putExtra(EXTRA_ROUTE_SPEED_METERS_PER_SECOND, speedMetersPerSecond)
            }
        }

        fun pauseRouteIntent(context: Context): Intent =
            Intent(context, MockLocationForegroundService::class.java).apply { action = ACTION_PAUSE_ROUTE }

        fun resumeRouteIntent(context: Context): Intent =
            Intent(context, MockLocationForegroundService::class.java).apply { action = ACTION_RESUME_ROUTE }

        fun stopRouteIntent(context: Context): Intent =
            Intent(context, MockLocationForegroundService::class.java).apply { action = ACTION_STOP_ROUTE }

        private fun publishState(state: MockServiceState) {
            mutableState.value = state
        }

        private const val MAX_ROUTE_POINTS = 1_000
    }

    private fun Intent.routeRequestOrNull(): RouteStartRequest? {
        val latitudes = getDoubleArrayExtra(EXTRA_ROUTE_LATITUDES) ?: return null
        val longitudes = getDoubleArrayExtra(EXTRA_ROUTE_LONGITUDES) ?: return null
        if (latitudes.size != longitudes.size) return null
        val speedMetersPerSecond = getDoubleExtra(
            EXTRA_ROUTE_SPEED_METERS_PER_SECOND,
            RoutePlayback.BICYCLE_SPEED_METERS_PER_SECOND,
        )
        return runCatching {
            RouteStartRequest(
                route = RoutePolyline(latitudes.indices.map { Coordinate(latitudes[it], longitudes[it]) }),
                speedMetersPerSecond = speedMetersPerSecond.also {
                    require(it.isFinite() && it > 0.0) { "Speed must be positive" }
                },
            )
        }.getOrNull()
    }

    private data class RouteStartRequest(
        val route: RoutePolyline,
        val speedMetersPerSecond: Double,
    )
}

private fun Throwable.userFacingMessage(fallback: String): String =
    message?.takeIf(String::isNotBlank) ?: fallback

private object SystemMockClock : MockClock {
    override fun currentTimeMillis(): Long = System.currentTimeMillis()
    override fun elapsedRealtimeNanos(): Long = SystemClock.elapsedRealtimeNanos()
}
