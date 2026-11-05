package com.termllm.app.push

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Web Push `aes128gcm` decryption (RFC 8188 + RFC 8291), ported from the iOS
 * `WebPushCrypto`: P-256 ECDH, HKDF-SHA256 with the subscription salt and the
 * "WebPush: info\0" context, then AES-128-GCM over the record.
 *
 * A body is: salt(16) | rs(4, big endian) | idlen(1) | keyid(idlen) | ciphertext.
 * Anything malformed is rejected rather than half-decrypted.
 */
object PushCrypto {

    class DecryptException(message: String) : Exception(message)

    /** Generate the browser-side subscription key pair (P-256). */
    fun generateKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        return generator.generateKeyPair()
    }

    /** Uncompressed point encoding of a public key, which is what keyid carries. */
    fun publicKeyBytes(key: PublicKey): ByteArray {
        val ec = key as ECPublicKey
        val x = fixed(ec.w.affineX.toByteArray(), 32)
        val y = fixed(ec.w.affineY.toByteArray(), 32)
        return byteArrayOf(0x04) + x + y
    }

    fun publicKeyFromBytes(raw: ByteArray): PublicKey {
        if (raw.size != 65 || raw[0] != 0x04.toByte()) throw DecryptException("keyid is not an uncompressed P-256 point")
        // Build the DER SubjectPublicKeyInfo the JCA expects.
        val header = byteArrayOf(
            0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x02, 0x01,
            0x06, 0x08, 0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x03, 0x01, 0x07, 0x03, 0x42, 0x00,
        )
        val der = header + raw
        return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
    }

    /**
     * Decrypt one aes128gcm record (RFC 8291). The subscription's auth secret is
     * part of the key schedule, so a message encrypted for one route cannot be
     * opened with another route's secret even when the VAPID key is shared.
     */
    fun decrypt(
        record: ByteArray,
        privateKey: PrivateKey,
        publicKeyBytes: ByteArray,
        authSecret: ByteArray,
    ): ByteArray {
        if (record.size < 16 + 4 + 1 + 1) throw DecryptException("record is too short")
        val salt = record.copyOfRange(0, 16)
        val rs = ((record[16].toInt() and 0xff) shl 24) or ((record[17].toInt() and 0xff) shl 16) or
            ((record[18].toInt() and 0xff) shl 8) or (record[19].toInt() and 0xff)
        if (rs < 18) throw DecryptException("record size is too small")
        val idLength = record[20].toInt() and 0xff
        if (record.size < 21 + idLength) throw DecryptException("record is missing its key id")
        val keyId = record.copyOfRange(21, 21 + idLength)
        var offset = 21 + idLength
        val chunk = 16
        val ciphertext = ByteArray(record.size - offset)
        System.arraycopy(record, offset, ciphertext, 0, ciphertext.size)
        if (ciphertext.size <= chunk) throw DecryptException("record carries no ciphertext")

        val ephemeral = publicKeyFromBytes(keyId)
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(ephemeral, true)
        val sharedSecret = agreement.generateSecret()

        val keyInfo = "WebPush: info".toByteArray() + byteArrayOf(0) + publicKeyBytes + keyId
        // RFC 8291 §3.4: HKDF(auth_secret, ecdh_secret, "WebPush: info" || ua_public || as_public).
        val ikm = hkdf(authSecret, sharedSecret, keyInfo, 32)
        val cek = hkdf(salt, ikm, "Content-Encoding: aes128gcm".toByteArray() + byteArrayOf(0), 16)
        val nonce = hkdf(salt, ikm, "Content-Encoding: nonce".toByteArray() + byteArrayOf(0), 12)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, nonce))
        val plain = try {
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            throw DecryptException("record did not decrypt: " + (e.message ?: "bad tag"))
        }
        return unpad(plain)
    }

    /**
     * Encrypt one aes128gcm record for [publicKeyBytes]. This is the sender side of
     * the same scheme; it exists so the app can prove the loop against its own
     * enrolled subscription in tests without a browser.
     */
    fun seal(payload: ByteArray, publicKeyBytes: ByteArray, authSecret: ByteArray): ByteArray {
        val ephemeral = generateKeyPair()
        val asPublic = publicKeyBytes(ephemeral.public)
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(ephemeral.private)
        agreement.doPhase(publicKeyFromBytes(publicKeyBytes), true)
        val shared = agreement.generateSecret()
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val ikm = hkdf(authSecret, shared, "WebPush: info".toByteArray() + byteArrayOf(0) + publicKeyBytes + asPublic, 32)
        val cek = hkdf(salt, ikm, "Content-Encoding: aes128gcm".toByteArray() + byteArrayOf(0), 16)
        val nonce = hkdf(salt, ikm, "Content-Encoding: nonce".toByteArray() + byteArrayOf(0), 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, nonce))
        val ciphertext = cipher.doFinal(payload + byteArrayOf(0x02))
        val rs = ByteArray(4).also { rs ->
            val value = 4096
            rs[0] = ((value shr 24) and 0xff).toByte()
            rs[1] = ((value shr 16) and 0xff).toByte()
            rs[2] = ((value shr 8) and 0xff).toByte()
            rs[3] = (value and 0xff).toByte()
        }
        return salt + rs + byteArrayOf(asPublic.size.toByte()) + asPublic + ciphertext
    }

    /** RFC 8188 padding: trailing zeros with a final 0x01 (last record) or 0x02. */
    private fun unpad(plain: ByteArray): ByteArray {
        var index = plain.size - 1
        while (index >= 0 && plain[index] == 0.toByte()) index -= 1
        if (index < 0) throw DecryptException("record padding is invalid")
        if (plain[index] != 0x01.toByte() && plain[index] != 0x02.toByte()) {
            throw DecryptException("record has no padding delimiter")
        }
        return plain.copyOfRange(0, index)
    }

    fun hkdf(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val effectiveSalt = if (salt.isEmpty()) ByteArray(32) else salt
        mac.init(SecretKeySpec(effectiveSalt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        // HKDF-Expand: T(n) = HMAC(PRK, T(n-1) || info || n).
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var written = 0
        var counter = 1
        while (written < length) {
            mac.reset()
            mac.update(previous)
            mac.update(info)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            val take = minOf(previous.size, length - written)
            System.arraycopy(previous, 0, out, written, take)
            written += take
            counter += 1
        }
        return out
    }

    /** Deterministic window identity from the iOS contract: native_ + base64url(SHA256(id)). */
    fun windowId(persistentIdentifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(persistentIdentifier.toByteArray(Charsets.UTF_8))
        return "native_" + base64Url(digest)
    }

    fun base64Url(raw: ByteArray): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw)

    fun base64UrlDecode(text: String): ByteArray {
        val raw = text.trim().trimEnd('=')
        return try {
            java.util.Base64.getUrlDecoder().decode(raw)
        } catch (e: IllegalArgumentException) {
            // Standard-alphabet bodies (older relay builds) still decode.
            java.util.Base64.getDecoder().decode(raw)
        }
    }

    private fun fixed(value: ByteArray, size: Int): ByteArray {
        if (value.size == size) return value
        if (value.size > size) return value.copyOfRange(value.size - size, value.size)
        val out = ByteArray(size)
        System.arraycopy(value, 0, out, size - value.size, value.size)
        return out
    }
}
