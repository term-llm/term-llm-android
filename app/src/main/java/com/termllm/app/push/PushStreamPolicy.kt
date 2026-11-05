package com.termllm.app.push

import kotlin.random.Random

/**
 * Reconnect rules for the relay's device stream, kept pure so the two failure modes
 * that matter can be tested on the JVM:
 *
 * * a transport failure (socket closed, timeout, 5xx, 429) is retried with bounded
 *   exponential backoff and jitter, so a relay outage does not become a reconnect
 *   storm from every phone at the same second;
 * * a credential/subscription failure (401, 403, 404, 410, other 4xx) is permanent.
 *   Retrying it forever would hold a foreground service open, drain the battery and
 *   hide the one thing the user can fix: re-enrolling the route.
 */
object PushStreamPolicy {

    /**
     * The relay sends a keepalive comment every few seconds. A read that stalls for
     * this long means the socket is dead even though it never reported an error, so
     * the stream is cancelled and reconnected instead of being held open (and the
     * foreground service with it) forever.
     */
    const val STALL_TIMEOUT_MS: Long = 90_000L

    const val BASE_BACKOFF_MS: Long = 1_000L
    const val MAX_BACKOFF_MS: Long = 60_000L

    /** What to do after a stream attempt ended. */
    sealed class Verdict {
        /** Try again after a jittered backoff. */
        object Retry : Verdict()

        /** Stop: only a new enrollment can fix this. */
        data class Permanent(val reason: String) : Verdict()
    }

    fun classify(httpCode: Int?): Verdict = when {
        // No HTTP status at all: a socket timeout, reset or DNS failure.
        httpCode == null -> Verdict.Retry
        // A successful response that ended is just an idle connection: reconnect.
        httpCode in 200..299 -> Verdict.Retry
        httpCode == 401 || httpCode == 403 ->
            Verdict.Permanent("the relay rejected this device's credentials")
        httpCode == 404 || httpCode == 410 ->
            Verdict.Permanent("this subscription no longer exists at the relay")
        httpCode == 408 || httpCode == 425 || httpCode == 429 -> Verdict.Retry
        httpCode in 500..599 -> Verdict.Retry
        httpCode in 400..499 ->
            Verdict.Permanent("the relay refused the stream request (HTTP $httpCode)")
        else -> Verdict.Retry
    }

    fun needsReenroll(verdict: Verdict): Boolean = verdict is Verdict.Permanent

    /**
     * Delay before attempt number [attempt] (1 = first retry). Exponential up to
     * [MAX_BACKOFF_MS], then jittered across the top half of that window so clients
     * spread out without ever retrying instantly.
     */
    fun backoffMs(attempt: Int, random: Random = Random.Default): Long {
        val exponent = attempt.coerceIn(1, 16) - 1
        val ceiling = (BASE_BACKOFF_MS shl exponent).coerceAtMost(MAX_BACKOFF_MS)
        val floor = ceiling / 2
        if (ceiling <= floor) return ceiling
        return random.nextLong(floor, ceiling + 1)
    }

    /** Endpoint comparison for "switch the stream to this route's new endpoint". */
    fun sameEndpoint(current: String?, next: String?): Boolean {
        if (current.isNullOrBlank() || next.isNullOrBlank()) return false
        return current.trimEnd('/') == next.trimEnd('/')
    }
}
