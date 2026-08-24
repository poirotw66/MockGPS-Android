package com.sora.mockgps.feature.favorites.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sora.mockgps.feature.routes.data.RecentRouteEntity
import com.sora.mockgps.feature.routes.data.DefaultRouteRepository
import com.sora.mockgps.feature.routes.data.RouteClock
import com.sora.mockgps.feature.routes.data.SavedRouteEntity
import com.sora.mockgps.core.model.Coordinate
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

/** Exercises the current Room schema on device; historic migration is kept explicit in the DB. */
@RunWith(AndroidJUnit4::class)
class RoomMigrationInstrumentedTest {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        FavoriteLocationDatabase::class.java,
    )

    @Test fun migration_2_3_preserves_existing_data_and_adds_recent_locations() {
        migrationHelper.createDatabase(MIGRATION_DATABASE, 2).apply {
            execSQL(
                "INSERT INTO favorite_locations " +
                    "(name, latitude, longitude, normalizedLatitude, normalizedLongitude, createdAt, updatedAt) " +
                    "VALUES ('Taipei', 25.0, 121.0, 25000000, 121000000, 1, 1)",
            )
            close()
        }

        migrationHelper.runMigrationsAndValidate(
            MIGRATION_DATABASE,
            3,
            true,
            FavoriteLocationDatabase.MIGRATION_2_3,
        ).use { database ->
            database.query("SELECT COUNT(*) FROM favorite_locations").use { cursor ->
                cursor.moveToFirst()
                assertEquals(1, cursor.getInt(0))
            }
            database.query("SELECT COUNT(*) FROM recent_locations").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    @Test fun current_schema_persists_favorites_and_route_tables() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, FavoriteLocationDatabase::class.java)
            .allowMainThreadQueries().build()
        runBlocking {
            try {
                db.favoriteLocationDao().insert(
                    FavoriteLocationEntity(name = "test", latitude = 25.0, longitude = 121.0, normalizedLatitude = 25_000_000, normalizedLongitude = 121_000_000, createdAt = 1, updatedAt = 1),
                )
                db.routeDao().insertSavedRoute(SavedRouteEntity(name = "route", geometry = "[[25,121],[25.1,121.1]]", distanceMeters = 10.0, createdAt = 1, updatedAt = 1))
                db.routeDao().insertRecentRoute(RecentRouteEntity(name = "route", geometry = "[[25,121],[25.1,121.1]]", distanceMeters = 10.0, usedAt = 1))
                assertEquals(1, db.routeDao().getAllSavedRoutes().size)
                assertEquals(1, db.routeDao().getAllRecentRoutes().size)
            } finally { db.close() }
        }
    }

    @Test fun route_repository_renames_and_duplicates_saved_geometry() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, FavoriteLocationDatabase::class.java)
            .allowMainThreadQueries().build()
        runBlocking {
            try {
                val repository = DefaultRouteRepository(db.routeDao(), RouteClock { 10L })
                val points = listOf(Coordinate(25.0, 121.0), Coordinate(25.1, 121.1))
                val original = repository.save("Original", points)

                assertEquals(true, repository.rename(original.id, "Renamed"))
                assertEquals("Renamed", repository.getSavedRoute(original.id)?.name)

                val duplicate = repository.duplicate(original.id, "Copy")
                assertEquals("Copy", duplicate.name)
                assertEquals(points, duplicate.points)
                assertEquals(2, db.routeDao().getAllSavedRoutes().size)
            } finally { db.close() }
        }
    }

    private companion object { const val MIGRATION_DATABASE = "migration-2-3-test" }
}
