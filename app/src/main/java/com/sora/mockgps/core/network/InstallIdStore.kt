package com.sora.mockgps.core.network

import android.content.Context
import java.util.UUID

/** Pseudonymous install identifier used only for best-effort rate limiting. Not an auth token. */
class InstallIdStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getOrCreate(): String {
        synchronized(preferences) {
            val existing = preferences.getString(KEY_INSTALL_ID, null)
            if (!existing.isNullOrBlank()) return existing
            val created = UUID.randomUUID().toString()
            preferences.edit().putString(KEY_INSTALL_ID, created).apply()
            return created
        }
    }

    private companion object {
        const val PREFS_NAME = "bloomwalk_install_id"
        const val KEY_INSTALL_ID = "install_id"
    }
}
