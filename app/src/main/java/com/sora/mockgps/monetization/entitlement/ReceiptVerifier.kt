package com.sora.mockgps.monetization.entitlement

import org.json.JSONObject
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.time.Instant
import java.util.Base64

data class ReceiptClaims(
    val kid: String,
    val issuer: String,
    val audience: String,
    val productId: String,
    val installId: String,
    val verifiedAt: Long,
    val receiptVersion: Int,
)

class ReceiptVerificationException(message: String) : Exception(message)

class ReceiptVerifier(
    private val publicKeysByKid: Map<String, PublicKey>,
    private val expectedAudience: String,
    private val expectedIssuer: String = DEFAULT_ISSUER,
    private val expectedProductId: String = DEFAULT_PRODUCT_ID,
) {
    fun verify(jws: String, expectedInstallId: String): ReceiptClaims {
        val parts = jws.split('.')
        if (parts.size != 3) throw ReceiptVerificationException("Receipt is not a JWS compact serialization.")
        val header = JSONObject(String(base64UrlDecode(parts[0]), Charsets.UTF_8))
        if (header.optString("alg") != "ES256") {
            throw ReceiptVerificationException("Receipt algorithm is not ES256.")
        }
        val kid = header.optString("kid").ifBlank {
            throw ReceiptVerificationException("Receipt is missing kid.")
        }
        val key = publicKeysByKid[kid]
            ?: throw ReceiptVerificationException("Receipt kid is unknown.")
        val signingInput = "${parts[0]}.${parts[1]}".toByteArray(Charsets.UTF_8)
        val signature = joseToDer(base64UrlDecode(parts[2]))
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(key)
        verifier.update(signingInput)
        if (!verifier.verify(signature)) {
            throw ReceiptVerificationException("Receipt signature is invalid.")
        }
        val payload = JSONObject(String(base64UrlDecode(parts[1]), Charsets.UTF_8))
        val claims = ReceiptClaims(
            kid = kid,
            issuer = payload.optString("iss"),
            audience = payload.optString("aud"),
            productId = payload.optString("productId"),
            installId = payload.optString("installId"),
            verifiedAt = parseVerifiedAtMillis(payload),
            receiptVersion = payload.optInt("receiptVersion"),
        )
        if (claims.issuer != expectedIssuer) throw ReceiptVerificationException("Receipt issuer is invalid.")
        if (claims.audience != expectedAudience) throw ReceiptVerificationException("Receipt audience is invalid.")
        if (claims.productId != expectedProductId) throw ReceiptVerificationException("Receipt product is invalid.")
        if (claims.installId != expectedInstallId) throw ReceiptVerificationException("Receipt install binding is invalid.")
        if (claims.receiptVersion < 1) throw ReceiptVerificationException("Receipt version is invalid.")
        return claims
    }

    companion object {
        const val DEFAULT_ISSUER = "bloomwalk-gps-gateway"
        const val DEFAULT_PRODUCT_ID = "remove_ads_lifetime"
    }
}

internal fun parseVerifiedAtMillis(payload: JSONObject): Long {
    if (!payload.has("verifiedAt") || payload.isNull("verifiedAt")) return 0L
    val raw = payload.get("verifiedAt")
    if (raw is Number) return raw.toLong()
    val text = raw.toString().trim()
    if (text.isEmpty()) return 0L
    text.toLongOrNull()?.let { return it }
    return runCatching { Instant.parse(text).toEpochMilli() }.getOrDefault(0L)
}

fun parseReceiptPublicKeysJson(raw: String): Map<String, String> {
    if (raw.isBlank() || raw == "[]") return emptyMap()
    val root = JSONObject(raw)
    val keys = linkedMapOf<String, String>()
    root.keys().forEach { kid ->
        keys[kid] = root.getString(kid)
    }
    return keys
}

fun publicKeyFromUncompressedP256(hexOrBase64: String): PublicKey {
    val bytes = if (hexOrBase64.startsWith("04") && hexOrBase64.length == 130) {
        hexOrBase64.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    } else {
        Base64.getDecoder().decode(hexOrBase64)
    }
    require(bytes.size == 65 && bytes[0] == 0x04.toByte()) { "P-256 public key must be uncompressed." }
    val x = BigInteger(1, bytes.copyOfRange(1, 33))
    val y = BigInteger(1, bytes.copyOfRange(33, 65))
    val parameters = AlgorithmParameters.getInstance("EC")
    parameters.init(ECGenParameterSpec("secp256r1"))
    val spec = parameters.getParameterSpec(ECParameterSpec::class.java)
    return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), spec))
}

internal fun base64UrlDecode(value: String): ByteArray {
    var normalized = value.replace('-', '+').replace('_', '/')
    while (normalized.length % 4 != 0) normalized += "="
    return Base64.getDecoder().decode(normalized)
}

internal fun joseToDer(jose: ByteArray): ByteArray {
    require(jose.size == 64) { "ES256 signature must be 64 bytes." }
    var r = jose.copyOfRange(0, 32).dropWhile { it == 0.toByte() }.toByteArray()
    var s = jose.copyOfRange(32, 64).dropWhile { it == 0.toByte() }.toByteArray()
    if (r.isEmpty()) r = byteArrayOf(0)
    if (s.isEmpty()) s = byteArrayOf(0)
    if (r[0] < 0) r = byteArrayOf(0) + r
    if (s[0] < 0) s = byteArrayOf(0) + s
    val sequence = ByteArray(6 + r.size + s.size)
    sequence[0] = 0x30
    sequence[1] = (4 + r.size + s.size).toByte()
    sequence[2] = 0x02
    sequence[3] = r.size.toByte()
    System.arraycopy(r, 0, sequence, 4, r.size)
    sequence[4 + r.size] = 0x02
    sequence[5 + r.size] = s.size.toByte()
    System.arraycopy(s, 0, sequence, 6 + r.size, s.size)
    return sequence
}

internal fun derToJose(der: ByteArray): ByteArray {
    require(der[0] == 0x30.toByte())
    var offset = 2
    require(der[offset] == 0x02.toByte())
    val rLen = der[offset + 1].toInt() and 0xff
    val r = der.copyOfRange(offset + 2, offset + 2 + rLen)
    offset += 2 + rLen
    require(der[offset] == 0x02.toByte())
    val sLen = der[offset + 1].toInt() and 0xff
    val s = der.copyOfRange(offset + 2, offset + 2 + sLen)
    return leftPad32(r) + leftPad32(s)
}

internal fun leftPad32(value: ByteArray): ByteArray {
    val stripped = value.dropWhile { it == 0.toByte() }.toByteArray()
    return ByteArray(32 - stripped.size) + stripped
}

internal fun base64UrlEncode(value: ByteArray): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(value)
