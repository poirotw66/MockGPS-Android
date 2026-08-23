package com.sora.mockgps.core.location

import com.sora.mockgps.core.model.Coordinate
import com.sora.mockgps.core.model.MockError
import com.sora.mockgps.core.model.MockPayload
import com.sora.mockgps.core.model.MockResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MockLocationCoordinatorTest {
    @Test
    fun `start starts all engines and repeated start is idempotent`() = runBlocking {
        val framework = FakeEngine("framework")
        val fused = FakeEngine("fused")
        val coordinator = MockLocationCoordinator(listOf(framework, fused))

        assertTrue(coordinator.start() is MockResult.Success)
        assertTrue(coordinator.start() is MockResult.Success)

        assertEquals(1, framework.starts)
        assertEquals(1, fused.starts)
    }

    @Test
    fun `failed start rolls back engines already started in reverse order`() = runBlocking {
        val events = mutableListOf<String>()
        val first = FakeEngine("first", events = events)
        val second = FakeEngine("second", startFailure = IllegalStateException("not mock app"), events = events)
        val coordinator = MockLocationCoordinator(listOf(first, second))

        val result = coordinator.start()

        assertTrue(result is MockResult.Failure)
        val error = (result as MockResult.Failure).error as MockError.StartFailed
        assertEquals("second", error.engineName)
        assertEquals(listOf("first.start", "second.start", "second.stop", "first.stop"), events)
        assertEquals(1, first.stops)
        assertTrue(coordinator.push(payload()) is MockResult.Failure)
    }

    @Test
    fun `push reports backend failure without skipping remaining engine`() = runBlocking {
        val first = FakeEngine("first", pushFailure = IllegalStateException("framework failed"))
        val second = FakeEngine("second")
        val coordinator = MockLocationCoordinator(listOf(first, second))
        coordinator.start()

        val result = coordinator.push(payload())

        val failure = (result as MockResult.Failure).error as MockError.PushFailed
        assertEquals("first", failure.failures.single().engineName)
        assertEquals(1, second.pushes)
    }

    @Test
    fun `stop is idempotent and tries every engine after stop failure`() = runBlocking {
        val first = FakeEngine("first", stopFailuresBeforeSuccess = 1)
        val second = FakeEngine("second")
        val coordinator = MockLocationCoordinator(listOf(first, second))
        coordinator.start()

        val firstStop = coordinator.stop()
        val secondStop = coordinator.stop()

        assertTrue(firstStop is MockResult.Failure)
        assertTrue((firstStop as MockResult.Failure).error is MockError.StopFailed)
        assertEquals(2, first.stops)
        assertEquals(1, second.stops)
        assertTrue(secondStop is MockResult.Success)
    }

    private fun payload() = MockPayload(
        coordinate = Coordinate(25.033, 121.565),
        accuracyMeters = 5f,
        wallClockMillis = 1,
        elapsedRealtimeNanos = 1,
    )

    private class FakeEngine(
        override val name: String,
        private val startFailure: Throwable? = null,
        private val pushFailure: Throwable? = null,
        private var stopFailuresBeforeSuccess: Int = 0,
        private val events: MutableList<String> = mutableListOf(),
    ) : MockLocationEngine {
        var starts = 0
        var pushes = 0
        var stops = 0

        override suspend fun start() {
            starts++
            events += "$name.start"
            startFailure?.let { throw it }
        }

        override suspend fun push(payload: MockPayload) {
            pushes++
            events += "$name.push"
            pushFailure?.let { throw it }
        }

        override suspend fun stop() {
            stops++
            events += "$name.stop"
            if (stopFailuresBeforeSuccess > 0) {
                stopFailuresBeforeSuccess--
                throw IllegalStateException("cleanup failed")
            }
        }
    }
}
