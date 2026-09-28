package com.sora.mockgps.core.network

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayConfigRepositoryTest {
    @Test
    fun `no config keeps local-only flags and never invents enablement`() = runBlocking {
        val repository = GatewayConfigRepository(fetch = { error("offline") }, clock = { 1_000L })
        repository.refreshOnForeground()
        val flags = repository.flags.value
        assertEquals(RemoteConfigSource.Unavailable, flags.source)
        assertFalse(flags.searchEnabled)
        assertFalse(flags.routingEnabled)
        assertFalse(flags.purchasesEnabled)
        assertFalse(flags.adsEnabled)
        assertFalse(flags.onlineGeoAvailable)
        assertEquals(1, repository.fetchCount)
    }

    @Test
    fun `foreground refresh is at most every six hours and cache lasts twenty four`() = runBlocking {
        var now = 0L
        var attempts = 0
        val enabled = capabilities(search = true, routing = true, purchases = true, ads = true, fetchedAt = 0L)
        val repository = GatewayConfigRepository(
            fetch = {
                attempts += 1
                if (attempts >= 3) error("later outage")
                enabled
            },
            clock = { now },
        )
        repository.refreshOnForeground()
        assertTrue(repository.flags.value.searchEnabled)
        assertEquals(RemoteConfigSource.Network, repository.flags.value.source)

        now = GatewayConfigRepository.REFRESH_INTERVAL_MILLIS - 1
        repository.refreshOnForeground()
        assertEquals(1, repository.fetchCount)

        now = GatewayConfigRepository.REFRESH_INTERVAL_MILLIS
        repository.refreshOnForeground()
        assertEquals(2, repository.fetchCount)
        assertTrue(repository.flags.value.adsEnabled)

        now = GatewayConfigRepository.REFRESH_INTERVAL_MILLIS * 2
        repository.refreshOnForeground()
        assertEquals(3, repository.fetchCount)
        assertTrue(repository.flags.value.searchEnabled)
        assertEquals(RemoteConfigSource.Cache, repository.flags.value.source)

        now = GatewayConfigRepository.REFRESH_INTERVAL_MILLIS * 2 +
            GatewayConfigRepository.MAX_CACHE_AGE_MILLIS + 1
        repository.refreshOnForeground()
        assertEquals(4, repository.fetchCount)
        assertFalse(repository.flags.value.searchEnabled)
        assertFalse(repository.flags.value.purchasesEnabled)
        assertEquals(RemoteConfigSource.Unavailable, repository.flags.value.source)
    }

    @Test
    fun `unavailable config does not invent paid flags`() = runBlocking {
        val repository = GatewayConfigRepository(
            fetch = {
                capabilities(search = false, routing = false, purchases = false, ads = true, fetchedAt = 0L)
            },
            clock = { 10L },
        )
        repository.refreshOnForeground()
        assertTrue(repository.flags.value.adsEnabled)
        assertFalse(repository.flags.value.purchasesEnabled)
        assertFalse(repository.flags.value.searchEnabled)
    }

    private fun capabilities(
        search: Boolean,
        routing: Boolean,
        purchases: Boolean,
        ads: Boolean,
        fetchedAt: Long,
    ) = GatewayCapabilities(
        schemaVersion = 1,
        revision = "geoapify-v1",
        searchEnabled = search,
        routingEnabled = routing,
        purchasesEnabled = purchases,
        adsEnabled = ads,
        routingMaxWaypoints = 25,
        routingMaxInputDistanceMeters = 100_000,
        privacyPolicyUrl = "https://privacy.example.test",
        supportUrl = "https://support.example.test",
        fetchedAtMillis = fetchedAt,
    )
}
