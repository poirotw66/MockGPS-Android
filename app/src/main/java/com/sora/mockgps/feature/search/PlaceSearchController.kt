package com.sora.mockgps.feature.search

import com.sora.mockgps.core.model.Coordinate
import com.sora.mockgps.core.network.AttributionLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

data class PlaceSearchSnapshot(
    val query: String = "",
    val phase: PlaceSearchPhase = PlaceSearchPhase.LocalOnly,
    val results: List<PlaceSearchResult> = emptyList(),
    val attribution: List<AttributionLink> = emptyList(),
    val error: PlaceSearchErrorKind? = null,
    val submissionId: Long = 0L,
)

enum class PlaceSearchErrorKind {
    Network,
    RateLimited,
    GlobalQuota,
    InvalidRequest,
    RequestTooLarge,
    InvalidResponse,
    Unavailable,
}

fun interface LandmarkMatcher {
    fun match(query: String): List<PlaceSearchResult>
}

/**
 * Separates local input from explicit remote submit. Remote results change only from submit,
 * never from recomposition or Activity restore.
 */
class PlaceSearchController(
    private val scope: CoroutineScope,
    private val repository: PlaceSearchRepository,
    private val landmarkMatcher: LandmarkMatcher,
    private val biasProvider: () -> PlaceSearchBias?,
    private val searchEnabled: () -> Boolean = { true },
) {
    var snapshot: PlaceSearchSnapshot = PlaceSearchSnapshot()
        private set
    private var nextSubmissionId = 0L
    private var inFlightJob: Job? = null
    private val listeners = mutableListOf<(PlaceSearchSnapshot) -> Unit>()

    fun addListener(listener: (PlaceSearchSnapshot) -> Unit) {
        listeners += listener
    }

    fun onQueryChanged(query: String) {
        inFlightJob?.cancel()
        inFlightJob = null
        val local = localResults(query)
        // New input invalidates in-flight remote results; do not match only on query text.
        val submissionId = ++nextSubmissionId
        publish(
            PlaceSearchSnapshot(
                query = query,
                phase = PlaceSearchPhase.LocalOnly,
                results = local,
                attribution = emptyList(),
                error = null,
                submissionId = submissionId,
            ),
        )
    }

    fun submit(): Boolean {
        val query = snapshot.query
        val normalized = normalizePlaceQuery(query)
        if (!isExplicitSearchQuerySubmittable(normalized)) return false
        if (parseCoordinateSearchQuery(normalized) != null) return false
        if (inFlightJob?.isActive == true) return false
        val submissionId = ++nextSubmissionId
        val local = localResults(query)
        if (!searchEnabled()) {
            publish(
                snapshot.copy(
                    phase = PlaceSearchPhase.RemoteUnavailable,
                    results = local,
                    attribution = emptyList(),
                    error = PlaceSearchErrorKind.Unavailable,
                    submissionId = submissionId,
                ),
            )
            return true
        }
        val bias = biasProvider()
        publish(
            snapshot.copy(
                phase = PlaceSearchPhase.Submitting,
                results = local,
                attribution = emptyList(),
                error = null,
                submissionId = submissionId,
            ),
        )
        inFlightJob = scope.launch {
            try {
                val page = repository.search(normalized, bias)
                ensureActive()
                publishIfCurrent(
                    submissionId,
                    snapshot.copy(
                        phase = if (page.results.isEmpty()) {
                            PlaceSearchPhase.EmptyRemoteResults
                        } else {
                            PlaceSearchPhase.RemoteResults
                        },
                        results = mergePlaceSearchResults(local, page.results),
                        attribution = page.attribution,
                        error = null,
                        submissionId = submissionId,
                    ),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: PlaceSearchException) {
                ensureActive()
                publishIfCurrent(
                    submissionId,
                    snapshot.copy(
                        phase = PlaceSearchPhase.RemoteUnavailable,
                        results = local,
                        attribution = emptyList(),
                        error = failure.toKind(),
                        submissionId = submissionId,
                    ),
                )
            }
        }
        return true
    }

    fun clear() {
        onQueryChanged("")
    }

    val isSubmitting: Boolean get() = snapshot.phase == PlaceSearchPhase.Submitting

    private fun localResults(query: String): List<PlaceSearchResult> {
        val normalized = normalizePlaceQuery(query)
        if (normalized.isEmpty()) return emptyList()
        parseCoordinateSearchQuery(normalized)?.let { coordinate ->
            return listOf(
                PlaceSearchResult(
                    name = formatCoordinateSearchLabel(coordinate),
                    coordinate = coordinate,
                    source = PlaceSearchSource.Coordinate,
                ),
            )
        }
        if (placeQueryCodePointCount(normalized) < MINIMUM_REMOTE_QUERY_CODE_POINTS) {
            return emptyList()
        }
        return landmarkMatcher.match(normalized)
    }

    private fun publishIfCurrent(expectedSubmissionId: Long, next: PlaceSearchSnapshot) {
        if (snapshot.submissionId != expectedSubmissionId) return
        publish(next)
    }

    private fun publish(next: PlaceSearchSnapshot) {
        snapshot = next
        listeners.toList().forEach { it(next) }
    }
}

fun PlaceSearchException.toKind(): PlaceSearchErrorKind = when (this) {
    is PlaceSearchException.RateLimited -> PlaceSearchErrorKind.RateLimited
    is PlaceSearchException.GlobalQuotaExhausted -> PlaceSearchErrorKind.GlobalQuota
    is PlaceSearchException.InvalidRequest -> PlaceSearchErrorKind.InvalidRequest
    is PlaceSearchException.RequestTooLarge -> PlaceSearchErrorKind.RequestTooLarge
    is PlaceSearchException.InvalidResponse -> PlaceSearchErrorKind.InvalidResponse
    is PlaceSearchException.ServiceUnavailable -> PlaceSearchErrorKind.Unavailable
    is PlaceSearchException.Network -> PlaceSearchErrorKind.Network
}
