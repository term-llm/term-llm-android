package com.termllm.app.listen

import kotlinx.serialization.Serializable

/**
 * Lifecycle of one offline download. `COMPLETE` is the only state the rest of the
 * app may treat as "ready to play from disk"; everything else is resumable work.
 */
@Serializable
enum class DownloadStatus { QUEUED, RUNNING, PAUSED, COMPLETE, FAILED, CANCELLED }

/**
 * Durable per-item download state. Bytes/progress live here (not in the queue)
 * so a process death during a large episode cannot lose the resume point, and
 * so the UI can show progress coming from a worker in the same process.
 */
@Serializable
data class DownloadRecord(
    val itemId: String,
    val url: String,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val bytes: Long = 0L,
    val totalBytes: Long? = null,
    val etag: String? = null,
    val attempts: Int = 0,
    /** Consecutive failed attempts; a budget pause or a fresh request resets it. */
    val failures: Int = 0,
    val error: String? = null,
    val updatedAt: Long = 0L,
) {
    /** Work that is queued, running, or paused mid-transfer. */
    val active: Boolean
        get() = status == DownloadStatus.QUEUED || status == DownloadStatus.RUNNING || status == DownloadStatus.PAUSED

    /** Fraction complete, or null when the remote has not advertised a length. */
    val progress: Float?
        get() {
            val total = totalBytes ?: return null
            if (total <= 0L) return null
            return (bytes.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
        }
}

@Serializable
data class DownloadSnapshot(val records: List<DownloadRecord> = emptyList())
