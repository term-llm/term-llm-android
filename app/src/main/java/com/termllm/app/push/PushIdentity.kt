package com.termllm.app.push

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyPair
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * This device's push identity: a P-256 subscription key pair (the private half is
 * encrypted with an AndroidKeyStore AES key, so it never sits in clear text in app
 * storage), a device token the relay uses for the endpoint, and one auth secret per
 * route so two routes sharing a VAPID key cannot open each other's messages.
 *
 * Relay endpoints, secrets and routing metadata are *not* stored here: they live in
 * [PushRouteStore] keyed by connection, because they are per source. The legacy
 * single-route fields below are read once by the migration and never written again.
 */
class PushIdentity(context: Context) {

    @Serializable
    data class Record(
        val publicKey: String = "",
        val deviceToken: String = "",
        val authSecrets: Map<String, String> = emptyMap(),
        /** Legacy (pre-2.x) single-route stream endpoint; migrated by PushRouteStore. */
        val endpoint: String? = null,
        /** Legacy single-route Web Push URL (what a server posts a record to). */
        val pushEndpoint: String? = null,
        /** Legacy single relay base URL. */
        val relay: String? = null,
        /** Legacy single device secret. */
        val deviceSecret: String = "",
        val wrappedPrivateKey: String = "",
        val iv: String = "",
        val windowId: String = "",
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val dir = File(context.filesDir, "push").apply { mkdirs() }
    private val file = File(dir, "identity.json")
    private val privateFile = File(dir, "identity.key")

    val windowId: String by lazy {
        // Retain existing enrolled identities, but never derive a new window id
        // from a hardware/Android identifier shared across source enrollments.
        load().windowId.takeIf { it.isNotBlank() }
            ?: PushCrypto.windowId("install:" + java.util.UUID.randomUUID().toString())
    }

    @Synchronized
    fun load(): Record = if (!file.exists()) Record() else runCatching {
        json.decodeFromString(Record.serializer(), file.readText())
    }.getOrElse { Record() }

    fun exists(): Boolean = file.exists() && privateFile.exists()

    /** Every route that already has an auth secret (used by delivery to decode). */
    @Synchronized
    fun routeKeys(): Set<String> = load().authSecrets.keys

    /** Create the key pair, device token and auth secret for [routeKey] if needed. */
    @Synchronized
    fun ensure(routeKey: String): Record {
        var record = load()
        if (!privateFile.exists() || record.publicKey.isBlank()) {
            val pair = PushCrypto.generateKeyPair()
            // The wrap writes the encrypted key and its IV; keep that record, then add
            // the public half — saving the pre-wrap copy would erase the IV.
            record = storePrivate(pair.private, record)
            record = record.copy(publicKey = PushCrypto.base64Url(PushCrypto.publicKeyBytes(pair.public)))
        }
        if (record.deviceToken.isBlank()) {
            val token = ByteArray(32).also { SecureRandom().nextBytes(it) }
            record = record.copy(deviceToken = token.joinToString("") { String.format("%02x", it) })
        }
        if (record.authSecrets[routeKey].isNullOrBlank()) {
            val secret = ByteArray(16).also { SecureRandom().nextBytes(it) }
            record = record.copy(authSecrets = record.authSecrets + (routeKey to PushCrypto.base64Url(secret)))
        }
        if (record.windowId.isBlank()) record = record.copy(windowId = windowId)
        save(record)
        return record
    }

    /**
     * Copy the legacy route's auth secret onto the connection that adopted its
     * subscription, so messages the old node is still encrypting keep working.
     */
    @Synchronized
    fun adoptSecret(from: String, to: String): Boolean {
        val record = load()
        val secret = record.authSecrets[from] ?: return false
        if (!record.authSecrets[to].isNullOrBlank()) return false
        save(record.copy(authSecrets = record.authSecrets + (to to secret)))
        return true
    }

    /** Forget one route's auth secret (used when a connection is removed). */
    @Synchronized
    fun forgetSecret(routeKey: String) {
        val record = load()
        if (!record.authSecrets.containsKey(routeKey)) return
        save(record.copy(authSecrets = record.authSecrets - routeKey))
    }

    fun publicKey(record: Record = load()): java.security.PublicKey =
        PushCrypto.publicKeyFromBytes(PushCrypto.base64UrlDecode(record.publicKey))

    fun authSecret(routeKey: String, record: Record = load()): ByteArray? =
        record.authSecrets[routeKey]?.let { PushCrypto.base64UrlDecode(it) }

    fun privateKey(): PrivateKey {
        val record = load()
        val key = keystoreKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, PushCrypto.base64UrlDecode(record.iv)))
        val pkcs8 = cipher.doFinal(PushCrypto.base64UrlDecode(record.wrappedPrivateKey))
        return java.security.KeyFactory.getInstance("EC")
            .generatePrivate(java.security.spec.PKCS8EncodedKeySpec(pkcs8))
    }

    @Synchronized
    fun save(record: Record) {
        file.writeText(json.encodeToString(Record.serializer(), record))
    }

    private fun storePrivate(key: PrivateKey, base: Record): Record {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
        val wrapped = cipher.doFinal(key.encoded)
        privateFile.writeBytes(wrapped)
        val record = base.copy(
            wrappedPrivateKey = PushCrypto.base64Url(wrapped),
            iv = PushCrypto.base64Url(cipher.iv),
        )
        save(record)
        return record
    }

    private fun keystoreKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val KEY_ALIAS = "termllm_push_identity"
    }
}
