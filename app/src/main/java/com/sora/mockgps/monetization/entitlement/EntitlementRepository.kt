package com.sora.mockgps.monetization.entitlement

import android.app.Activity
import com.sora.mockgps.monetization.billing.BillingProduct
import com.sora.mockgps.monetization.billing.BillingQueryResult
import com.sora.mockgps.monetization.billing.BillingRepository
import com.sora.mockgps.monetization.billing.PlayPurchase
import com.sora.mockgps.monetization.billing.PlayPurchaseState
import com.sora.mockgps.monetization.billing.PurchaseVerifier
import com.sora.mockgps.monetization.billing.PurchaseVerifyStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class EntitlementRepository(
    private val store: EntitlementStore,
    private val billing: BillingRepository,
    private val verifier: PurchaseVerifier,
    private val receiptVerifier: ReceiptVerifier?,
    private val installIdProvider: () -> String,
    private val productId: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(EntitlementSnapshot())
    val state: StateFlow<EntitlementSnapshot> = mutableState.asStateFlow()
    private val acknowledged = mutableSetOf<String>()
    private var purchaseInFlight = false
    private var emptyAccountQueryConfirmations = 0
    private var lastRemoteVerifyAtMillis = 0L

    companion object {
        const val ROUTINE_VERIFY_INTERVAL_MS = 24L * 60 * 60 * 1000
    }

    suspend fun start() {
        mutex.withLock {
            restoreFromStore()
            billing.connect()
            refreshProduct()
            refreshPurchases(force = false)
        }
    }

    suspend fun restore() {
        mutex.withLock {
            billing.connect()
            refreshProduct()
            refreshPurchases(force = true)
        }
    }

    suspend fun refreshOnForeground() {
        mutex.withLock {
            billing.connect()
            refreshProduct()
            refreshPurchases(force = false)
        }
    }

    fun onPurchaseFlowFinished() {
        purchaseInFlight = false
    }

    fun launchPurchase(activity: Activity): Boolean {
        val current = mutableState.value
        if (!current.purchasesEnabled ||
            !current.state.canStartPurchase() ||
            purchaseInFlight ||
            !current.productDetailsAvailable
        ) {
            return false
        }
        purchaseInFlight = true
        val launched = billing.launchPurchase(activity)
        if (!launched) purchaseInFlight = false
        return launched
    }

    suspend fun onPurchasesUpdated(purchases: List<PlayPurchase>) {
        mutex.withLock {
            applyPurchases(
                purchases,
                querySucceeded = true,
                force = true,
                completeInAppQuery = false,
            )
            purchaseInFlight = false
        }
    }

    fun setPurchasesEnabled(enabled: Boolean) {
        mutableState.update { it.copy(purchasesEnabled = enabled) }
    }

    private fun restoreFromStore() {
        val stored = store.read() ?: return
        val receipt = stored.receiptJws
        val installId = installIdProvider()
        val storedReceiptVerifier = receiptVerifier ?: return
        val claims = runCatching { storedReceiptVerifier.verify(receipt, installId) }.getOrNull()
        if (claims == null) return
        mutableState.update {
            it.copy(
                state = EntitlementState.OwnedOffline,
                lastVerifiedAtMillis = stored.lastVerifiedAtMillis,
                acknowledgementPending = false,
            )
        }
    }

    private suspend fun refreshProduct() {
        val product = runCatching { billing.queryProduct() }.getOrNull()
        mutableState.update {
            it.copy(
                formattedPrice = product?.formattedPrice,
                productDetailsAvailable = product != null,
                productId = product?.productId ?: productId,
            )
        }
    }

    private suspend fun refreshPurchases(force: Boolean) {
        when (val result = runCatching { billing.queryPurchases() }.getOrElse { BillingQueryResult.Unavailable }) {
            is BillingQueryResult.Unavailable -> {
                emptyAccountQueryConfirmations = 0
                if (mutableState.value.state == EntitlementState.Unknown && store.read() == null) {
                    // Cold start without receipt: remain Unknown and do not flash ads.
                    return
                }
            }
            is BillingQueryResult.Success -> applyPurchases(
                result.purchases,
                querySucceeded = true,
                force = force,
                completeInAppQuery = true,
            )
        }
    }

    private suspend fun applyPurchases(
        purchases: List<PlayPurchase>,
        querySucceeded: Boolean,
        force: Boolean,
        completeInAppQuery: Boolean,
    ) {
        val owned = purchases.filter { it.productId == productId && it.quantity >= 1 && !it.isAutoRenewing }
        val purchased = owned.filter { it.state == PlayPurchaseState.Purchased }
        val pending = owned.filter { it.state == PlayPurchaseState.Pending }
        if (purchased.isEmpty() && pending.isEmpty()) {
            applyEmptyPurchases(
                querySucceeded = querySucceeded,
                force = force,
                completeInAppQuery = completeInAppQuery,
            )
            return
        }
        emptyAccountQueryConfirmations = 0
        if (purchased.isEmpty()) {
            if (mutableState.value.state.isOwned()) {
                return
            }
            mutableState.update { it.copy(state = EntitlementState.PurchasePending) }
            return
        }
        val purchase = purchased.first()
        val withinRoutineWindow =
            lastRemoteVerifyAtMillis > 0L &&
                clock() - lastRemoteVerifyAtMillis < ROUTINE_VERIFY_INTERVAL_MS
        if (!force && withinRoutineWindow) {
            return
        }
        mutableState.update { it.copy(state = EntitlementState.VerificationPending) }
        val verification = verifier.verify(purchase.purchaseToken, installIdProvider())
        lastRemoteVerifyAtMillis = clock()
        when (verification.status) {
            PurchaseVerifyStatus.Owned -> {
                val receipt = verification.receipt
                if (receipt.isNullOrBlank()) {
                    mutableState.update { it.copy(state = EntitlementState.VerificationPending) }
                    return
                }
                val ownedReceiptVerifier = receiptVerifier
                if (ownedReceiptVerifier == null) {
                    mutableState.update { it.copy(state = EntitlementState.VerificationPending) }
                    return
                }
                runCatching { ownedReceiptVerifier.verify(receipt, installIdProvider()) }.getOrElse {
                    mutableState.update { it.copy(state = EntitlementState.VerificationPending) }
                    return
                }
                store.write(
                    StoredEntitlement(
                        receiptJws = receipt,
                        purchaseToken = purchase.purchaseToken,
                        installId = installIdProvider(),
                        productId = productId,
                        state = EntitlementState.OwnedVerified,
                        lastVerifiedAtMillis = clock(),
                    ),
                )
                if (!purchase.acknowledged && purchase.purchaseToken !in acknowledged) {
                    billing.acknowledge(purchase.purchaseToken)
                    acknowledged += purchase.purchaseToken
                }
                mutableState.update {
                    it.copy(
                        state = EntitlementState.OwnedVerified,
                        acknowledgementPending = verification.acknowledgementPending,
                        lastVerifiedAtMillis = clock(),
                    )
                }
            }
            PurchaseVerifyStatus.Pending -> mutableState.update { it.copy(state = EntitlementState.PurchasePending) }
            PurchaseVerifyStatus.Revoked -> {
                store.clear()
                mutableState.update { it.copy(state = EntitlementState.Revoked) }
            }
            PurchaseVerifyStatus.Unavailable -> {
                val stored = store.read()
                if (stored != null) {
                    mutableState.update { it.copy(state = EntitlementState.OwnedOffline) }
                } else {
                    mutableState.update { it.copy(state = EntitlementState.VerificationPending) }
                }
            }
        }
    }

    private fun applyEmptyPurchases(
        querySucceeded: Boolean,
        force: Boolean,
        completeInAppQuery: Boolean,
    ) {
        if (!querySucceeded) {
            emptyAccountQueryConfirmations = 0
            return
        }
        val current = mutableState.value.state
        if (current.isOwned()) {
            if (completeInAppQuery && force) {
                emptyAccountQueryConfirmations += 1
                if (emptyAccountQueryConfirmations >= 2) {
                    store.clear()
                    emptyAccountQueryConfirmations = 0
                    mutableState.update {
                        it.copy(
                            state = EntitlementState.Free,
                            acknowledgementPending = false,
                        )
                    }
                }
            }
            return
        }
        emptyAccountQueryConfirmations = 0
        if (current == EntitlementState.Unknown ||
            current == EntitlementState.Free ||
            current == EntitlementState.Revoked
        ) {
            mutableState.update { it.copy(state = EntitlementState.Free) }
        }
    }
}

fun BillingProduct.matches(productId: String): Boolean = this.productId == productId
