package com.sora.mockgps

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.sora.mockgps.feature.map.MapScreen
import com.sora.mockgps.ui.theme.BloomWalkTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as? BloomWalkApplication
        app?.let { bloomWalk ->
            bloomWalk.monetization.consentRepository.requestUpdate(this)
            lifecycleScope.launch {
                refreshRemoteConfig(bloomWalk)
                bloomWalk.monetization.entitlementRepository.start()
            }
        }
        enableEdgeToEdge()
        setContent {
            BloomWalkTheme { MapScreen() }
        }
    }

    override fun onResume() {
        super.onResume()
        val app = application as? BloomWalkApplication ?: return
        lifecycleScope.launch {
            refreshRemoteConfig(app)
            app.monetization.entitlementRepository.refreshOnForeground()
        }
    }

    private suspend fun refreshRemoteConfig(app: BloomWalkApplication) {
        app.mapDependencies.configRepository.refreshOnForeground()
        app.monetization.entitlementRepository.setPurchasesEnabled(
            app.mapDependencies.configRepository.flags.value.purchasesEnabled,
        )
    }
}
