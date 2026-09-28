package com.sora.mockgps.monetization.ads

import com.sora.mockgps.monetization.entitlement.EntitlementState
import com.sora.mockgps.monetization.entitlement.suppressesAds

data class AdsEligibility(
    val activityResumed: Boolean,
    val entitlement: EntitlementState,
    val adsEnabled: Boolean,
    val canRequestAds: Boolean,
    val slotEligible: Boolean,
    val pausedByInteraction: Boolean,
)

class AdsController {
    var loadCount: Int = 0
        private set
    var destroyCount: Int = 0
        private set
    var lastEligible: Boolean = false
        private set

    fun shouldRequestAds(eligibility: AdsEligibility): Boolean {
        if (eligibility.entitlement.suppressesAds()) return false
        return eligibility.activityResumed &&
            eligibility.entitlement == EntitlementState.Free &&
            eligibility.adsEnabled &&
            eligibility.canRequestAds &&
            eligibility.slotEligible &&
            !eligibility.pausedByInteraction
    }

    fun onEligibilityChanged(eligibility: AdsEligibility): AdsAction {
        val eligible = shouldRequestAds(eligibility)
        if (!eligible) {
            lastEligible = false
            destroyCount += 1
            return AdsAction.Destroy
        }
        if (lastEligible) return AdsAction.Keep
        lastEligible = true
        loadCount += 1
        return AdsAction.Load
    }
}

enum class AdsAction { Load, Keep, Destroy }
