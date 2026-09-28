package com.sora.mockgps.monetization.consent

import android.app.Activity
import android.content.Context
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ConsentSnapshot(
    val canRequestAds: Boolean = false,
    val privacyOptionsRequired: Boolean = false,
    val updated: Boolean = false,
)

interface ConsentRepository {
    val snapshot: StateFlow<ConsentSnapshot>
    fun requestUpdate(activity: Activity)
    fun showPrivacyOptions(activity: Activity)
}

class UmpConsentRepository(
    context: Context,
) : ConsentRepository {
    private val appContext = context.applicationContext
    private val consentInformation: ConsentInformation =
        UserMessagingPlatform.getConsentInformation(appContext)
    private val mutableSnapshot = MutableStateFlow(readSnapshot())
    override val snapshot: StateFlow<ConsentSnapshot> = mutableSnapshot.asStateFlow()

    override fun requestUpdate(activity: Activity) {
        val params = ConsentRequestParameters.Builder().build()
        consentInformation.requestConsentInfoUpdate(
            activity,
            params,
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) {
                    mutableSnapshot.value = readSnapshot()
                }
            },
            { mutableSnapshot.value = readSnapshot() },
        )
    }

    override fun showPrivacyOptions(activity: Activity) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) {
            mutableSnapshot.value = readSnapshot()
        }
    }

    private fun readSnapshot(): ConsentSnapshot = ConsentSnapshot(
        canRequestAds = consentInformation.canRequestAds(),
        privacyOptionsRequired = consentInformation.privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED,
        updated = true,
    )
}

class FakeConsentRepository(
    initial: ConsentSnapshot = ConsentSnapshot(canRequestAds = false),
) : ConsentRepository {
    private val mutableSnapshot = MutableStateFlow(initial)
    override val snapshot: StateFlow<ConsentSnapshot> = mutableSnapshot.asStateFlow()
    var updateCount: Int = 0
        private set

    override fun requestUpdate(activity: Activity) {
        updateCount += 1
    }

    override fun showPrivacyOptions(activity: Activity) = Unit

    fun setCanRequestAds(value: Boolean) {
        mutableSnapshot.value = mutableSnapshot.value.copy(canRequestAds = value)
    }
}
