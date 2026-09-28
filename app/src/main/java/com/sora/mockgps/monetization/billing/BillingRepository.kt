package com.sora.mockgps.monetization.billing

import android.app.Activity

data class BillingProduct(
    val productId: String,
    val formattedPrice: String,
)

enum class PlayPurchaseState {
    Purchased,
    Pending,
    Unspecified,
}

data class PlayPurchase(
    val purchaseToken: String,
    val productId: String,
    val state: PlayPurchaseState,
    val acknowledged: Boolean,
    val quantity: Int = 1,
    val isAutoRenewing: Boolean = false,
)

sealed class BillingQueryResult {
    data class Success(val purchases: List<PlayPurchase>) : BillingQueryResult()
    data object Unavailable : BillingQueryResult()
}

interface BillingRepository {
    suspend fun connect()
    suspend fun queryProduct(): BillingProduct?
    suspend fun queryPurchases(): BillingQueryResult
    fun launchPurchase(activity: Activity): Boolean
    fun acknowledge(purchaseToken: String)
}

class FakeBillingRepository(
    var product: BillingProduct? = null,
    var queryResult: BillingQueryResult = BillingQueryResult.Success(emptyList()),
    var purchaseLaunched: Boolean = false,
) : BillingRepository {
    val acknowledgedTokens = mutableListOf<String>()

    override suspend fun connect() = Unit

    override suspend fun queryProduct(): BillingProduct? = product

    override suspend fun queryPurchases(): BillingQueryResult = queryResult

    override fun launchPurchase(activity: Activity): Boolean {
        purchaseLaunched = true
        return product != null
    }

    override fun acknowledge(purchaseToken: String) {
        acknowledgedTokens += purchaseToken
    }
}
