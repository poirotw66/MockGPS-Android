package com.sora.mockgps.feature.favorites.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultFavoriteLocationRepositoryTest {
    @Test
    fun `save exposes favorite through flow and de-duplicates rounded coordinates`() = runBlocking {
        val clock = MutableClock(1_000)
        val repository = DefaultFavoriteLocationRepository(InMemoryFavoriteLocationDao(), clock)

        val first = repository.save("  Taipei 101  ", 25.0339641, 121.5644681)
        clock.now = 2_000
        val replacement = repository.save("Taipei 101 updated", 25.0339644, 121.5644684)

        assertEquals(first.id, replacement.id)
        assertEquals("Taipei 101 updated", replacement.name)
        assertEquals(1_000, replacement.createdAt)
        assertEquals(2_000, replacement.updatedAt)
        assertEquals(listOf(replacement), repository.favorites.first())
    }

    @Test
    fun `rename delete and validation have predictable results`() {
        runBlocking {
            val clock = MutableClock(1_000)
            val repository = DefaultFavoriteLocationRepository(InMemoryFavoriteLocationDao(), clock)
            val favorite = repository.save("Home", 25.0, 121.0)

            clock.now = 2_000
            assertTrue(repository.rename(favorite.id, "  Office  "))
            assertEquals("Office", repository.get(favorite.id)?.name)
            assertEquals(2_000L, repository.get(favorite.id)?.updatedAt)
            assertTrue(repository.delete(favorite.id))
            assertFalse(repository.delete(favorite.id))
            assertNull(repository.get(favorite.id))
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                DefaultFavoriteLocationRepository(InMemoryFavoriteLocationDao()).save(" ", 25.0, 121.0)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                DefaultFavoriteLocationRepository(InMemoryFavoriteLocationDao())
                    .save("Bad coordinate", 91.0, 121.0)
            }
        }
    }

    @Test
    fun `recent locations de-duplicate and retain newest fifty`() = runBlocking {
        val clock = MutableClock(1_000)
        val repository = DefaultFavoriteLocationRepository(InMemoryFavoriteLocationDao(), clock)
        repository.recordRecent(25.0339641, 121.5644681)
        clock.now++
        repository.recordRecent(25.0339644, 121.5644684)
        repeat(50) { index ->
            clock.now++
            repository.recordRecent(index.toDouble(), 100.0)
        }

        val recent = repository.recentLocations.first()
        assertEquals(50, recent.size)
        assertEquals(49.0, recent.first().latitude, 0.0)
        assertTrue(recent.none { it.latitude == 25.033964 })
    }

    private class MutableClock(var now: Long) : FavoriteLocationClock {
        override fun currentTimeMillis(): Long = now
    }

    private class InMemoryFavoriteLocationDao : FavoriteLocationDao {
        private val rows = linkedMapOf<Long, FavoriteLocationEntity>()
        private val state = MutableStateFlow(emptyList<FavoriteLocationEntity>())
        private val recentRows = linkedMapOf<Long, RecentLocationEntity>()
        private val recentState = MutableStateFlow(emptyList<RecentLocationEntity>())
        private var nextId = 1L
        private var nextRecentId = 1L

        override fun observeAll(): Flow<List<FavoriteLocationEntity>> = state
        override fun observeRecentLocations(): Flow<List<RecentLocationEntity>> = recentState

        override suspend fun getById(id: Long): FavoriteLocationEntity? = rows[id]

        override suspend fun getByCoordinate(
            normalizedLatitude: Long,
            normalizedLongitude: Long,
        ): FavoriteLocationEntity? = rows.values.firstOrNull {
            it.normalizedLatitude == normalizedLatitude && it.normalizedLongitude == normalizedLongitude
        }

        override suspend fun insert(entity: FavoriteLocationEntity): Long {
            if (getByCoordinate(entity.normalizedLatitude, entity.normalizedLongitude) != null) return -1
            val id = nextId++
            rows[id] = entity.copy(id = id)
            publish()
            return id
        }

        override suspend fun updateName(id: Long, name: String, updatedAt: Long): Int {
            val current = rows[id] ?: return 0
            rows[id] = current.copy(name = name, updatedAt = updatedAt)
            publish()
            return 1
        }

        override suspend fun deleteById(id: Long): Int {
            val removed = rows.remove(id) ?: return 0
            publish()
            return if (removed.id == id) 1 else 0
        }

        override suspend fun clearAll(): Int {
            val removed = rows.size
            rows.clear()
            publish()
            return removed
        }

        override suspend fun getRecentLocationByCoordinate(
            normalizedLatitude: Long,
            normalizedLongitude: Long,
        ): RecentLocationEntity? = recentRows.values.firstOrNull {
            it.normalizedLatitude == normalizedLatitude && it.normalizedLongitude == normalizedLongitude
        }

        override suspend fun insertRecentLocation(entity: RecentLocationEntity): Long {
            if (getRecentLocationByCoordinate(entity.normalizedLatitude, entity.normalizedLongitude) != null) return -1
            val id = nextRecentId++
            recentRows[id] = entity.copy(id = id)
            publishRecent()
            return id
        }

        override suspend fun refreshRecentLocation(
            id: Long,
            latitude: Double,
            longitude: Double,
            usedAt: Long,
        ): Int {
            val current = recentRows[id] ?: return 0
            recentRows[id] = current.copy(latitude = latitude, longitude = longitude, usedAt = usedAt)
            publishRecent()
            return 1
        }

        override suspend fun trimRecentLocations(maximumRows: Int): Int {
            val retainedIds = recentRows.values.sortedWith(
                compareByDescending<RecentLocationEntity> { it.usedAt }.thenByDescending { it.id },
            ).take(maximumRows).mapTo(mutableSetOf(), RecentLocationEntity::id)
            val before = recentRows.size
            recentRows.keys.retainAll(retainedIds)
            publishRecent()
            return before - recentRows.size
        }

        override suspend fun clearRecentLocations(): Int {
            val removed = recentRows.size
            recentRows.clear()
            publishRecent()
            return removed
        }

        private fun publish() {
            state.value = rows.values.sortedWith(
                compareByDescending<FavoriteLocationEntity> { it.updatedAt }.thenByDescending { it.id },
            )
        }

        private fun publishRecent() {
            recentState.value = recentRows.values.sortedWith(
                compareByDescending<RecentLocationEntity> { it.usedAt }.thenByDescending { it.id },
            )
        }
    }
}
