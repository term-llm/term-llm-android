package com.termllm.app.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Who may use this phone's device tools.
 *
 * Ported from the iOS `DeviceToolsSettings` contract — one global "Share device
 * tools" switch, off until the user turns it on — with the per-source consent
 * Astra's privacy rules ask for on Android: a saved server gets nothing unless it
 * was granted, and revoking takes the tools away immediately.
 *
 * Tools reach a page only through this object: when sharing is off, or the source
 * was not granted, the bridge is never installed, so `document.modelContext` does
 * not exist and no tool can be registered or called.
 */
class DeviceToolSharing(private val file: File) {

    @Serializable
    data class State(
        val enabled: Boolean = false,
        val sources: Set<String> = emptySet(),
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun state(): State {
        val text = if (file.exists()) file.readText() else return State()
        return try {
            json.decodeFromString(State.serializer(), text)
        } catch (e: Exception) {
            // An unreadable consent file must never grant anything.
            State()
        }
    }

    fun isGloballyEnabled(): Boolean = state().enabled

    /** True only when sharing is on *and* this source was granted. */
    fun isShared(connectionId: String): Boolean {
        val current = state()
        return current.enabled && connectionId in current.sources
    }

    fun grantedSources(): Set<String> = state().sources

    fun setEnabled(enabled: Boolean) = write(state().copy(enabled = enabled))

    fun grant(connectionId: String) = write(state().let { it.copy(sources = it.sources + connectionId) })

    fun revoke(connectionId: String) = write(state().let { it.copy(sources = it.sources - connectionId) })

    /** A removed source must not leave a standing grant behind. */
    fun forget(connectionId: String) = revoke(connectionId)

    private fun write(next: State) {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(State.serializer(), next))
    }
}
