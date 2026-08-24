package com.sora.mockgps.feature.favorites.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.sora.mockgps.feature.routes.data.RecentRouteEntity
import com.sora.mockgps.feature.routes.data.RouteDao
import com.sora.mockgps.feature.routes.data.SavedRouteEntity

@Database(
    entities = [FavoriteLocationEntity::class, SavedRouteEntity::class, RecentRouteEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class FavoriteLocationDatabase : RoomDatabase() {
    abstract fun favoriteLocationDao(): FavoriteLocationDao
    abstract fun routeDao(): RouteDao

    companion object {
        @Volatile
        private var instance: FavoriteLocationDatabase? = null

        fun getInstance(context: Context): FavoriteLocationDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                FavoriteLocationDatabase::class.java,
                "favorite-locations.db",
            ).addMigrations(MIGRATION_1_2).build().also { instance = it }
        }

        /**
         * Keeps all v1 favorite rows intact while adding independent saved/recent route tables.
         * No favorite data is copied, transformed, or dropped.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `saved_routes` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`geometry` TEXT NOT NULL, " +
                        "`distanceMeters` REAL NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "`updatedAt` INTEGER NOT NULL, " +
                        "`reversedFromRouteId` INTEGER)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_saved_routes_updatedAt` " +
                        "ON `saved_routes` (`updatedAt`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `recent_routes` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`geometry` TEXT NOT NULL, " +
                        "`distanceMeters` REAL NOT NULL, " +
                        "`usedAt` INTEGER NOT NULL, " +
                        "`savedRouteId` INTEGER)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_recent_routes_usedAt` " +
                        "ON `recent_routes` (`usedAt`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_recent_routes_savedRouteId` " +
                        "ON `recent_routes` (`savedRouteId`)",
                )
            }
        }
    }
}
