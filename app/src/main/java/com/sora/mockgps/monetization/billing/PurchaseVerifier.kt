package com.sora.mockgps.monetization.billing

import com.sora.mockgps.core.network.GatewayClient
import com.sora.mockgps.core.network.GatewayClientConfig
import com.sora.mockgps.core.network.GatewayException
import org.json.JSONObject

enum class PurchaseVerifyStatus {
    Owned,
    Pending,
    Revoked,
    Unavailable,
}

data class PurchaseVerifyResult(
    val status: PurchaseVerifyStatus,
    val receipt: String? = null,
    val acknowledgementPending: Boolean = false,
    val serverTimeMillis: Long? = null,
)

open class PurchaseVerifier(
    private val client: GatewayClient,
) {
    open suspend fun verify(purchaseToken: String, installId: String): PurchaseVerifyResult {
        val payload = JSONObject()
            .put("purchaseToken", purchaseToken)
            .put("installId", installId)
        val response = try {
            client.postJson(
                path = "/v1/purchases/verify",
                payload = payload,
                maxRequestBytes = GatewayClientConfig.PURCHASE_REQUEST_MAX_BYTES,
                maxResponseBytes = GatewayClientConfig.PURCHASE_REQUEST_MAX_BYTES,
            )
        } catch (error: GatewayException) {
            return PurchaseVerifyResult(PurchaseVerifyStatus.Unavailable)
        }
        return parsePurchaseVerifyResult(response.body)
    }
}

internal fun parsePurchaseVerifyResult(body: String): PurchaseVerifyResult {
    val root = runCatching { JSONObject(body) }.getOrElse {
        return PurchaseVerifyResult(PurchaseVerifyStatus.Unavailable)
    }
    val status = when (root.optString("status")) {
        "owned" -> PurchaseVerifyStatus.Owned
        "pending" -> PurchaseVerifyStatus.Pending
        "revoked" -> PurchaseVerifyStatus.Revoked
        else -> return PurchaseVerifyResult(PurchaseVerifyStatus.Unavailable)
    }
    return PurchaseVerifyResult(
        status = status,
        receipt = root.optString("receipt").ifBlank { null },
        acknowledgementPending = root.optBoolean("acknowledgementPending", false),
        serverTimeMillis = root.optLong("serverTime").takeIf { it > 0L },
    )
}
