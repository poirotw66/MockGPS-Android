package com.sora.mockgps.feature.search

import com.sora.mockgps.core.model.Coordinate
import com.sora.mockgps.core.network.AttributionLink
import com.sora.mockgps.core.network.GatewayException
import java.text.Normalizer
import kotlin.math.abs

enum class PlaceSearchSource { Coordinate, Landmark, Remote }

enum class PlaceSearchPhase {
    LocalOnly,
    Submitting,
    RemoteResults,
    EmptyRemoteResults,
    RemoteUnavailable,
}

data class PlaceSearchResult(
    val name: String,
    val coordinate: Coordinate,
    val source: PlaceSearchSource = PlaceSearchSource.Remote,
    val address: String? = null,
)

data class PlaceSearchBias(
    val latitude: Double,
    val longitude: Double,
    val language: String,
)

data class PlaceSearchPage(
    val results: List<PlaceSearchResult>,
    val attribution: List<AttributionLink> = emptyList(),
    val providerRevision: String? = null,
)

sealed class PlaceSearchException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Network(cause: Throwable? = null) : PlaceSearchException("network", cause)
    class RateLimited(val retryAfterSeconds: Int? = null) : PlaceSearchException("rate_limited")
    class GlobalQuotaExhausted(val retryAfterSeconds: Int? = null) : PlaceSearchException("global_quota")
    class InvalidRequest(cause: Throwable? = null) : PlaceSearchException("invalid_request", cause)
    class RequestTooLarge : PlaceSearchException("request_too_large")
    class ServiceUnavailable(cause: Throwable? = null) : PlaceSearchException("unavailable", cause)
    class InvalidResponse(cause: Throwable? = null) : PlaceSearchException("invalid_response", cause)

    companion object {
        fun fromGateway(error: GatewayException): PlaceSearchException = when (error) {
            is GatewayException.InvalidRequest -> InvalidRequest(error)
            is GatewayException.RequestTooLarge -> RequestTooLarge()
            is GatewayException.ClientRateLimited -> RateLimited(error.retryAfterSeconds)
            is GatewayException.GlobalQuotaExhausted -> GlobalQuotaExhausted(error.retryAfterSeconds)
            is GatewayException.InvalidProviderResponse -> InvalidResponse(error)
            is GatewayException.ProviderTimeout,
            is GatewayException.Unavailable,
            is GatewayException.ServiceDisabled,
            is GatewayException.BudgetUnavailable,
            is GatewayException.ProviderUnavailable,
            -> ServiceUnavailable(error)
            else -> Network(error)
        }
    }
}

fun normalizePlaceQuery(query: String): String =
    Normalizer.normalize(query.trim(), Normalizer.Form.NFC)

fun placeQueryCodePointCount(query: String): Int {
    val normalized = normalizePlaceQuery(query)
    return normalized.codePointCount(0, normalized.length)
}

fun isExplicitSearchQuerySubmittable(query: String): Boolean =
    placeQueryCodePointCount(query) in MINIMUM_REMOTE_QUERY_CODE_POINTS..MAXIMUM_REMOTE_QUERY_CODE_POINTS

const val MINIMUM_REMOTE_QUERY_CODE_POINTS = 2
const val MAXIMUM_REMOTE_QUERY_CODE_POINTS = 200

/** Parses "lat, lon" / "lat lon" / "lon, lat" when one value is outside latitude range. */
internal fun parseCoordinateSearchQuery(query: String): Coordinate? {
    val parts = normalizePlaceQuery(query).split(Regex("[,\\s;]+")).filter { it.isNotBlank() }
    if (parts.size != 2) return null
    val first = parts[0].toDoubleOrNull() ?: return null
    val second = parts[1].toDoubleOrNull() ?: return null
    val (latitude, longitude) = if (abs(first) > 90.0 && abs(second) <= 90.0) {
        second to first
    } else {
        first to second
    }
    return Coordinate(latitude, longitude).takeIf {
        it.latitude.isFinite() && it.latitude in -90.0..90.0 &&
            it.longitude.isFinite() && it.longitude in -180.0..180.0
    }
}

internal fun formatCoordinateSearchLabel(coordinate: Coordinate): String =
    "${coordinate.latitude.formatSearchCoordinate()}, ${coordinate.longitude.formatSearchCoordinate()}"

private fun Double.formatSearchCoordinate(): String = java.lang.String.format(java.util.Locale.US, "%.6f", this)

internal fun buildPlaceSearchBias(
    cameraCenter: Coordinate,
    acceptLanguage: String?,
): PlaceSearchBias = PlaceSearchBias(
    latitude = cameraCenter.latitude,
    longitude = cameraCenter.longitude,
    language = acceptLanguage?.takeIf { it.isNotBlank() } ?: "en",
)

/** Local landmarks first; drop remote hits that roughly duplicate a landmark coordinate. */
internal fun mergePlaceSearchResults(
    local: List<PlaceSearchResult>,
    remote: List<PlaceSearchResult>,
): List<PlaceSearchResult> {
    val filteredRemote = remote.filter { remoteHit ->
        local.none { roughlySameCoordinate(it.coordinate, remoteHit.coordinate) }
    }
    return local + filteredRemote
}

private fun roughlySameCoordinate(first: Coordinate, second: Coordinate): Boolean =
    abs(first.latitude - second.latitude) < 0.002 &&
        abs(first.longitude - second.longitude) < 0.002

/** Short or digit-heavy queries often intend a landmark nickname. */
internal fun looksLikeLandmarkNickname(query: String): Boolean {
    val normalized = normalizePlaceQuery(query)
    if (normalized.length < 2) return false
    if (normalized.all { it.isDigit() }) return true
    return normalized.length <= 4
}
