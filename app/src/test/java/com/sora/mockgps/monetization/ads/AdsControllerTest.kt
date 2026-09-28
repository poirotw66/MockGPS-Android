package com.sora.mockgps.monetization.ads

import com.sora.mockgps.monetization.entitlement.EntitlementState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdsControllerTest {
    @Test
    fun `unknown and owned states never request ads`() {
        val controller = AdsController()
        EntitlementState.entries.filter { it != EntitlementState.Free && it != EntitlementState.Revoked }.forEach { state ->
            assertFalse(
                controller.shouldRequestAds(
                    AdsEligibility(true, state, adsEnabled = true, canRequestAds = true, slotEligible = true, pausedByInteraction = false),
                ),
            )
        }
    }

    @Test
    fun `free ads require consent visible slot and server flag`() {
        val controller = AdsController()
        val eligible = AdsEligibility(true, EntitlementState.Free, true, true, true, false)
        assertTrue(controller.shouldRequestAds(eligible))
        assertFalse(controller.shouldRequestAds(eligible.copy(adsEnabled = false)))
        assertFalse(controller.shouldRequestAds(eligible.copy(canRequestAds = false)))
        assertFalse(controller.shouldRequestAds(eligible.copy(slotEligible = false)))
        assertFalse(controller.shouldRequestAds(eligible.copy(activityResumed = false)))
        assertFalse(controller.shouldRequestAds(eligible.copy(pausedByInteraction = true)))
    }

    @Test
    fun `duplicate eligibility does not load twice`() {
        val controller = AdsController()
        val eligible = AdsEligibility(true, EntitlementState.Free, true, true, true, false)
        assertEquals(AdsAction.Load, controller.onEligibilityChanged(eligible))
        assertEquals(AdsAction.Keep, controller.onEligibilityChanged(eligible))
        assertEquals(1, controller.loadCount)
        assertEquals(AdsAction.Destroy, controller.onEligibilityChanged(eligible.copy(entitlement = EntitlementState.OwnedVerified)))
        assertEquals(1, controller.destroyCount)
    }

    @Test
    fun `duplicate consent callback and recomposition do not double load`() {
        val controller = AdsController()
        val afterConsent = AdsEligibility(
            activityResumed = true,
            entitlement = EntitlementState.Free,
            adsEnabled = true,
            canRequestAds = true,
            slotEligible = true,
            pausedByInteraction = false,
        )
        assertEquals(AdsAction.Destroy, controller.onEligibilityChanged(afterConsent.copy(canRequestAds = false)))
        assertEquals(0, controller.loadCount)
        assertEquals(AdsAction.Load, controller.onEligibilityChanged(afterConsent))
        assertEquals(AdsAction.Keep, controller.onEligibilityChanged(afterConsent))
        assertEquals(AdsAction.Keep, controller.onEligibilityChanged(afterConsent.copy()))
        assertEquals(1, controller.loadCount)
    }

    @Test
    fun `background and non-resumed states never load ads`() {
        val controller = AdsController()
        val background = AdsEligibility(
            activityResumed = false,
            entitlement = EntitlementState.Free,
            adsEnabled = true,
            canRequestAds = true,
            slotEligible = true,
            pausedByInteraction = false,
        )
        assertEquals(AdsAction.Destroy, controller.onEligibilityChanged(background))
        assertEquals(AdsAction.Destroy, controller.onEligibilityChanged(background))
        assertEquals(0, controller.loadCount)
        assertFalse(controller.shouldRequestAds(background))
    }
}
