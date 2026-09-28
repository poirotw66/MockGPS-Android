package com.sora.mockgps.core.network

import org.json.JSONObject
import java.net.URI

data class AttributionLink(
    val label: String,
    val url: String,
)

data class GatewayCapabilities(
    val schemaVersion: Int,
    val revision: String,
    val searchEnabled: Boolean,
    val routingEnabled: Boolean,
    val purchasesEnabled: Boolean,
    val adsEnabled: Boolean,
    val routingMaxWaypoints: Int,
    val routingMaxInputDistanceMeters: Int,
    val privacyPolicyUrl: String,
    val supportUrl: String,
    val fetchedAtMillis: Long,
) {
    val providerRevision: String get() = revision
}

data class GatewayClientConfig(
    val baseUrl: String,
    val clientVersion: String,
    val contractRevision: String = DEFAULT_CONTRACT_REVISION,
    val connectTimeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    val readTimeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    val maxSearchResponseBytes: Int = 64 * 1024,
    val maxRouteResponseBytes: Int = 512 * 1024,
    val maxErrorResponseBytes: Int = 4 * 1024,
    val maxPurchaseResponseBytes: Int = 16 * 1024,
    val maxConfigResponseBytes: Int = 16 * 1024,
    val userAgent: String = DEFAULT_USER_AGENT,
) {
    val normalizedBaseUrl: String
        get() = baseUrl.trimEnd('/')

    init {
        val uri = runCatching { URI(baseUrl) }.getOrElse {
            throw IllegalArgumentException("Gateway base URL is invalid.", it)
        }
        require(uri.scheme == "https") { "Gateway must use HTTPS." }
        require(!uri.host.isNullOrBlank()) { "Gateway must include a host." }
        require(contractRevision.isNotBlank())
        require(clientVersion.isNotBlank())
        require(userAgent.isNotBlank())
        require(connectTimeoutMillis in 1..60_000)
        require(readTimeoutMillis in 1..60_000)
    }

    companion object {
        const val DEFAULT_CONTRACT_REVISION = "v1"
        const val DEFAULT_TIMEOUT_MILLIS = 10_000
        const val DEFAULT_USER_AGENT = "BloomWalk-GPS/0.1"
        const val SEARCH_REQUEST_MAX_BYTES = 4 * 1024
        const val ROUTE_REQUEST_MAX_BYTES = 16 * 1024
        const val PURCHASE_REQUEST_MAX_BYTES = 16 * 1024
    }
}

object GatewayLanguages {
    const val ZH_TW = "zh-TW"
    const val EN = "en"

    fun wireLanguage(tag: String?): String {
        val normalized = tag?.trim().orEmpty()
        return if (normalized.equals(ZH_TW, ignoreCase = true) ||
            normalized.startsWith("zh-Hant", ignoreCase = true) ||
            normalized.equals("zh-TW", ignoreCase = true) ||
            normalized.startsWith("zh-rTW", ignoreCase = true)
        ) {
            ZH_TW
        } else {
            EN
        }
    }
}

val ALLOWED_ATTRIBUTION_HOSTS = setOf(
    "www.geoapify.com",
    "geoapify.com",
    "www.openstreetmap.org",
    "openstreetmap.org",
)

fun parseAttributionArray(raw: org.json.JSONArray?): List<AttributionLink> {
    if (raw == null) return emptyList()
    return buildList {
        for (index in 0 until raw.length()) {
            val item = raw.optJSONObject(index) ?: continue
            val label = item.optString("label").trim()
            val url = item.optString("url").trim()
            if (label.isEmpty() || url.isEmpty()) continue
            val host = runCatching { URI(url).host?.lowercase() }.getOrNull() ?: continue
            if (host in ALLOWED_ATTRIBUTION_HOSTS) {
                add(AttributionLink(label, url))
            }
        }
    }
}

fun parseGatewayCapabilities(body: String, fetchedAtMillis: Long): GatewayCapabilities {
    val root = JSONObject(body)
    val schemaVersion = root.getInt("schemaVersion")
    require(schemaVersion == 1) { "Unsupported gateway schema version." }
    return GatewayCapabilities(
        schemaVersion = schemaVersion,
        revision = root.getString("revision").trim().also { require(it.isNotEmpty()) },
        searchEnabled = root.optBoolean("searchEnabled", false),
        routingEnabled = root.optBoolean("routingEnabled", false),
        purchasesEnabled = root.optBoolean("purchasesEnabled", false),
        adsEnabled = root.optBoolean("adsEnabled", false),
        routingMaxWaypoints = root.optInt("routingMaxWaypoints", 25),
        routingMaxInputDistanceMeters = root.optInt("routingMaxInputDistanceMeters", 100_000),
        privacyPolicyUrl = root.optString("privacyPolicyUrl"),
        supportUrl = root.optString("supportUrl"),
        fetchedAtMillis = fetchedAtMillis,
    )
}

fun isReleaseForbiddenUrl(url: String): Boolean {
    val host = runCatching { URI(url).host?.lowercase() }.getOrNull().orEmpty()
    return host.isBlank() || host.endsWith(".invalid") || host == "localhost" || host.endsWith(".example")
}
