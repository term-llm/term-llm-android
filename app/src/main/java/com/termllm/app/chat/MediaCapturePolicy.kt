package com.termllm.app.chat

/**
 * Pure WebView capture-permission policy, the Android counterpart of iOS
 * `BrowserView.mediaCaptureDecision`: the configured source's own origin may use
 * the microphone (after the OS permission), cameras and other origins are
 * prompted, anything else is denied. Granting here never bypasses Android's
 * app-level RECORD_AUDIO / CAMERA authorization, which the UI layer requests.
 */
object MediaCapturePolicy {

    const val MICROPHONE = "android.webkit.resource.AUDIO_CAPTURE"
    const val CAMERA = "android.webkit.resource.VIDEO_CAPTURE"

    enum class Decision { GRANT, PROMPT, DENY }

    fun decide(
        allowsMediaCapture: Boolean,
        requestOrigin: String,
        sourceOrigin: String,
        resources: List<String>,
    ): Decision {
        if (!allowsMediaCapture || resources.isEmpty()) return Decision.DENY
        // Only microphone/camera are capture requests; protected media, MIDI and
        // unknown resources are never granted by this app.
        if (resources.any { it != MICROPHONE && it != CAMERA }) return Decision.DENY
        val sameOrigin = normalize(requestOrigin) == normalize(sourceOrigin)
        return if (resources == listOf(MICROPHONE) && sameOrigin) Decision.GRANT else Decision.PROMPT
    }

    /** Android permission names the decision needs before a grant can be answered. */
    fun androidPermissions(resources: List<String>): List<String> = buildList {
        if (MICROPHONE in resources) add(android.Manifest.permission.RECORD_AUDIO)
        if (CAMERA in resources) add(android.Manifest.permission.CAMERA)
    }

    private fun normalize(origin: String): String {
        val trimmed = origin.trim().trimEnd('/').lowercase()
        val url = runCatching { java.net.URI(trimmed) }.getOrNull() ?: return trimmed
        val scheme = url.scheme ?: return trimmed
        val host = url.host ?: return trimmed
        val defaultPort = if (scheme == "https") 443 else if (scheme == "http") 80 else -1
        val port = if (url.port == -1) defaultPort else url.port
        return "$scheme://$host:$port"
    }
}
