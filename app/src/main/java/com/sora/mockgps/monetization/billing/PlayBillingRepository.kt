package com.sora.mockgps.monetization.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class PlayBillingRepository(
    context: Context,
    private val productId: String,
    private val onPurchases: (List<PlayPurchase>) -> Unit,
    private val onQueryExistingPurchases: () -> Unit = {},
    private val onPurchaseFlowFinished: () -> Unit = {},
) : BillingRepository, PurchasesUpdatedListener {
    private var productDetails: ProductDetails? = null
    private val client: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build(),
        )
        .build()

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        val mapped = (purchases ?: emptyList()).mapNotNull(::toPlayPurchase)
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> onPurchases(mapped)
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                if (mapped.isNotEmpty()) {
                    onPurchases(mapped)
                } else {
                    onPurchaseFlowFinished()
                    onQueryExistingPurchases()
                }
            }
            else -> onPurchaseFlowFinished()
        }
    }

    override suspend fun connect() {
        if (client.isReady) return
        suspendCancellableCoroutine { continuation ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (continuation.isActive) continuation.resume(Unit)
                }

                override fun onBillingServiceDisconnected() = Unit
            })
        }
    }

    override suspend fun queryProduct(): BillingProduct? {
        connect()
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(productId)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build(),
                ),
            )
            .build()
        val details = suspendCancellableCoroutine<List<ProductDetails>> { continuation ->
            client.queryProductDetailsAsync(params) { result, productDetailsList ->
                val list = if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    productDetailsList
                } else {
                    emptyList()
                }
                if (continuation.isActive) continuation.resume(list)
            }
        }
        val match = details.firstOrNull { it.productId == productId } ?: return null
        productDetails = match
        val offer = match.oneTimePurchaseOfferDetails ?: return null
        return BillingProduct(productId = match.productId, formattedPrice = offer.formattedPrice)
    }

    override suspend fun queryPurchases(): BillingQueryResult {
        connect()
        if (!client.isReady) return BillingQueryResult.Unavailable
        return suspendCancellableCoroutine { continuation ->
            client.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(),
            ) { result, purchases ->
                val mapped = if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    BillingQueryResult.Success(purchases.mapNotNull(::toPlayPurchase))
                } else {
                    BillingQueryResult.Unavailable
                }
                if (continuation.isActive) continuation.resume(mapped)
            }
        }
    }

    override fun launchPurchase(activity: Activity): Boolean {
        val details = productDetails ?: return false
        val offer = details.oneTimePurchaseOfferDetails ?: return false
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .build(),
                ),
            )
            .build()
        val result = client.launchBillingFlow(activity, params)
        return result.responseCode == BillingClient.BillingResponseCode.OK
    }

    override fun acknowledge(purchaseToken: String) {
        val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchaseToken).build()
        client.acknowledgePurchase(params) { }
    }

    private fun toPlayPurchase(purchase: Purchase): PlayPurchase? {
        if (!purchase.products.contains(productId)) return null
        val state = when (purchase.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> PlayPurchaseState.Purchased
            Purchase.PurchaseState.PENDING -> PlayPurchaseState.Pending
            else -> PlayPurchaseState.Unspecified
        }
        return PlayPurchase(
            purchaseToken = purchase.purchaseToken,
            productId = productId,
            state = state,
            acknowledged = purchase.isAcknowledged,
            quantity = purchase.quantity,
            isAutoRenewing = purchase.isAutoRenewing,
        )
    }
}
