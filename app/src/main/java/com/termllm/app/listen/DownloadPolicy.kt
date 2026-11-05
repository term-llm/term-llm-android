package com.termllm.app.listen

/**
 * Tunables and pure decisions for durable offline downloads. Everything here is
 * deterministic and free of Android APIs so the retry, space, budget and
 * completion rules are unit-testable without an emulator.
 */
data class DownloadPreferences(
    /** Metered networks are allowed unless the user opted into unmetered-only. */
    val allowMetered: Boolean = true,
    /** Free bytes that must survive a download so the device stays usable. */
    val reserveBytes: Long = 128L * 1024L * 1024L,
    /** Hard cap for a single episode: a hostile or wrong URL cannot fill the disk. */
    val maxBytes: Long = 2L * 1024L * 1024L * 1024L,
)

data class DownloadConstraints(
    val networkRequired: Boolean = true,
    /** True when the job must wait for an unmetered network. */
    val unmetered: Boolean = false,
)

/** What the worker should do with an attempt when it finishes. */
enum class DownloadStep { RETRY, GIVE_UP }

object DownloadPolicy {

    /** Consecutive failed attempts (first try included) before giving up. */
    const val MAX_ATTEMPTS = 5

    const val MIN_BACKOFF_SECONDS = 10L

    /**
     * Wall-clock budget for one worker attempt. WorkManager kills a job around ten
     * minutes; stopping ourselves well before that lets us checkpoint the partial
     * file (and its validator) and let the next run resume instead of losing the
     * transfer to a kill. Deliberately NOT a foreground service and NOT a build flag.
     */
    const val ATTEMPT_BUDGET_MILLIS = 8L * 60L * 1000L

    private const val MAX_BACKOFF_SECONDS = 30L * 60L

    private val NAME_UNSAFE = Regex("[^A-Za-z0-9._-]")

    /** Stable on-disk name for an item; unchanged from the previous implementation. */
    fun fileNameFor(itemId: String): String = itemId.replace(NAME_UNSAFE, "_") + ".audio"

    fun constraintsFor(preferences: DownloadPreferences): DownloadConstraints =
        DownloadConstraints(networkRequired = true, unmetered = !preferences.allowMetered)

    /** Exponential backoff for attempt 1..n: 10s, 20s, 40s, … capped at 30 minutes. */
    fun backoffSeconds(attempt: Int): Long {
        val shift = (attempt.coerceAtLeast(1) - 1).coerceAtMost(16)
        return (MIN_BACKOFF_SECONDS shl shift).coerceIn(MIN_BACKOFF_SECONDS, MAX_BACKOFF_SECONDS)
    }

    /** A server `Retry-After` never shortens the local backoff. */
    fun retryDelaySeconds(attempt: Int, retryAfterSeconds: Long?): Long =
        maxOf(backoffSeconds(attempt), (retryAfterSeconds ?: 0L).coerceIn(0L, MAX_BACKOFF_SECONDS))

    fun canRetry(failures: Int, retryable: Boolean): Boolean = retryable && failures < MAX_ATTEMPTS

    /**
     * A budget pause is never a failure — it must always be rescheduled, however
     * many times the download needs. Only real failures count against the cap.
     */
    fun step(paused: Boolean, failures: Int): DownloadStep = when {
        paused -> DownloadStep.RETRY
        failures < MAX_ATTEMPTS -> DownloadStep.RETRY
        else -> DownloadStep.GIVE_UP
    }

    /**
     * `complete` is only ever true on an exact byte match with a known, positive
     * advertised length — never "at least", which is how truncated audio used to
     * become playable-but-broken.
     */
    fun canMarkComplete(bytes: Long, totalBytes: Long?): Boolean =
        totalBytes != null && totalBytes > 0L && bytes == totalBytes

    fun hasSpace(availableBytes: Long?, requiredBytes: Long?, reserveBytes: Long): Boolean {
        if (availableBytes == null) return true
        val reserve = reserveBytes.coerceAtLeast(0L)
        if (availableBytes <= reserve) return false
        val required = (requiredBytes ?: return true).coerceAtLeast(0L)
        return availableBytes - required >= reserve
    }

    /** Free space a download still needs, or null when it is already covered. */
    fun missingSpace(availableBytes: Long?, requiredBytes: Long?, reserveBytes: Long): Long? {
        if (availableBytes == null) return null
        val reserve = reserveBytes.coerceAtLeast(0L)
        val required = requiredBytes ?: return null
        val needed = required.coerceAtLeast(0L) + reserve
        return (needed - availableBytes).takeIf { it > 0L }
    }
}
