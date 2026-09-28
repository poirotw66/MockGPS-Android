package com.sora.mockgps.monetization.entitlement

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class StoredEntitlement(
    val receiptJws: String,
    val purchaseToken: String,
    val installId: String,
    val productId: String,
    val state: EntitlementState,
    val lastVerifiedAtMillis: Long,
)

interface EntitlementStore {
    fun read(): StoredEntitlement?
    fun write(value: StoredEntitlement)
    fun clear()
}

class InMemoryEntitlementStore : EntitlementStore {
    private var value: StoredEntitlement? = null
    override fun read(): StoredEntitlement? = value
    override fun write(value: StoredEntitlement) {
        this.value = value
    }
    override fun clear() {
        value = null
    }
}

/**
 * App-private encrypted entitlement store. Key material lives in Android Keystore.
 * This file is excluded from Room backups and JSON export.
 */
class KeystoreEntitlementStore(
    context: Context,
    private val file: File = File(context.applicationContext.filesDir, FILE_NAME),
) : EntitlementStore {
    override fun read(): StoredEntitlement? {
        if (!file.exists()) return null
        val packed = file.readBytes()
        if (packed.size <= IV_SIZE) return null
        val iv = packed.copyOfRange(0, IV_SIZE)
        val ciphertext = packed.copyOfRange(IV_SIZE, packed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        val json = JSONObject(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
        return StoredEntitlement(
            receiptJws = json.getString("receiptJws"),
            purchaseToken = json.getString("purchaseToken"),
            installId = json.getString("installId"),
            productId = json.getString("productId"),
            state = EntitlementState.valueOf(json.getString("state")),
            lastVerifiedAtMillis = json.getLong("lastVerifiedAtMillis"),
        )
    }

    override fun write(value: StoredEntitlement) {
        val json = JSONObject()
            .put("receiptJws", value.receiptJws)
            .put("purchaseToken", value.purchaseToken)
            .put("installId", value.installId)
            .put("productId", value.productId)
            .put("state", value.state.name)
            .put("lastVerifiedAtMillis", value.lastVerifiedAtMillis)
            .toString()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val packed = cipher.iv + cipher.doFinal(json.toByteArray(Charsets.UTF_8))
        file.parentFile?.mkdirs()
        file.writeBytes(packed)
    }

    override fun clear() {
        if (file.exists()) file.delete()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val FILE_NAME = "entitlement.store"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "bloomwalk_entitlement"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}
