package com.sora.mockgps.feature.search

import com.sora.mockgps.core.model.Coordinate
import com.sora.mockgps.core.network.GatewayClient
import com.sora.mockgps.core.network.GatewayClientConfig
import com.sora.mockgps.core.network.GatewayException
import com.sora.mockgps.core.network.GatewayLanguages
import com.sora.mockgps.core.network.parseAttributionArray
import org.json.JSONObject

class GatewayPlaceSearchRepository(
    private val client: GatewayClient,
) : PlaceSearchRepository {
    override suspend fun search(query: String, bias: PlaceSearchBias?): PlaceSearchPage {
        val normalized = normalizePlaceQuery(query)
        if (!isExplicitSearchQuerySubmittable(normalized)) {
            throw PlaceSearchException.InvalidRequest()
        }
        val payload = JSONObject().apply {
            put("query", normalized)
            put("language", GatewayLanguages.wireLanguage(bias?.language))
            if (bias != null &&
                bias.latitude.isFinite() &&
                bias.longitude.isFinite() &&
                bias.latitude in -90.0..90.0 &&
                bias.longitude in -180.0..180.0
            ) {
                put(
                    "bias",
                    JSONObject().apply {
                        put("latitude", bias.latitude)
                        put("longitude", bias.longitude)
                    },
                )
            }
        }
        val response = try {
            client.postJson(
                path = "/v1/search",
                payload = payload,
                maxRequestBytes = GatewayClientConfig.SEARCH_REQUEST_MAX_BYTES,
                maxResponseBytes = 64 * 1024,
            )
        } catch (error: GatewayException) {
            throw PlaceSearchException.fromGateway(error)
        }
        return parseGatewaySearchPage(response.body)
    }
}

internal fun parseGatewaySearchPage(body: String): PlaceSearchPage {
    val root = runCatching { JSONObject(body) }.getOrElse {
        throw PlaceSearchException.InvalidResponse(it)
    }
    val items = root.optJSONArray("results")
        ?: throw PlaceSearchException.InvalidResponse()
    val results = buildList {
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val coordinate = item.optJSONObject("coordinate") ?: continue
            val latitude = coordinate.optDouble("latitude", Double.NaN)
            val longitude = coordinate.optDouble("longitude", Double.NaN)
            val name = item.optString("name").trim()
            if (name.isEmpty() ||
                !latitude.isFinite() ||
                !longitude.isFinite() ||
                latitude !in -90.0..90.0 ||
                longitude !in -180.0..180.0
            ) {
                continue
            }
            add(
                PlaceSearchResult(
                    name = name,
                    coordinate = Coordinate(latitude, longitude),
                    source = PlaceSearchSource.Remote,
                    address = item.optString("address").trim().ifBlank { null },
                ),
            )
        }
    }
    return PlaceSearchPage(
        results = results,
        attribution = parseAttributionArray(root.optJSONArray("attribution")),
        providerRevision = root.optString("providerRevision").trim().ifBlank { null },
    )
}
