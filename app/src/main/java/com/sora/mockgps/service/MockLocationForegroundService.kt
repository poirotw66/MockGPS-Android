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
import com.sora.mockgps.route.AccelerationModel
import com.sora.mockgps.route.GpsDriftConfiguration
import com.sora.mockgps.route.MovementProfile
import com.sora.mockgps.route.RouteExecution
import com.sora.mockgps.route.RouteExecutionMode
import com.sora.mockgps.route.RouteExecutionSnapshot
import com.sora.mockgps.route.RouteExecutionState
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
    private val sessionGate = ServiceSessionGate()
    private var sessionJob: Job? = null
    @Volatile
    private var sessionCoordinate: Coordinate? = null
    @Volatile
    private var routeExecution: RouteExecution? = null
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
            ACTION_STOP, ACTION_STOP_ROUTE -> requestStop(intent.commandToken())
            ACTION_START -> startSession(intent.coordinateOrDefault())
            ACTION_UPDATE -> updateSession(intent.coordinateOrDefault(), intent.commandToken())
            ACTION_START_ROUTE -> intent.routeRequestOrNull()?.let { request ->
                startRouteSession(
                    route = request.route,
                    movementProfile = request.movementProfile,
                    accelerationModel = request.accelerationModel,
                    executionMode = request.executionMode,
                    gpsDrift = request.gpsDrift,
                )
            } ?: rejectRouteStart()
            ACTION_PAUSE_ROUTE -> pauseRoute(intent.commandToken())
            ACTION_RESUME_ROUTE -> resumeRoute(intent.commandToken())
            else -> requestStop(intent?.commandToken())
        }
        return START_NOT_STICKY
    }

    private fun startSession(coordinate: Coordinate) {
        if (sessionJob?.isActive == true || sessionGate.current() != null) return
        val sessionToken = sessionGate.begin()

        try {
            MockLocationNotification.createChannel(this)
            // Android requires this immediately after a foreground-service start, before I/O.
            promoteToForeground(coordinate, routeAction = null)
        } catch (failure: Throwable) {
            publishState(MockServiceState.Error(failure.userFacingMessage(getString(R.string.mock_operation_failed))))
            sessionGate.end(sessionToken)
            stopSelf()
            return
        }

        sessionCoordinate = coordinate
        routeExecution = null
        publishRouteState(RouteServiceState.Idle)
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
                    finishSession(sessionToken, terminalError)
                }
            }
        }
    }

    /** Updates an existing session only after an explicit UI confirmation. */
    private fun updateSession(coordinate: Coordinate, commandToken: ServiceSessionToken?) {
        if (sessionJob?.isActive != true) return
        if (!sessionGate.accepts(commandToken)) return
        if (routeExecution != null) return
        sessionCoordinate = coordinate
        publishState(MockServiceState.Active(coordinate))
        runCatching { promoteToForeground(coordinate) }
    }

    private fun startRouteSession(
        route: RoutePolyline,
        movementProfile: MovementProfile,
        accelerationModel: AccelerationModel,
        executionMode: RouteExecutionMode,
        gpsDrift: GpsDriftConfiguration,
    ) {
        if (sessionJob?.isActive == true) return
        val execution = try {
            RouteExecution(route, movementProfile, accelerationModel, executionMode, gpsDrift)
        } catch (failure: IllegalArgumentException) {
            val message = failure.userFacingMessage(getString(R.string.mock_operation_failed))
            publishState(MockServiceState.Error(message))
            publishRouteState(RouteFailed(message, lastProgress = null))
            stopSelf()
            return
        }
        startRouteSession(execution)
    }

    private fun startRouteSession(execution: RouteExecution) {
        if (sessionJob?.isActive == true || sessionGate.current() != null) return
        val sessionToken = sessionGate.begin()
        val initialCoordinate = execution.snapshot().reportedCoordinate
        try {
            MockLocationNotification.createChannel(this)
            promoteToForeground(initialCoordinate, RouteNotificationAction.Pause, sessionToken)
        } catch (failure: Throwable) {
            val message = failure.userFacingMessage(getString(R.string.mock_operation_failed))
            publishState(MockServiceState.Error(message))
            publishRouteState(RouteFailed(message, lastProgress = null))
            sessionGate.end(sessionToken)
            stopSelf()
            return
        }

        sessionCoordinate = initialCoordinate
        routeExecution = execution
        publishRouteState(RouteStarting(execution.snapshot().toRouteProgress(), sessionToken))
        sessionJob = serviceScope.launch {
            sessionMutex.withLock {
                cleanedUp = false
                publishState(MockServiceState.Starting(initialCoordinate))
                var terminalError: String? = null
                var completedRouteState: RouteCompleted? = null
                try {
                    when (val result = coordinator.start()) {
                        is MockResult.Failure -> throw MockSessionException(result.error.toString())
                        is MockResult.Success -> Unit
                    }
                    execution.start(SystemClock.elapsedRealtime())
                    publishRouteState(RouteRunning(execution.snapshot().toRouteProgress(), sessionToken))

                    while (isActive) {
                        val snapshot = execution.advance(SystemClock.elapsedRealtime())
                        sessionCoordinate = snapshot.reportedCoordinate
                        when (snapshot.state) {
                            RouteExecutionState.PAUSED -> {
                                publishRoutePaused(snapshot, sessionToken)
                                delay(PAUSED_POLL_INTERVAL_MILLIS)
                                continue
                            }
                            RouteExecutionState.STOPPED -> break
                            else -> Unit
                        }

                        val payload = when (
                            val result = payloadFactory.create(
                                coordinate = snapshot.reportedCoordinate,
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

                        if (snapshot.state == RouteExecutionState.REACHED_END) {
                            promoteToForeground(snapshot.reportedCoordinate, sessionToken = sessionToken)
                            publishState(MockServiceState.Active(snapshot.reportedCoordinate))
                            completedRouteState = RouteCompleted(
                                snapshot.toRouteProgress(),
                                sessionToken,
                            )
                            publishRouteState(completedRouteState)
                            break
                        }
                        val current = execution.snapshot()
                        when (current.state) {
                            RouteExecutionState.PAUSED -> publishRoutePaused(current, sessionToken)
                            RouteExecutionState.STOPPED -> break
                            else -> publishRouteActive(current, sessionToken)
                        }
                        promoteToForeground(sessionCoordinate ?: snapshot.reportedCoordinate, sessionToken = sessionToken)
                        delay(UPDATE_INTERVAL_MILLIS)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    terminalError = failure.userFacingMessage(getString(R.string.mock_operation_failed))
                } finally {
                    finishSession(sessionToken, terminalError, completedRouteState)
                }
            }
        }
    }

    private fun pauseRoute(commandToken: ServiceSessionToken?) {
        if (!sessionGate.accepts(commandToken)) return
        val execution = routeExecution ?: return
        val snapshot = execution.pause(SystemClock.elapsedRealtime())
        if (snapshot.state != RouteExecutionState.PAUSED) return
        sessionCoordinate = snapshot.reportedCoordinate
        publishRoutePaused(snapshot, requireNotNull(sessionGate.current()))
        runCatching {
            promoteToForeground(snapshot.reportedCoordinate, RouteNotificationAction.Resume, sessionGate.current())
        }
    }

    private fun resumeRoute(commandToken: ServiceSessionToken?) {
        if (!sessionGate.accepts(commandToken)) return
        val execution = routeExecution ?: return
        val snapshot = execution.resume(SystemClock.elapsedRealtime())
        if (snapshot.state != RouteExecutionState.RUNNING) return
        sessionCoordinate = snapshot.reportedCoordinate
        publishRouteActive(snapshot, requireNotNull(sessionGate.current()))
        runCatching {
            promoteToForeground(snapshot.reportedCoordinate, RouteNotificationAction.Pause, sessionGate.current())
        }
    }

    private fun publishRouteActive(snapshot: RouteExecutionSnapshot, sessionToken: ServiceSessionToken) {
        publishState(MockServiceState.Active(snapshot.reportedCoordinate))
        publishRouteState(RouteRunning(snapshot.toRouteProgress(), sessionToken))
    }

    private fun publishRoutePaused(snapshot: RouteExecutionSnapshot, sessionToken: ServiceSessionToken) {
        publishState(MockServiceState.Active(snapshot.reportedCoordinate))
        publishRouteState(RoutePaused(snapshot.toRouteProgress(), sessionToken))
    }

    private fun rejectRouteStart() {
        if (sessionJob?.isActive == true) return
        val message = getString(R.string.mock_operation_failed)
        publishState(MockServiceState.Error(message))
        publishRouteState(RouteFailed(message, lastProgress = null))
        stopSelf()
    }

    private fun requestStop(commandToken: ServiceSessionToken?) {
        if (!sessionGate.accepts(commandToken)) return
        val sessionToken = sessionGate.current() ?: return
        val job = sessionJob
        serviceScope.launch {
            job?.cancelAndJoin()
            // A coroutine cancelled before entering its body does not execute its finally block.
            // This second, idempotent pass covers that race and preserves any cleanup error.
            finishSession(
                sessionToken,
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

    private suspend fun finishSession(
        sessionToken: ServiceSessionToken,
        terminalError: String?,
        completedRouteState: RouteCompleted? = null,
    ) {
        // A delayed Stop coroutine from an old session must never clean up a replacement's
        // providers or demote its foreground notification.
        if (sessionGate.current() != sessionToken) return
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

        if (sessionGate.current() != sessionToken) {
            cancellation?.let { throw it }
            return
        }

        val finalError = listOfNotNull(terminalError, cleanupError).joinToString(separator = "\n").ifBlank { null }
        publishState(finalError?.let(MockServiceState::Error) ?: MockServiceState.Idle)
        val finishingExecution = routeExecution
        val finalRouteState = when {
            completedRouteState != null && finalError == null -> completedRouteState
            finishingExecution != null && finalError != null -> RouteFailed(
                message = finalError,
                lastProgress = finishingExecution.snapshot().toRouteProgress(),
                sessionToken = sessionToken,
            )
            else -> RouteServiceState.Idle
        }
        publishRouteState(finalRouteState)
        finishingExecution?.stop()
        if (routeExecution === finishingExecution) routeExecution = null
        sessionCoordinate = null
        sessionGate.end(sessionToken)
        demoteAndStop()
        cancellation?.let { throw it }
    }

    private fun promoteToForeground(
        coordinate: Coordinate,
        routeAction: RouteNotificationAction? = routeNotificationAction(),
        sessionToken: ServiceSessionToken? = sessionGate.current(),
    ) {
        val notification = MockLocationNotification.build(
            this,
            coordinate.latitude,
            coordinate.longitude,
            routeAction,
            sessionToken,
        )
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

    private fun routeNotificationAction(): RouteNotificationAction? = when (routeExecution?.snapshot()?.state) {
        RouteExecutionState.READY,
        RouteExecutionState.RUNNING -> RouteNotificationAction.Pause
        RouteExecutionState.PAUSED -> RouteNotificationAction.Resume
        RouteExecutionState.REACHED_END,
        RouteExecutionState.STOPPED,
        null -> null
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
        /** Legacy input retained for callers that supplied a raw speed in m/s. */
        const val EXTRA_ROUTE_SPEED_METERS_PER_SECOND = "com.sora.mockgps.extra.ROUTE_SPEED_METERS_PER_SECOND"
        const val EXTRA_ROUTE_MOVEMENT_PROFILE = "com.sora.mockgps.extra.ROUTE_MOVEMENT_PROFILE"
        const val EXTRA_ROUTE_SPEED_KILOMETERS_PER_HOUR = "com.sora.mockgps.extra.ROUTE_SPEED_KILOMETERS_PER_HOUR"
        const val EXTRA_ROUTE_ACCELERATION_METERS_PER_SECOND_SQUARED =
            "com.sora.mockgps.extra.ROUTE_ACCELERATION_METERS_PER_SECOND_SQUARED"
        const val EXTRA_ROUTE_DECELERATION_METERS_PER_SECOND_SQUARED =
            "com.sora.mockgps.extra.ROUTE_DECELERATION_METERS_PER_SECOND_SQUARED"
        const val EXTRA_ROUTE_EXECUTION_MODE = "com.sora.mockgps.extra.ROUTE_EXECUTION_MODE"
        const val EXTRA_ROUTE_GPS_DRIFT_METERS = "com.sora.mockgps.extra.ROUTE_GPS_DRIFT_METERS"
        const val EXTRA_ROUTE_GPS_DRIFT_SEED = "com.sora.mockgps.extra.ROUTE_GPS_DRIFT_SEED"
        const val EXTRA_SESSION_ID = "com.sora.mockgps.extra.SESSION_ID"
        const val EXTRA_SESSION_GENERATION = "com.sora.mockgps.extra.SESSION_GENERATION"
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

        fun stopIntent(context: Context, sessionToken: ServiceSessionToken? = null): Intent =
            Intent(context, MockLocationForegroundService::class.java).apply {
                action = ACTION_STOP
                putSessionToken(sessionToken)
            }

        fun updateIntent(
            context: Context,
            coordinate: Coordinate,
            sessionToken: ServiceSessionToken? = null,
        ): Intent =
            Intent(context, MockLocationForegroundService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_LATITUDE, coordinate.latitude)
                putExtra(EXTRA_LONGITUDE, coordinate.longitude)
                putSessionToken(sessionToken)
            }

        fun startRouteIntent(
            context: Context,
            points: List<Coordinate>,
            speedMetersPerSecond: Double = MovementProfile.Bicycle.metersPerSecond,
        ): Intent {
            require(speedMetersPerSecond.isFinite() && speedMetersPerSecond > 0.0) { "Speed must be positive" }
            val profile = if (speedMetersPerSecond == MovementProfile.Bicycle.metersPerSecond) {
                MovementProfile.Bicycle
            } else {
                MovementProfile.Custom(speedMetersPerSecond * KILOMETERS_PER_HOUR_PER_METER_PER_SECOND)
            }
            return startRouteIntent(
                context = context,
                points = points,
                movementProfile = profile,
                accelerationModel = AccelerationModel.Instant,
                executionMode = RouteExecutionMode.StopAtEnd,
                gpsDrift = GpsDriftConfiguration(),
            ).apply {
                putExtra(EXTRA_ROUTE_SPEED_METERS_PER_SECOND, speedMetersPerSecond)
            }
        }

        fun startRouteIntent(
            context: Context,
            points: List<Coordinate>,
            movementProfile: MovementProfile,
            accelerationModel: AccelerationModel = AccelerationModel.Instant,
            executionMode: RouteExecutionMode = RouteExecutionMode.StopAtEnd,
            gpsDrift: GpsDriftConfiguration = GpsDriftConfiguration(),
        ): Intent {
            require(points.size <= MAX_ROUTE_POINTS) { "Route has too many points" }
            val route = RoutePolyline(points)
            return Intent(context, MockLocationForegroundService::class.java).apply {
                action = ACTION_START_ROUTE
                putExtra(EXTRA_ROUTE_LATITUDES, route.points.map(Coordinate::latitude).toDoubleArray())
                putExtra(EXTRA_ROUTE_LONGITUDES, route.points.map(Coordinate::longitude).toDoubleArray())
                putExtra(EXTRA_ROUTE_MOVEMENT_PROFILE, movementProfile.intentName())
                putExtra(EXTRA_ROUTE_SPEED_KILOMETERS_PER_HOUR, movementProfile.kilometersPerHour)
                putExtra(
                    EXTRA_ROUTE_ACCELERATION_METERS_PER_SECOND_SQUARED,
                    accelerationModel.accelerationMetersPerSecondSquared,
                )
                putExtra(
                    EXTRA_ROUTE_DECELERATION_METERS_PER_SECOND_SQUARED,
                    accelerationModel.decelerationMetersPerSecondSquared,
                )
                putExtra(EXTRA_ROUTE_EXECUTION_MODE, executionMode.name)
                putExtra(EXTRA_ROUTE_GPS_DRIFT_METERS, gpsDrift.maximumHorizontalMeters)
                putExtra(EXTRA_ROUTE_GPS_DRIFT_SEED, gpsDrift.seed)
            }
        }

        fun pauseRouteIntent(context: Context, sessionToken: ServiceSessionToken? = null): Intent =
            Intent(context, MockLocationForegroundService::class.java).apply {
                action = ACTION_PAUSE_ROUTE
                putSessionToken(sessionToken)
            }

        fun resumeRouteIntent(context: Context, sessionToken: ServiceSessionToken? = null): Intent =
            Intent(context, MockLocationForegroundService::class.java).apply {
                action = ACTION_RESUME_ROUTE
                putSessionToken(sessionToken)
            }

        fun stopRouteIntent(context: Context, sessionToken: ServiceSessionToken? = null): Intent =
            Intent(context, MockLocationForegroundService::class.java).apply {
                action = ACTION_STOP_ROUTE
                putSessionToken(sessionToken)
            }

        private fun publishState(state: MockServiceState) {
            mutableState.value = state
        }

        private val mutableRouteState = MutableStateFlow<RouteServiceState>(RouteServiceState.Idle)
        val routeState: StateFlow<RouteServiceState> = mutableRouteState.asStateFlow()

        private fun publishRouteState(state: RouteServiceState) {
            mutableRouteState.value = state
        }

        private const val MAX_ROUTE_POINTS = 2_000
        private const val KILOMETERS_PER_HOUR_PER_METER_PER_SECOND = 3.6
    }

    private fun Intent.routeRequestOrNull(): RouteStartRequest? {
        val latitudes = getDoubleArrayExtra(EXTRA_ROUTE_LATITUDES) ?: return null
        val longitudes = getDoubleArrayExtra(EXTRA_ROUTE_LONGITUDES) ?: return null
        if (latitudes.size != longitudes.size) return null
        return runCatching {
            RouteStartRequest(
                route = RoutePolyline(latitudes.indices.map { Coordinate(latitudes[it], longitudes[it]) }),
                movementProfile = routeMovementProfile(),
                accelerationModel = AccelerationModel(
                    accelerationMetersPerSecondSquared = getDoubleExtra(
                        EXTRA_ROUTE_ACCELERATION_METERS_PER_SECOND_SQUARED,
                        Double.POSITIVE_INFINITY,
                    ),
                    decelerationMetersPerSecondSquared = getDoubleExtra(
                        EXTRA_ROUTE_DECELERATION_METERS_PER_SECOND_SQUARED,
                        Double.POSITIVE_INFINITY,
                    ),
                ),
                executionMode = getStringExtra(EXTRA_ROUTE_EXECUTION_MODE)
                    ?.let(RouteExecutionMode::valueOf) ?: RouteExecutionMode.StopAtEnd,
                gpsDrift = GpsDriftConfiguration(
                    maximumHorizontalMeters = getDoubleExtra(EXTRA_ROUTE_GPS_DRIFT_METERS, 0.0),
                    seed = getLongExtra(EXTRA_ROUTE_GPS_DRIFT_SEED, 0L),
                ),
            )
        }.getOrNull()
    }

    private fun Intent.routeMovementProfile(): MovementProfile {
        val speedKilometersPerHour = getDoubleExtra(EXTRA_ROUTE_SPEED_KILOMETERS_PER_HOUR, Double.NaN)
        return when (getStringExtra(EXTRA_ROUTE_MOVEMENT_PROFILE)) {
            PROFILE_WALK -> MovementProfile.Walk
            PROFILE_RUN -> MovementProfile.Run
            PROFILE_BICYCLE -> MovementProfile.Bicycle
            PROFILE_DRIVING -> MovementProfile.Driving(
                if (speedKilometersPerHour.isNaN()) {
                    MovementProfile.DEFAULT_DRIVING_KILOMETERS_PER_HOUR
                } else {
                    speedKilometersPerHour
                },
            )
            PROFILE_CUSTOM -> MovementProfile.Custom(requireValidRouteSpeed(speedKilometersPerHour))
            null -> {
                val legacySpeedMetersPerSecond = getDoubleExtra(
                    EXTRA_ROUTE_SPEED_METERS_PER_SECOND,
                    MovementProfile.Bicycle.metersPerSecond,
                )
                if (legacySpeedMetersPerSecond == MovementProfile.Bicycle.metersPerSecond) {
                    MovementProfile.Bicycle
                } else {
                    MovementProfile.Custom(
                        requireValidRouteSpeed(
                            legacySpeedMetersPerSecond * KILOMETERS_PER_HOUR_PER_METER_PER_SECOND,
                        ),
                    )
                }
            }
            else -> throw IllegalArgumentException("Unknown movement profile")
        }
    }

    private data class RouteStartRequest(
        val route: RoutePolyline,
        val movementProfile: MovementProfile,
        val accelerationModel: AccelerationModel,
        val executionMode: RouteExecutionMode,
        val gpsDrift: GpsDriftConfiguration,
    )

    private fun Intent.commandToken(): ServiceSessionToken? {
        val hasSessionId = hasExtra(EXTRA_SESSION_ID)
        val hasGeneration = hasExtra(EXTRA_SESSION_GENERATION)
        if (!hasSessionId && !hasGeneration) return null
        if (!hasSessionId || !hasGeneration) return ServiceSessionToken.INVALID
        val sessionId = getStringExtra(EXTRA_SESSION_ID).orEmpty()
        val generation = getLongExtra(EXTRA_SESSION_GENERATION, 0L)
        return runCatching { ServiceSessionToken(sessionId, generation) }
            .getOrElse { ServiceSessionToken.INVALID }
    }
}

private fun MovementProfile.intentName(): String = when (this) {
    MovementProfile.Walk -> PROFILE_WALK
    MovementProfile.Run -> PROFILE_RUN
    MovementProfile.Bicycle -> PROFILE_BICYCLE
    is MovementProfile.Driving -> PROFILE_DRIVING
    is MovementProfile.Custom -> PROFILE_CUSTOM
}

private fun requireValidRouteSpeed(speedKilometersPerHour: Double): Double {
    require(speedKilometersPerHour.isFinite() && speedKilometersPerHour > 0.0) { "Speed must be positive" }
    return speedKilometersPerHour
}

private const val PROFILE_WALK = "walk"
private const val PROFILE_RUN = "run"
private const val PROFILE_BICYCLE = "bicycle"
private const val PROFILE_DRIVING = "driving"
private const val PROFILE_CUSTOM = "custom"

private fun Intent.putSessionToken(sessionToken: ServiceSessionToken?) {
    sessionToken ?: return
    putExtra(MockLocationForegroundService.EXTRA_SESSION_ID, sessionToken.sessionId)
    putExtra(MockLocationForegroundService.EXTRA_SESSION_GENERATION, sessionToken.generation)
}

private fun Throwable.userFacingMessage(fallback: String): String =
    message?.takeIf(String::isNotBlank) ?: fallback

private object SystemMockClock : MockClock {
    override fun currentTimeMillis(): Long = System.currentTimeMillis()
    override fun elapsedRealtimeNanos(): Long = SystemClock.elapsedRealtimeNanos()
}
