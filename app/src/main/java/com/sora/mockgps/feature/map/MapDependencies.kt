package com.sora.mockgps.feature.map

import android.app.Application
import com.sora.mockgps.BuildConfig
import com.sora.mockgps.core.network.GatewayClient
import com.sora.mockgps.core.network.GatewayClientConfig
import com.sora.mockgps.core.network.GatewayConfigRepository
import com.sora.mockgps.core.network.RemoteConfigSource
import com.sora.mockgps.core.network.InstallIdStore
import com.sora.mockgps.core.settings.AppSettingsRepository
import com.sora.mockgps.core.settings.DataStoreAppSettingsRepository
import com.sora.mockgps.feature.favorites.data.DefaultFavoriteLocationRepository
import com.sora.mockgps.feature.favorites.data.FavoriteLocationDatabase
import com.sora.mockgps.feature.favorites.domain.FavoriteLocationRepository
import com.sora.mockgps.feature.routes.data.DefaultRouteRepository
import com.sora.mockgps.feature.routes.domain.RouteRepository
import com.sora.mockgps.feature.search.CachingPlaceSearchRepository
import com.sora.mockgps.feature.search.GatewayPlaceSearchRepository
import com.sora.mockgps.feature.search.PlaceSearchRepository
import com.sora.mockgps.route.CachingRoutingRepository
import com.sora.mockgps.route.FeatureGatedRoutingRepository
import com.sora.mockgps.route.GatewayRoutingRepository
import com.sora.mockgps.route.RoutingCacheIdentity
import com.sora.mockgps.route.RoutingRepository
import kotlin.random.Random

/** Production composition root; tests can replace every I/O dependency. */
data class MapDependencies(
    val favoriteRepository: FavoriteLocationRepository,
    val routeRepository: RouteRepository,
    val routingRepository: RoutingRepository,
    val settingsRepository: AppSettingsRepository,
    val placeSearchRepository: PlaceSearchRepository,
    val journeyRandom: Random = Random.Default,
    val gatewayClient: GatewayClient? = null,
    val configRepository: GatewayConfigRepository = GatewayConfigRepository(),
    val installIdStore: InstallIdStore? = null,
) {
    companion object {
        fun from(application: Application): MapDependencies {
            val database = FavoriteLocationDatabase.getInstance(application)
            val installIdStore = InstallIdStore(application)
            val gatewayConfig = GatewayClientConfig(
                baseUrl = BuildConfig.GATEWAY_BASE_URL,
                clientVersion = BuildConfig.VERSION_NAME,
                contractRevision = BuildConfig.API_CONTRACT_REVISION,
            )
            val gatewayClient = GatewayClient(
                config = gatewayConfig,
                installIdProvider = installIdStore::getOrCreate,
            )
            val configRepository = GatewayConfigRepository(fetch = { gatewayClient.getConfig() })
            return MapDependencies(
                favoriteRepository = DefaultFavoriteLocationRepository(database.favoriteLocationDao()),
                routeRepository = DefaultRouteRepository(database.routeDao()),
                routingRepository = FeatureGatedRoutingRepository(
                    delegate = CachingRoutingRepository(
                        delegate = GatewayRoutingRepository(gatewayClient),
                        identity = RoutingCacheIdentity(
                            contractRevision = gatewayConfig.contractRevision,
                            providerRevision = "geoapify-v1",
                        ),
                    ),
                    routingEnabled = { configRepository.flags.value.routingEnabled },
                    refreshRoutingAvailability = { configRepository.refreshOnForeground(force = true) },
                    routingConfirmedDisabled = {
                        val source = configRepository.flags.value.source
                        source == RemoteConfigSource.Network || source == RemoteConfigSource.Cache
                    },
                ),
                settingsRepository = DataStoreAppSettingsRepository(application),
                placeSearchRepository = CachingPlaceSearchRepository(
                    GatewayPlaceSearchRepository(gatewayClient),
                ),
                gatewayClient = gatewayClient,
                configRepository = configRepository,
                installIdStore = installIdStore,
            )
        }
    }
}
