package com.termllm.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class ServerKind { UNKNOWN, HUB, DIRECT_NODE }

/**
 * A saved source. Ported from the iOS HubConnection catalog entry: names,
 * kinds, URLs and stable identities live here; credentials are kept separately
 * and are never written into URLs, defaults or logs.
 */
@Serializable
data class Connection(
    val id: String,
    val name: String,
    val kind: ServerKind = ServerKind.UNKNOWN,
    val url: String,
    val needsKindReview: Boolean = false,
)

@Serializable
data class ConnectionCatalog(
    val connections: List<Connection> = emptyList(),
    val selectedId: String? = null,
)

class ConnectionStore(context: Context) {

    private val file = File(context.filesDir, "connections.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    fun load(): ConnectionCatalog = try {
        if (file.exists()) json.decodeFromString(ConnectionCatalog.serializer(), file.readText()) else ConnectionCatalog()
    } catch (e: Exception) {
        ConnectionCatalog()
    }

    fun save(catalog: ConnectionCatalog) {
        file.writeText(json.encodeToString(ConnectionCatalog.serializer(), catalog))
    }
}

/**
 * Per-connection bearer token. iOS holds these in a connection-scoped Keychain
 * item; Android keeps them AES/GCM encrypted with an AndroidKeyStore key in
 * app-private storage. Tokens are keyed by connection identity, so editing a
 * connection URL or kind (which mints a new identity) cannot replay an old
 * credential at a new endpoint.
 */
class CredentialStore(context: Context) {

    private val prefs = context.getSharedPreferences("termllm_tokens", Context.MODE_PRIVATE)

    fun read(id: String): String? = prefs.getString(key(id), null)?.let { decrypt(it) }

    fun write(id: String, token: String?) {
        if (token.isNullOrEmpty()) {
            prefs.edit().remove(key(id)).commit()
        } else {
            prefs.edit().putString(key(id), encrypt(token)).commit()
        }
    }

    /** Browser session a passkey sign-in redeemed for this connection (never a bearer). */
    fun readSession(id: String): com.termllm.app.auth.NativeLogin.SessionCookie? =
        prefs.getString("session_" + id, null)?.let { decrypt(it) }?.let { text ->
            runCatching {
                Json.decodeFromString(com.termllm.app.auth.NativeLogin.SessionCookie.serializer(), text)
            }.getOrNull()
        }

    fun writeSession(id: String, cookie: com.termllm.app.auth.NativeLogin.SessionCookie?) {
        if (cookie == null) {
            prefs.edit().remove("session_" + id).commit()
        } else {
            val text = Json.encodeToString(com.termllm.app.auth.NativeLogin.SessionCookie.serializer(), cookie)
            prefs.edit().putString("session_" + id, encrypt(text)).commit()
        }
    }

    fun clearAll() = prefs.edit().clear().commit()

    private fun key(id: String) = "token_" + id

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val payload = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String? = try {
        val parts = stored.split(":", limit = 2)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
        String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "termllm_bearer_tokens"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
