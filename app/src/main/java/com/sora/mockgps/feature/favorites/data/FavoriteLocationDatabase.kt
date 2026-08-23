package com.sora.mockgps.feature.favorites.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [FavoriteLocationEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class FavoriteLocationDatabase : RoomDatabase() {
    abstract fun favoriteLocationDao(): FavoriteLocationDao

    companion object {
        @Volatile
        private var instance: FavoriteLocationDatabase? = null

        fun getInstance(context: Context): FavoriteLocationDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                FavoriteLocationDatabase::class.java,
                "favorite-locations.db",
            ).build().also { instance = it }
        }
    }
}
