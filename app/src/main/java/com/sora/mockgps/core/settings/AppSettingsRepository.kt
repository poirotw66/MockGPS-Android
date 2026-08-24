package com.sora.mockgps.core.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.sora.mockgps.core.model.Coordinate
import com.sora.mockgps.feature.map.MapDisplayType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.appSettingsDataStore by preferencesDataStore("app_settings")

/** User choices only; no location is sent to a service by this store. */
data class AppSettings(
    val mapType: MapDisplayType = MapDisplayType.Light,
    val updateIntervalMillis: Long = 1_000L,
    val accuracyMeters: Float = 5f,
    val showCoordinates: Boolean = true,
    val lastCoordinate: Coordinate? = null,
)

interface AppSettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun update(transform: (AppSettings) -> AppSettings)
}

class DataStoreAppSettingsRepository(private val context: Context) : AppSettingsRepository {
    override val settings: Flow<AppSettings> = context.appSettingsDataStore.data.map { preferences ->
        AppSettings(
            mapType = if (preferences[MAP_TYPE] == "dark") MapDisplayType.Dark else MapDisplayType.Light,
            updateIntervalMillis = preferences[UPDATE_INTERVAL]?.toLong()?.coerceIn(250L, 60_000L) ?: 1_000L,
            accuracyMeters = preferences[ACCURACY]?.toFloat()?.coerceIn(1f, 100f) ?: 5f,
            showCoordinates = preferences[SHOW_COORDINATES] ?: true,
            lastCoordinate = preferences[LAST_LAT]?.let { latitude ->
                preferences[LAST_LON]?.let { longitude -> Coordinate(latitude, longitude) }
            },
        )
    }

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.appSettingsDataStore.edit { preferences ->
            val updated = transform(settingsFrom(preferences))
            preferences[MAP_TYPE] = if (updated.mapType == MapDisplayType.Dark) "dark" else "light"
            preferences[UPDATE_INTERVAL] = updated.updateIntervalMillis.coerceIn(250L, 60_000L).toInt()
            preferences[ACCURACY] = updated.accuracyMeters.coerceIn(1f, 100f).toDouble()
            preferences[SHOW_COORDINATES] = updated.showCoordinates
            updated.lastCoordinate?.let {
                preferences[LAST_LAT] = it.latitude
                preferences[LAST_LON] = it.longitude
            } ?: run {
                preferences.remove(LAST_LAT)
                preferences.remove(LAST_LON)
            }
        }
    }

    private fun settingsFrom(preferences: androidx.datastore.preferences.core.Preferences): AppSettings = AppSettings(
        mapType = if (preferences[MAP_TYPE] == "dark") MapDisplayType.Dark else MapDisplayType.Light,
        updateIntervalMillis = preferences[UPDATE_INTERVAL]?.toLong()?.coerceIn(250L, 60_000L) ?: 1_000L,
        accuracyMeters = preferences[ACCURACY]?.toFloat()?.coerceIn(1f, 100f) ?: 5f,
        showCoordinates = preferences[SHOW_COORDINATES] ?: true,
        lastCoordinate = preferences[LAST_LAT]?.let { latitude -> preferences[LAST_LON]?.let { Coordinate(latitude, it) } },
    )

    private companion object {
        val MAP_TYPE = androidx.datastore.preferences.core.stringPreferencesKey("map_type")
        val UPDATE_INTERVAL = intPreferencesKey("update_interval_millis")
        val ACCURACY = doublePreferencesKey("accuracy_meters")
        val SHOW_COORDINATES = booleanPreferencesKey("show_coordinates")
        val LAST_LAT = doublePreferencesKey("last_latitude")
        val LAST_LON = doublePreferencesKey("last_longitude")
    }
}
