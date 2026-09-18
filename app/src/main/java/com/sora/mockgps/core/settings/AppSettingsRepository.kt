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
    val setupGuideDismissed: Boolean = false,
)

interface AppSettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun update(transform: (AppSettings) -> AppSettings)
}

class DataStoreAppSettingsRepository(private val context: Context) : AppSettingsRepository {
    override val settings: Flow<AppSettings> = context.appSettingsDataStore.data.map { preferences ->
        settingsFrom(preferences)
    }

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.appSettingsDataStore.edit { preferences ->
            val updated = transform(settingsFrom(preferences))
            preferences[MAP_TYPE] = if (updated.mapType == MapDisplayType.Dark) "dark" else "light"
            preferences[UPDATE_INTERVAL] = normalizeUpdateInterval(updated.updateIntervalMillis).toInt()
            preferences[ACCURACY] = normalizeAccuracy(updated.accuracyMeters).toDouble()
            preferences[SHOW_COORDINATES] = updated.showCoordinates
            preferences[SETUP_GUIDE_DISMISSED] = updated.setupGuideDismissed
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
        updateIntervalMillis = preferences[UPDATE_INTERVAL]?.toLong()?.let(::normalizeUpdateInterval) ?: 1_000L,
        accuracyMeters = preferences[ACCURACY]?.toFloat()?.let(::normalizeAccuracy) ?: 5f,
        showCoordinates = preferences[SHOW_COORDINATES] ?: true,
        lastCoordinate = preferences[LAST_LAT]?.let { latitude -> preferences[LAST_LON]?.let { Coordinate(latitude, it) } },
        setupGuideDismissed = preferences[SETUP_GUIDE_DISMISSED] ?: false,
    )

    private companion object {
        val MAP_TYPE = androidx.datastore.preferences.core.stringPreferencesKey("map_type")
        val UPDATE_INTERVAL = intPreferencesKey("update_interval_millis")
        val ACCURACY = doublePreferencesKey("accuracy_meters")
        val SHOW_COORDINATES = booleanPreferencesKey("show_coordinates")
        val SETUP_GUIDE_DISMISSED = booleanPreferencesKey("setup_guide_dismissed")
        val LAST_LAT = doublePreferencesKey("last_latitude")
        val LAST_LON = doublePreferencesKey("last_longitude")

        val ALLOWED_UPDATE_INTERVALS = listOf(500L, 1_000L, 2_000L)
        val ALLOWED_ACCURACY_METERS = listOf(1f, 5f, 10f, 50f)

        fun normalizeUpdateInterval(value: Long): Long =
            ALLOWED_UPDATE_INTERVALS.minByOrNull { kotlin.math.abs(it - value.coerceIn(250L, 60_000L)) } ?: 1_000L

        fun normalizeAccuracy(value: Float): Float =
            ALLOWED_ACCURACY_METERS.minByOrNull { kotlin.math.abs(it - value.coerceIn(1f, 100f)) } ?: 5f
    }
}

/** Cycles MVP update intervals: 500 → 1000 → 2000 → 500. */
fun nextUpdateIntervalMillis(current: Long): Long = when {
    current < 750L -> 1_000L
    current < 1_500L -> 2_000L
    else -> 500L
}

/** Cycles common accuracy presets: 1 → 5 → 10 → 50 → 1. */
fun nextAccuracyMeters(current: Float): Float = when {
    current < 3f -> 5f
    current < 7.5f -> 10f
    current < 30f -> 50f
    else -> 1f
}
