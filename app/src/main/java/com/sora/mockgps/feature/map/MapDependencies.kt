package com.sora.mockgps.feature.map

import android.app.Application
import com.sora.mockgps.core.settings.AppSettingsRepository
import com.sora.mockgps.core.settings.DataStoreAppSettingsRepository
import com.sora.mockgps.feature.favorites.data.DefaultFavoriteLocationRepository
import com.sora.mockgps.feature.favorites.data.FavoriteLocationDatabase
import com.sora.mockgps.feature.favorites.domain.FavoriteLocationRepository
import com.sora.mockgps.feature.routes.data.DefaultRouteRepository
import com.sora.mockgps.feature.routes.domain.RouteRepository
import com.sora.mockgps.feature.search.NominatimPlaceSearchRepository
import com.sora.mockgps.feature.search.PlaceSearchRepository
import com.sora.mockgps.route.CachingRoutingRepository
import com.sora.mockgps.route.FossgisBicycleRoutingRepository
import com.sora.mockgps.route.RoutingRepository

/** Production composition root; tests can replace every I/O dependency. */
data class MapDependencies(
    val favoriteRepository: FavoriteLocationRepository,
    val routeRepository: RouteRepository,
    val routingRepository: RoutingRepository,
    val settingsRepository: AppSettingsRepository,
    val placeSearchRepository: PlaceSearchRepository,
) {
    companion object {
        fun from(application: Application): MapDependencies {
            val database = FavoriteLocationDatabase.getInstance(application)
            return MapDependencies(
                favoriteRepository = DefaultFavoriteLocationRepository(database.favoriteLocationDao()),
                routeRepository = DefaultRouteRepository(database.routeDao()),
                routingRepository = CachingRoutingRepository(FossgisBicycleRoutingRepository()),
                settingsRepository = DataStoreAppSettingsRepository(application),
                placeSearchRepository = NominatimPlaceSearchRepository(),
            )
        }
    }
}
