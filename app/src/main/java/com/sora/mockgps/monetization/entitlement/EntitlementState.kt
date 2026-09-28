package com.sora.mockgps.monetization.entitlement

enum class EntitlementState {
    Unknown,
    Free,
    PurchasePending,
    VerificationPending,
    OwnedVerified,
    OwnedOffline,
    Revoked,
}

fun EntitlementState.allowsAds(): Boolean = this == EntitlementState.Free

fun EntitlementState.suppressesAds(): Boolean = when (this) {
    EntitlementState.Unknown,
    EntitlementState.PurchasePending,
    EntitlementState.VerificationPending,
    EntitlementState.OwnedVerified,
    EntitlementState.OwnedOffline,
    -> true
    EntitlementState.Free,
    EntitlementState.Revoked,
    -> false
}

fun EntitlementState.canStartPurchase(): Boolean = this == EntitlementState.Free

fun EntitlementState.isOwned(): Boolean =
    this == EntitlementState.OwnedVerified || this == EntitlementState.OwnedOffline

data class EntitlementSnapshot(
    val state: EntitlementState = EntitlementState.Unknown,
    val productId: String = "remove_ads_lifetime",
    val formattedPrice: String? = null,
    val acknowledgementPending: Boolean = false,
    val lastVerifiedAtMillis: Long? = null,
    val productDetailsAvailable: Boolean = false,
    val purchasesEnabled: Boolean = false,
)
