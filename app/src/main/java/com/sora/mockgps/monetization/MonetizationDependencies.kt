package com.sora.mockgps.monetization

import android.app.Application
import com.sora.mockgps.BuildConfig
import com.sora.mockgps.core.network.GatewayClient
import com.sora.mockgps.core.network.InstallIdStore
import com.sora.mockgps.monetization.ads.AdsController
import com.sora.mockgps.monetization.billing.BillingRepository
import com.sora.mockgps.monetization.billing.FakeBillingRepository
import com.sora.mockgps.monetization.billing.PlayBillingRepository
import com.sora.mockgps.monetization.billing.PurchaseVerifier
import com.sora.mockgps.monetization.consent.ConsentRepository
import com.sora.mockgps.monetization.consent.UmpConsentRepository
import com.sora.mockgps.monetization.entitlement.EntitlementRepository
import com.sora.mockgps.monetization.entitlement.KeystoreEntitlementStore
import com.sora.mockgps.monetization.entitlement.ReceiptVerifier
import com.sora.mockgps.monetization.entitlement.parseReceiptPublicKeysJson
import com.sora.mockgps.monetization.entitlement.publicKeyFromUncompressedP256
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

data class MonetizationDependencies(
    val entitlementRepository: EntitlementRepository,
    val adsController: AdsController,
    val consentRepository: ConsentRepository,
    val scope: CoroutineScope,
) {
    companion object {
        fun from(
            application: Application,
            gatewayClient: GatewayClient,
            installIdStore: InstallIdStore,
        ): MonetizationDependencies {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val keys = parseReceiptPublicKeysJson(BuildConfig.RECEIPT_PUBLIC_KEYS_JSON)
                .mapValues { publicKeyFromUncompressedP256(it.value) }
            val receiptVerifier = if (keys.isEmpty()) {
                null
            } else {
                ReceiptVerifier(
                    publicKeysByKid = keys,
                    expectedAudience = application.packageName,
                )
            }
            val billingHolder = BillingHolder()
            val entitlement = EntitlementRepository(
                store = KeystoreEntitlementStore(application),
                billing = billingHolder,
                verifier = PurchaseVerifier(gatewayClient),
                receiptVerifier = receiptVerifier,
                installIdProvider = installIdStore::getOrCreate,
                productId = BuildConfig.BILLING_PRODUCT_ID,
            )
            val playBilling = PlayBillingRepository(
                context = application,
                productId = BuildConfig.BILLING_PRODUCT_ID,
                onPurchases = { purchases ->
                    scope.launch { entitlement.onPurchasesUpdated(purchases) }
                },
                onQueryExistingPurchases = {
                    scope.launch { entitlement.restore() }
                },
            )
            billingHolder.delegate = playBilling
            return MonetizationDependencies(
                entitlementRepository = entitlement,
                adsController = AdsController(),
                consentRepository = UmpConsentRepository(application),
                scope = scope,
            )
        }
    }
}

private class BillingHolder : BillingRepository {
    var delegate: BillingRepository = FakeBillingRepository()

    override suspend fun connect() = delegate.connect()
    override suspend fun queryProduct() = delegate.queryProduct()
    override suspend fun queryPurchases() = delegate.queryPurchases()
    override fun launchPurchase(activity: android.app.Activity) = delegate.launchPurchase(activity)
    override fun acknowledge(purchaseToken: String) = delegate.acknowledge(purchaseToken)
}
