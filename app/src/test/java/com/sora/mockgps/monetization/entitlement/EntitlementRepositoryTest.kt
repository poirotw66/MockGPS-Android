package com.sora.mockgps.monetization.entitlement

import com.sora.mockgps.monetization.billing.BillingProduct
import com.sora.mockgps.monetization.billing.BillingQueryResult
import com.sora.mockgps.monetization.billing.FakeBillingRepository
import com.sora.mockgps.monetization.billing.PlayPurchase
import com.sora.mockgps.monetization.billing.PlayPurchaseState
import com.sora.mockgps.monetization.billing.PurchaseVerifier
import com.sora.mockgps.monetization.billing.PurchaseVerifyResult
import com.sora.mockgps.monetization.billing.PurchaseVerifyStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class EntitlementRepositoryTest {
    @Test
    fun `unknown without receipt stays unknown when Play is unavailable`() = runBlocking {
        val repository = repository(
            billing = FakeBillingRepository(queryResult = BillingQueryResult.Unavailable),
        )
        repository.start()
        assertEquals(EntitlementState.Unknown, repository.state.value.state)
        assertTrue(repository.state.value.state.suppressesAds())
    }

    @Test
    fun `successful empty query can become free`() = runBlocking {
        val repository = repository()
        repository.start()
        assertEquals(EntitlementState.Free, repository.state.value.state)
    }

    @Test
    fun `pending purchase does not write a permanent receipt`() = runBlocking {
        val billing = FakeBillingRepository(
            product = BillingProduct("remove_ads_lifetime", "NT$90"),
            queryResult = BillingQueryResult.Success(
                listOf(
                    PlayPurchase("tok", "remove_ads_lifetime", PlayPurchaseState.Pending, acknowledged = false),
                ),
            ),
        )
        val store = InMemoryEntitlementStore()
        val repository = repository(store, billing, verify = PurchaseVerifyResult(PurchaseVerifyStatus.Pending))
        repository.start()
        assertEquals(EntitlementState.PurchasePending, repository.state.value.state)
        assertEquals(null, store.read())
        assertTrue(repository.state.value.state.suppressesAds())
    }

    @Test
    fun `owned verification stores receipt and does not double acknowledge`() = runBlocking {
        val keys = testKeys()
        val receipt = signReceipt(keys, "install-1")
        val billing = FakeBillingRepository(
            product = BillingProduct("remove_ads_lifetime", "NT$90"),
            queryResult = BillingQueryResult.Success(
                listOf(
                    PlayPurchase("tok", "remove_ads_lifetime", PlayPurchaseState.Purchased, acknowledged = false),
                ),
            ),
        )
        val store = InMemoryEntitlementStore()
        val verifier = RecordingPurchaseVerifier(PurchaseVerifyResult(PurchaseVerifyStatus.Owned, receipt))
        val repository = EntitlementRepository(
            store = store,
            billing = billing,
            verifier = verifier,
            receiptVerifier = ReceiptVerifier(mapOf("test" to keys.public), "com.bloss0m.bloomwalk"),
            installIdProvider = { "install-1" },
            productId = "remove_ads_lifetime",
        )
        repository.start()
        repository.onPurchasesUpdated(
            listOf(PlayPurchase("tok", "remove_ads_lifetime", PlayPurchaseState.Purchased, acknowledged = false)),
        )
        assertEquals(EntitlementState.OwnedVerified, repository.state.value.state)
        assertEquals(1, billing.acknowledgedTokens.size)
        assertEquals("tok", store.read()?.purchaseToken)
        assertFalse(repository.state.value.state.allowsAds())
    }

    @Test
    fun `verification unavailable keeps an existing grant`() = runBlocking {
        val keys = testKeys()
        val receipt = signReceipt(keys, "install-1")
        val store = InMemoryEntitlementStore()
        store.write(
            StoredEntitlement(
                receiptJws = receipt,
                purchaseToken = "tok",
                installId = "install-1",
                productId = "remove_ads_lifetime",
                state = EntitlementState.OwnedVerified,
                lastVerifiedAtMillis = 1L,
            ),
        )
        val billing = FakeBillingRepository(
            queryResult = BillingQueryResult.Success(
                listOf(PlayPurchase("tok", "remove_ads_lifetime", PlayPurchaseState.Purchased, true)),
            ),
        )
        val repository = EntitlementRepository(
            store = store,
            billing = billing,
            verifier = RecordingPurchaseVerifier(PurchaseVerifyResult(PurchaseVerifyStatus.Unavailable)),
            receiptVerifier = ReceiptVerifier(mapOf("test" to keys.public), "com.bloss0m.bloomwalk"),
            installIdProvider = { "install-1" },
            productId = "remove_ads_lifetime",
        )
        repository.start()
        assertEquals(EntitlementState.OwnedOffline, repository.state.value.state)
        assertTrue(store.read() != null)
    }

    @Test
    fun `authoritative revoke clears the grant`() = runBlocking {
        val billing = FakeBillingRepository(
            queryResult = BillingQueryResult.Success(
                listOf(PlayPurchase("tok", "remove_ads_lifetime", PlayPurchaseState.Purchased, true)),
            ),
        )
        val store = InMemoryEntitlementStore()
        store.write(
            StoredEntitlement("x", "tok", "install-1", "remove_ads_lifetime", EntitlementState.OwnedVerified, 1L),
        )
        val repository = repository(
            store,
            billing,
            verify = PurchaseVerifyResult(PurchaseVerifyStatus.Revoked),
            receiptVerifier = null,
        )
        repository.start()
        assertEquals(EntitlementState.Revoked, repository.state.value.state)
        assertEquals(null, store.read())
        assertFalse(repository.state.value.state.suppressesAds())
    }

    @Test
    fun `gateway ISO verifiedAt receipts verify against the server issuer`() {
        val keys = testKeys()
        val receipt = signReceipt(keys, "install-1", verifiedAt = "2026-09-28T00:00:00.000Z")
        val verifier = ReceiptVerifier(mapOf("test" to keys.public), "com.bloss0m.bloomwalk")
        val claims = verifier.verify(receipt, "install-1")
        assertEquals("bloomwalk-gps-gateway", claims.issuer)
        assertEquals(java.time.Instant.parse("2026-09-28T00:00:00.000Z").toEpochMilli(), claims.verifiedAt)
    }

    @Test
    fun `wrong install receipt is rejected`() {
        val keys = testKeys()
        val receipt = signReceipt(keys, "other-install")
        val verifier = ReceiptVerifier(mapOf("test" to keys.public), "com.bloss0m.bloomwalk")
        val failure = runCatching { verifier.verify(receipt, "install-1") }.exceptionOrNull()
        assertTrue(failure is ReceiptVerificationException)
    }

    @Test
    fun `wrong product invalid signature and unknown kid receipts are rejected`() {
        val keys = testKeys()
        val verifier = ReceiptVerifier(mapOf("test" to keys.public), "com.bloss0m.bloomwalk")
        val wrongProduct = signReceipt(keys, "install-1", productId = "other_product")
        assertTrue(runCatching { verifier.verify(wrongProduct, "install-1") }.exceptionOrNull() is ReceiptVerificationException)
        val valid = signReceipt(keys, "install-1")
        val tampered = valid.replaceRange(valid.indexOf('.') + 1, valid.indexOf('.') + 2, "A")
        assertTrue(runCatching { verifier.verify(tampered, "install-1") }.exceptionOrNull() is ReceiptVerificationException)
        val unknownKid = signReceipt(keys, "install-1", kid = "unknown")
        assertTrue(runCatching { verifier.verify(unknownKid, "install-1") }.exceptionOrNull() is ReceiptVerificationException)
    }

    @Test
    fun `pending update does not revoke an existing grant`() = runBlocking {
        val keys = testKeys()
        val receipt = signReceipt(keys, "install-1")
        val store = InMemoryEntitlementStore()
        store.write(
            StoredEntitlement(receipt, "tok", "install-1", "remove_ads_lifetime", EntitlementState.OwnedVerified, 1L),
        )
        val billing = FakeBillingRepository(
            queryResult = BillingQueryResult.Success(
                listOf(PlayPurchase("tok", "remove_ads_lifetime", PlayPurchaseState.Purchased, true)),
            ),
        )
        val repository = EntitlementRepository(
            store = store,
            billing = billing,
            verifier = RecordingPurchaseVerifier(PurchaseVerifyResult(PurchaseVerifyStatus.Owned, receipt)),
            receiptVerifier = ReceiptVerifier(mapOf("test" to keys.public), "com.bloss0m.bloomwalk"),
            installIdProvider = { "install-1" },
            productId = "remove_ads_lifetime",
        )
        repository.start()
        assertEquals(EntitlementState.OwnedVerified, repository.state.value.state)
        repository.onPurchasesUpdated(
            listOf(PlayPurchase("pending-other", "remove_ads_lifetime", PlayPurchaseState.Pending, false)),
        )
        assertEquals(EntitlementState.OwnedVerified, repository.state.value.state)
        assertTrue(store.read() != null)
    }

    @Test
    fun `process death restores an existing receipt then verifies when Play is present`() = runBlocking {
        val keys = testKeys()
        val receipt = signReceipt(keys, "install-1")
        val store = InMemoryEntitlementStore()
        store.write(
            StoredEntitlement(
                receiptJws = receipt,
                purchaseToken = "tok",
                installId = "install-1",
                productId = "remove_ads_lifetime",
                state = EntitlementState.OwnedVerified,
                lastVerifiedAtMillis = 1L,
            ),
        )
        val billing = FakeBillingRepository(
            product = BillingProduct("remove_ads_lifetime", "NT$90"),
            queryResult = BillingQueryResult.Success(
                listOf(PlayPurchase("tok", "remove_ads_lifetime", PlayPurchaseState.Purchased, true)),
            ),
        )
        val first = EntitlementRepository(
            store = store,
            billing = billing,
            verifier = RecordingPurchaseVerifier(PurchaseVerifyResult(PurchaseVerifyStatus.Owned, receipt)),
            receiptVerifier = ReceiptVerifier(mapOf("test" to keys.public), "com.bloss0m.bloomwalk"),
            installIdProvider = { "install-1" },
            productId = "remove_ads_lifetime",
        )
        first.start()
        assertEquals(EntitlementState.OwnedVerified, first.state.value.state)

        val afterDeath = EntitlementRepository(
            store = store,
            billing = billing,
            verifier = RecordingPurchaseVerifier(PurchaseVerifyResult(PurchaseVerifyStatus.Owned, receipt)),
            receiptVerifier = ReceiptVerifier(mapOf("test" to keys.public), "com.bloss0m.bloomwalk"),
            installIdProvider = { "install-1" },
            productId = "remove_ads_lifetime",
        )
        afterDeath.start()
        assertEquals(EntitlementState.OwnedVerified, afterDeath.state.value.state)
        assertTrue(store.read() != null)
    }

    @Test
    fun `ITEM_ALREADY_OWNED purchase callback still grants`() = runBlocking {
        val keys = testKeys()
        val receipt = signReceipt(keys, "install-1")
        val billing = FakeBillingRepository(product = BillingProduct("remove_ads_lifetime", "NT$90"))
        val store = InMemoryEntitlementStore()
        val repository = EntitlementRepository(
            store = store,
            billing = billing,
            verifier = RecordingPurchaseVerifier(PurchaseVerifyResult(PurchaseVerifyStatus.Owned, receipt)),
            receiptVerifier = ReceiptVerifier(mapOf("test" to keys.public), "com.bloss0m.bloomwalk"),
            installIdProvider = { "install-1" },
            productId = "remove_ads_lifetime",
        )
        repository.start()
        repository.onPurchasesUpdated(
            listOf(PlayPurchase("already-owned", "remove_ads_lifetime", PlayPurchaseState.Purchased, false)),
        )
        assertEquals(EntitlementState.OwnedVerified, repository.state.value.state)
        assertEquals("already-owned", store.read()?.purchaseToken)
        assertEquals(1, billing.acknowledgedTokens.size)
    }

    @Test
    fun `account switch clears local grant only after two successful empty restores`() = runBlocking {
        val keys = testKeys()
        val receipt = signReceipt(keys, "install-1")
        val store = InMemoryEntitlementStore()
        store.write(
            StoredEntitlement(receipt, "tok", "install-1", "remove_ads_lifetime", EntitlementState.OwnedVerified, 1L),
        )
        val billing = FakeBillingRepository(
            queryResult = BillingQueryResult.Success(emptyList()),
        )
        val repository = EntitlementRepository(
            store = store,
            billing = billing,
            verifier = RecordingPurchaseVerifier(PurchaseVerifyResult(PurchaseVerifyStatus.Unavailable)),
            receiptVerifier = ReceiptVerifier(mapOf("test" to keys.public), "com.bloss0m.bloomwalk"),
            installIdProvider = { "install-1" },
            productId = "remove_ads_lifetime",
        )
        repository.start()
        assertEquals(EntitlementState.OwnedOffline, repository.state.value.state)
        repository.restore()
        assertEquals(EntitlementState.OwnedOffline, repository.state.value.state)
        assertTrue(store.read() != null)
        repository.restore()
        assertEquals(EntitlementState.Free, repository.state.value.state)
        assertEquals(null, store.read())
    }

    @Test
    fun `failed Play query never clears a local grant`() = runBlocking {
        val keys = testKeys()
        val receipt = signReceipt(keys, "install-1")
        val store = InMemoryEntitlementStore()
        store.write(
            StoredEntitlement(receipt, "tok", "install-1", "remove_ads_lifetime", EntitlementState.OwnedVerified, 1L),
        )
        val billing = FakeBillingRepository(queryResult = BillingQueryResult.Unavailable)
        val repository = EntitlementRepository(
            store = store,
            billing = billing,
            verifier = RecordingPurchaseVerifier(PurchaseVerifyResult(PurchaseVerifyStatus.Unavailable)),
            receiptVerifier = ReceiptVerifier(mapOf("test" to keys.public), "com.bloss0m.bloomwalk"),
            installIdProvider = { "install-1" },
            productId = "remove_ads_lifetime",
        )
        repository.start()
        repository.restore()
        repository.restore()
        assertEquals(EntitlementState.OwnedOffline, repository.state.value.state)
        assertTrue(store.read() != null)
    }

    @Test
    fun `turning purchases off does not clear a paid grant`() = runBlocking {
        val keys = testKeys()
        val receipt = signReceipt(keys, "install-1")
        val store = InMemoryEntitlementStore()
        store.write(
            StoredEntitlement(receipt, "tok", "install-1", "remove_ads_lifetime", EntitlementState.OwnedVerified, 1L),
        )
        val billing = FakeBillingRepository(
            queryResult = BillingQueryResult.Success(
                listOf(PlayPurchase("tok", "remove_ads_lifetime", PlayPurchaseState.Purchased, true)),
            ),
        )
        val repository = EntitlementRepository(
            store = store,
            billing = billing,
            verifier = RecordingPurchaseVerifier(PurchaseVerifyResult(PurchaseVerifyStatus.Owned, receipt)),
            receiptVerifier = ReceiptVerifier(mapOf("test" to keys.public), "com.bloss0m.bloomwalk"),
            installIdProvider = { "install-1" },
            productId = "remove_ads_lifetime",
        )
        repository.start()
        repository.setPurchasesEnabled(false)
        assertEquals(EntitlementState.OwnedVerified, repository.state.value.state)
        assertFalse(repository.state.value.purchasesEnabled)
        assertTrue(store.read() != null)
    }

    @Test
    fun `product details unavailable blocks a new purchase offer`() = runBlocking {
        val repository = repository(billing = FakeBillingRepository(product = null))
        repository.start()
        assertEquals(EntitlementState.Free, repository.state.value.state)
        assertFalse(repository.state.value.productDetailsAvailable)
        repository.setPurchasesEnabled(true)
        assertTrue(repository.state.value.purchasesEnabled)
        assertFalse(repository.state.value.productDetailsAvailable)
    }

    @Test
    fun `invalid stored receipt is not treated as owned`() = runBlocking {
        val keys = testKeys()
        val store = InMemoryEntitlementStore()
        store.write(
            StoredEntitlement(
                receiptJws = "not-a-jws",
                purchaseToken = "tok",
                installId = "install-1",
                productId = "remove_ads_lifetime",
                state = EntitlementState.OwnedVerified,
                lastVerifiedAtMillis = 1L,
            ),
        )
        val repository = EntitlementRepository(
            store = store,
            billing = FakeBillingRepository(),
            verifier = RecordingPurchaseVerifier(PurchaseVerifyResult(PurchaseVerifyStatus.Unavailable)),
            receiptVerifier = ReceiptVerifier(mapOf("test" to keys.public), "com.bloss0m.bloomwalk"),
            installIdProvider = { "install-1" },
            productId = "remove_ads_lifetime",
        )
        repository.start()
        assertEquals(EntitlementState.Free, repository.state.value.state)
    }

    private fun repository(
        store: EntitlementStore = InMemoryEntitlementStore(),
        billing: FakeBillingRepository = FakeBillingRepository(),
        verify: PurchaseVerifyResult = PurchaseVerifyResult(PurchaseVerifyStatus.Unavailable),
        receiptVerifier: ReceiptVerifier? = null,
    ) = EntitlementRepository(
        store = store,
        billing = billing,
        verifier = RecordingPurchaseVerifier(verify),
        receiptVerifier = receiptVerifier,
        installIdProvider = { "install-1" },
        productId = "remove_ads_lifetime",
    )

    private class RecordingPurchaseVerifier(
        private val result: PurchaseVerifyResult,
    ) : PurchaseVerifier(client = unusedClient()) {
        override suspend fun verify(purchaseToken: String, installId: String): PurchaseVerifyResult = result
    }
}

private fun unusedClient(): com.sora.mockgps.core.network.GatewayClient {
    return com.sora.mockgps.core.network.GatewayClient(
        config = com.sora.mockgps.core.network.GatewayClientConfig(
            baseUrl = "https://gateway.example.test",
            clientVersion = "test",
        ),
        installIdProvider = { "install-1" },
    )
}

private fun testKeys(): java.security.KeyPair {
    val generator = KeyPairGenerator.getInstance("EC")
    generator.initialize(ECGenParameterSpec("secp256r1"))
    return generator.generateKeyPair()
}

private fun signReceipt(
    keys: java.security.KeyPair,
    installId: String,
    productId: String = "remove_ads_lifetime",
    verifiedAt: Any = 1_700_000_000L,
    kid: String = "test",
): String {
    val header = base64UrlEncode("""{"alg":"ES256","kid":"$kid"}""".toByteArray())
    val payload = base64UrlEncode(
        JSONObject()
            .put("iss", ReceiptVerifier.DEFAULT_ISSUER)
            .put("aud", "com.bloss0m.bloomwalk")
            .put("productId", productId)
            .put("installId", installId)
            .put("verifiedAt", verifiedAt)
            .put("receiptVersion", 1)
            .toString()
            .toByteArray(),
    )
    val signingInput = "$header.$payload"
    val signer = Signature.getInstance("SHA256withECDSA")
    signer.initSign(keys.private)
    signer.update(signingInput.toByteArray())
    val jose = derToJose(signer.sign())
    return "$signingInput.${base64UrlEncode(jose)}"
}
