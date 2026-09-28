package com.sora.mockgps.feature.search

import com.sora.mockgps.core.model.Coordinate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceSearchControllerTest {
    private val taipei = Coordinate(25.033964, 121.564468)

    @Test
    fun `typing never calls the remote repository`() = runBlocking {
        val repository = RecordingPlaceSearchRepository()
        withController(repository) { controller ->
            controller.onQueryChanged("T")
            controller.onQueryChanged("Ta")
            controller.onQueryChanged("Tai")
            controller.onQueryChanged("Taipei")
            assertEquals(0, repository.calls)
            assertEquals(PlaceSearchPhase.LocalOnly, controller.snapshot.phase)
        }
    }

    @Test
    fun `coordinate query is selected locally and submit is ignored`() = runBlocking {
        val repository = RecordingPlaceSearchRepository()
        withController(repository) { controller ->
            controller.onQueryChanged("25.033964, 121.564468")
            assertEquals(PlaceSearchSource.Coordinate, controller.snapshot.results.single().source)
            assertFalse(controller.submit())
            assertEquals(0, repository.calls)
        }
    }

    @Test
    fun `explicit submit dispatches once and later stale response is ignored`() = runBlocking {
        val firstGate = CompletableDeferred<Unit>()
        val firstRelease = CompletableDeferred<Unit>()
        val repository = GatedPlaceSearchRepository(
            firstGate = firstGate,
            firstRelease = firstRelease,
            firstName = "A",
            secondName = "B",
        )
        withController(repository) { controller ->
            controller.onQueryChanged("Alpha")
            assertTrue(controller.submit())
            firstGate.await()
            controller.onQueryChanged("Beta")
            assertTrue(controller.submit())
            firstRelease.complete(Unit)
            yield()
            repository.secondCompleted.await()
            assertEquals(2, repository.calls)
            assertEquals("B", controller.snapshot.results.single().name)
            assertEquals(PlaceSearchPhase.RemoteResults, controller.snapshot.phase)
        }
    }

    @Test
    fun `in-flight submit rejects a duplicate send`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repository = GatedPlaceSearchRepository(
            firstGate = started,
            firstRelease = release,
            firstName = "Slow",
            secondName = "Unused",
        )
        withController(repository) { controller ->
            controller.onQueryChanged("Tokyo")
            assertTrue(controller.submit())
            started.await()
            assertFalse(controller.submit())
            release.complete(Unit)
            repository.firstCompleted.await()
            assertEquals(1, repository.calls)
        }
    }

    @Test
    fun `blank short and overlong queries are not submitted`() = runBlocking {
        val repository = RecordingPlaceSearchRepository()
        withController(repository) { controller ->
            controller.onQueryChanged(" ")
            assertFalse(controller.submit())
            controller.onQueryChanged("a")
            assertFalse(controller.submit())
            controller.onQueryChanged("台".repeat(201))
            assertFalse(controller.submit())
            assertEquals(0, repository.calls)
            controller.onQueryChanged("台".repeat(200))
            assertTrue(controller.submit())
            yield()
            assertEquals(1, repository.calls)
        }
    }

    @Test
    fun `A then B then A delayed first response does not overwrite`() = runBlocking {
        val firstGate = CompletableDeferred<Unit>()
        val firstRelease = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        val secondRelease = CompletableDeferred<Unit>()
        val repository = SequentialGatedPlaceSearchRepository(
            firstGate = firstGate,
            firstRelease = firstRelease,
            secondGate = secondGate,
            secondRelease = secondRelease,
            firstName = "A-stale",
            secondName = "B-stale",
            thirdName = "A-latest",
        )
        withController(repository) { controller ->
            controller.onQueryChanged("Alpha")
            assertTrue(controller.submit())
            firstGate.await()
            controller.onQueryChanged("Beta")
            assertTrue(controller.submit())
            secondGate.await()
            controller.onQueryChanged("Alpha")
            assertTrue(controller.submit())
            repository.thirdCompleted.await()
            firstRelease.complete(Unit)
            secondRelease.complete(Unit)
            yield()
            yield()
            assertEquals(3, repository.calls)
            assertEquals("A-latest", controller.snapshot.results.single().name)
            assertEquals(PlaceSearchPhase.RemoteResults, controller.snapshot.phase)
        }
    }

    @Test
    fun `cancel by new input ignores delayed remote and stays local-only`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repository = GatedPlaceSearchRepository(
            firstGate = started,
            firstRelease = release,
            firstName = "Stale remote",
            secondName = "Unused",
        )
        withController(repository) { controller ->
            controller.onQueryChanged("Alpha")
            assertTrue(controller.submit())
            started.await()
            controller.onQueryChanged("Al")
            assertEquals(PlaceSearchPhase.LocalOnly, controller.snapshot.phase)
            release.complete(Unit)
            yield()
            yield()
            assertEquals(PlaceSearchPhase.LocalOnly, controller.snapshot.phase)
            assertTrue(controller.snapshot.results.none { it.source == PlaceSearchSource.Remote })
            assertEquals(null, controller.snapshot.error)
        }
    }

    @Test
    fun `bias change uses a new submission and ignores the delayed prior response`() = runBlocking {
        val firstGate = CompletableDeferred<Unit>()
        val firstRelease = CompletableDeferred<Unit>()
        var language = "en"
        val repository = GatedPlaceSearchRepository(
            firstGate = firstGate,
            firstRelease = firstRelease,
            firstName = "English stale",
            secondName = "Chinese latest",
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = PlaceSearchController(
            scope = scope,
            repository = repository,
            landmarkMatcher = { emptyList() },
            biasProvider = { PlaceSearchBias(25.0, 121.0, language) },
        )
        try {
            controller.onQueryChanged("Taipei")
            assertTrue(controller.submit())
            firstGate.await()
            language = "zh-TW"
            controller.onQueryChanged("Taipei Station")
            assertTrue(controller.submit())
            repository.secondCompleted.await()
            firstRelease.complete(Unit)
            yield()
            yield()
            assertEquals("Chinese latest", controller.snapshot.results.single().name)
            assertEquals(PlaceSearchPhase.RemoteResults, controller.snapshot.phase)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `remote failure keeps local landmarks`() = runBlocking {
        val repository = RecordingPlaceSearchRepository(failure = PlaceSearchException.RateLimited(30))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = PlaceSearchController(
            scope = scope,
            repository = repository,
            landmarkMatcher = { listOf(PlaceSearchResult("Local 101", taipei, PlaceSearchSource.Landmark)) },
            biasProvider = { PlaceSearchBias(25.0, 121.0, "zh-TW") },
        )
        try {
            controller.onQueryChanged("101")
            controller.submit()
            yield()
            assertEquals(PlaceSearchPhase.RemoteUnavailable, controller.snapshot.phase)
            assertEquals(PlaceSearchSource.Landmark, controller.snapshot.results.single().source)
            assertEquals(PlaceSearchErrorKind.RateLimited, controller.snapshot.error)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `empty remote results are success and keep local landmarks`() = runBlocking {
        val repository = RecordingPlaceSearchRepository()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = PlaceSearchController(
            scope = scope,
            repository = repository,
            landmarkMatcher = { listOf(PlaceSearchResult("Local 101", taipei, PlaceSearchSource.Landmark)) },
            biasProvider = { PlaceSearchBias(25.0, 121.0, "zh-TW") },
        )
        try {
            controller.onQueryChanged("101")
            assertTrue(controller.submit())
            yield()
            assertEquals(PlaceSearchPhase.EmptyRemoteResults, controller.snapshot.phase)
            assertEquals(PlaceSearchSource.Landmark, controller.snapshot.results.single().source)
            assertEquals(1, repository.calls)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `timeout oversized and invalid responses keep local results`() = runBlocking {
        listOf(
            PlaceSearchException.Network() to PlaceSearchErrorKind.Network,
            PlaceSearchException.RequestTooLarge() to PlaceSearchErrorKind.RequestTooLarge,
            PlaceSearchException.InvalidResponse() to PlaceSearchErrorKind.InvalidResponse,
            PlaceSearchException.ServiceUnavailable() to PlaceSearchErrorKind.Unavailable,
            PlaceSearchException.InvalidRequest() to PlaceSearchErrorKind.InvalidRequest,
        ).forEach { (failure, kind) ->
            val repository = RecordingPlaceSearchRepository(failure)
            withController(repository) { controller ->
                controller.onQueryChanged("Taipei")
                assertTrue(controller.submit())
                yield()
                assertEquals(PlaceSearchPhase.RemoteUnavailable, controller.snapshot.phase)
                assertEquals(kind, controller.snapshot.error)
            }
        }
    }

    @Test
    fun `disabled remote search shows unavailable without calling the gateway`() = runBlocking {
        val repository = RecordingPlaceSearchRepository()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = PlaceSearchController(
            scope = scope,
            repository = repository,
            landmarkMatcher = { listOf(PlaceSearchResult("Local 101", taipei, PlaceSearchSource.Landmark)) },
            biasProvider = { PlaceSearchBias(25.0, 121.0, "zh-TW") },
            searchEnabled = { false },
        )
        try {
            controller.onQueryChanged("101")
            assertTrue(controller.submit())
            assertEquals(0, repository.calls)
            assertEquals(PlaceSearchPhase.RemoteUnavailable, controller.snapshot.phase)
            assertEquals(PlaceSearchErrorKind.Unavailable, controller.snapshot.error)
            assertEquals(PlaceSearchSource.Landmark, controller.snapshot.results.single().source)
        } finally {
            scope.cancel()
        }
    }

    private suspend fun withController(
        repository: PlaceSearchRepository,
        block: suspend (PlaceSearchController) -> Unit,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val controller = PlaceSearchController(
            scope = scope,
            repository = repository,
            landmarkMatcher = { emptyList() },
            biasProvider = { PlaceSearchBias(25.0, 121.0, "en") },
        )
        try {
            block(controller)
        } finally {
            scope.cancel()
        }
    }

    private class RecordingPlaceSearchRepository(
        private val failure: PlaceSearchException? = null,
    ) : PlaceSearchRepository {
        var calls = 0

        override suspend fun search(query: String, bias: PlaceSearchBias?): PlaceSearchPage {
            calls += 1
            failure?.let { throw it }
            return PlaceSearchPage(emptyList())
        }
    }

    private inner class GatedPlaceSearchRepository(
        private val firstGate: CompletableDeferred<Unit>,
        private val firstRelease: CompletableDeferred<Unit>,
        private val firstName: String,
        private val secondName: String,
    ) : PlaceSearchRepository {
        var calls = 0
        val firstCompleted = CompletableDeferred<Unit>()
        val secondCompleted = CompletableDeferred<Unit>()

        override suspend fun search(query: String, bias: PlaceSearchBias?): PlaceSearchPage {
            calls += 1
            val name = if (calls == 1) firstName else secondName
            if (calls == 1) {
                firstGate.complete(Unit)
                firstRelease.await()
                firstCompleted.complete(Unit)
            } else {
                secondCompleted.complete(Unit)
            }
            return PlaceSearchPage(listOf(PlaceSearchResult(name, taipei, PlaceSearchSource.Remote)))
        }
    }

    private inner class SequentialGatedPlaceSearchRepository(
        private val firstGate: CompletableDeferred<Unit>,
        private val firstRelease: CompletableDeferred<Unit>,
        private val secondGate: CompletableDeferred<Unit>,
        private val secondRelease: CompletableDeferred<Unit>,
        private val firstName: String,
        private val secondName: String,
        private val thirdName: String,
    ) : PlaceSearchRepository {
        var calls = 0
        val thirdCompleted = CompletableDeferred<Unit>()

        override suspend fun search(query: String, bias: PlaceSearchBias?): PlaceSearchPage {
            calls += 1
            val name = when (calls) {
                1 -> {
                    firstGate.complete(Unit)
                    firstRelease.await()
                    firstName
                }
                2 -> {
                    secondGate.complete(Unit)
                    secondRelease.await()
                    secondName
                }
                else -> {
                    thirdCompleted.complete(Unit)
                    thirdName
                }
            }
            return PlaceSearchPage(listOf(PlaceSearchResult(name, taipei, PlaceSearchSource.Remote)))
        }
    }
}
