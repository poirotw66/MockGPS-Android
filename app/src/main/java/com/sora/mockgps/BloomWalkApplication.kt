package com.sora.mockgps

import android.app.Application
import com.sora.mockgps.core.network.GatewayClient
import com.sora.mockgps.core.network.GatewayClientConfig
import com.sora.mockgps.core.network.InstallIdStore
import com.sora.mockgps.feature.map.MapDependencies
import com.sora.mockgps.monetization.MonetizationDependencies

class BloomWalkApplication : Application() {
    lateinit var mapDependencies: MapDependencies
        private set
    lateinit var monetization: MonetizationDependencies
        private set

    override fun onCreate() {
        super.onCreate()
        mapDependencies = MapDependencies.from(this)
        val installIdStore = mapDependencies.installIdStore ?: InstallIdStore(this)
        val gatewayClient = mapDependencies.gatewayClient ?: GatewayClient(
            config = GatewayClientConfig(
                baseUrl = BuildConfig.GATEWAY_BASE_URL,
                clientVersion = BuildConfig.VERSION_NAME,
            ),
            installIdProvider = installIdStore::getOrCreate,
        )
        monetization = MonetizationDependencies.from(
            application = this,
            gatewayClient = gatewayClient,
            installIdStore = installIdStore,
        )
    }
}
