package com.sora.mockgps.core.location

import com.sora.mockgps.core.model.EngineFailure
import com.sora.mockgps.core.model.EngineOperation
import com.sora.mockgps.core.model.MockError
import com.sora.mockgps.core.model.MockPayload
import com.sora.mockgps.core.model.MockResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Coordinates multiple backends as one atomic mock-location session. */
class MockLocationCoordinator(
    private val engines: List<MockLocationEngine>,
) {
    private val operationMutex = Mutex()
    private val activeEngines = mutableListOf<MockLocationEngine>()

    suspend fun start(): MockResult<Unit> = operationMutex.withLock {
        if (activeEngines.size == engines.size) return@withLock MockResult.Success(Unit)

        // This should only occur after an interrupted/failed lifecycle; restore a clean baseline.
        if (activeEngines.isNotEmpty()) {
            val cleanupFailures = stopActiveEngines()
            if (cleanupFailures.isNotEmpty()) {
                return@withLock MockResult.Failure(
                    MockError.StartFailed(
                        engineName = "cleanup",
                        cause = IllegalStateException("Previous mock session could not be cleaned up"),
                        rollbackFailures = cleanupFailures,
                    ),
                )
            }
        }

        for (engine in engines) {
            try {
                engine.start()
                activeEngines += engine
            } catch (cancelled: CancellationException) {
                cleanupAfterCancelledStart(engine, cancelled)
                throw cancelled
            } catch (failure: Throwable) {
                // start() can fail after partially enabling its backend. Include the failed
                // engine in rollback; stop() is required to be idempotent.
                activeEngines += engine
                val rollbackFailures = rollbackStartedEngines(failure)
                return@withLock MockResult.Failure(
                    MockError.StartFailed(engine.name, failure, rollbackFailures),
                )
            }
        }
        MockResult.Success(Unit)
    }

    suspend fun push(payload: MockPayload): MockResult<Unit> = operationMutex.withLock {
        if (activeEngines.size != engines.size) return@withLock MockResult.Failure(MockError.NotStarted)

        val failures = activeEngines.mapNotNull { engine ->
            try {
                engine.push(payload)
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                EngineFailure(engine.name, EngineOperation.PUSH, failure)
            }
        }
        if (failures.isEmpty()) MockResult.Success(Unit) else MockResult.Failure(MockError.PushFailed(failures))
    }

    suspend fun stop(): MockResult<Unit> = operationMutex.withLock {
        val failures = stopActiveEngines()
        if (failures.isEmpty()) MockResult.Success(Unit) else MockResult.Failure(MockError.StopFailed(failures))
    }

    private suspend fun rollbackStartedEngines(originalFailure: Throwable): List<EngineFailure> {
        val failures = stopActiveEngines()
        failures.forEach { originalFailure.addSuppressed(it.cause) }
        return failures
    }

    private suspend fun cleanupAfterCancelledStart(
        failedEngine: MockLocationEngine,
        cancellation: CancellationException,
    ) {
        // An engine may have enabled its backend immediately before its suspend call was cancelled.
        activeEngines += failedEngine
        val failures = stopActiveEngines()
        failures.forEach { cancellation.addSuppressed(it.cause) }
    }

    private suspend fun stopActiveEngines(): List<EngineFailure> {
        val enginesToStop = activeEngines.asReversed().toList()
        activeEngines.clear()
        val failures = mutableListOf<EngineFailure>()
        val enginesNeedingRetry = mutableListOf<MockLocationEngine>()
        var cancellation: CancellationException? = null
        enginesToStop.forEach { engine ->
            try {
                withContext(NonCancellable) { engine.stop() }
            } catch (cancelled: CancellationException) {
                // Keep cleaning up every backend, then propagate cancellation unchanged.
                if (cancellation == null) cancellation = cancelled
                enginesNeedingRetry += engine
            } catch (failure: Throwable) {
                failures += EngineFailure(engine.name, EngineOperation.STOP, failure)
                enginesNeedingRetry += engine
            }
        }
        // Restore original start order so a later stop retries in reverse order again.
        activeEngines += enginesNeedingRetry.asReversed()
        cancellation?.let { throw it }
        return failures
    }
}
