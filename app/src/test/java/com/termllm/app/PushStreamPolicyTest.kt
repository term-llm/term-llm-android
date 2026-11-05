package com.termllm.app

import com.termllm.app.push.PushStreamPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Relay stream lifecycle policy (hardening row 4): a deterministic table for what is
 * retried and what is permanent, plus bounded jittered backoff. The service applies
 * exactly this table, so an emulator test can assert the same outcomes without
 * waiting for real timeouts.
 */
class PushStreamPolicyTest {

    @Test
    fun credentialAndSubscriptionFailuresArePermanent() {
        listOf(400, 401, 403, 404, 405, 410, 422).forEach { code ->
            val verdict = PushStreamPolicy.classify(code)
            assertTrue("HTTP $code must not be retried forever", PushStreamPolicy.needsReenroll(verdict))
            val permanent = verdict as PushStreamPolicy.Verdict.Permanent
            assertNotNull("a permanent verdict must say why", permanent.reason)
            assertTrue(permanent.reason.isNotBlank())
        }
    }

    @Test
    fun transportFailuresAreRetried() {
        listOf<Int?>(null, 200, 204, 301, 408, 425, 429, 500, 502, 503).forEach { code ->
            assertFalse("HTTP $code must be retried", PushStreamPolicy.needsReenroll(PushStreamPolicy.classify(code)))
        }
    }

    @Test
    fun backoffGrowsToTheCapAndIsDeterministicPerSeed() {
        val first = PushStreamPolicy.backoffMs(3, Random(42))
        val second = PushStreamPolicy.backoffMs(3, Random(42))
        assertEquals("the policy is deterministic for a seeded generator", first, second)

        for (attempt in 1..6) {
            val ceiling = (PushStreamPolicy.BASE_BACKOFF_MS shl (attempt - 1)).coerceAtMost(PushStreamPolicy.MAX_BACKOFF_MS)
            val random = Random(attempt.toLong())
            repeat(20) {
                val delay = PushStreamPolicy.backoffMs(attempt, random)
                assertTrue("attempt $attempt delay $delay must be >= half the window", delay >= ceiling / 2)
                assertTrue("attempt $attempt delay $delay must stay in its window", delay <= ceiling)
            }
        }
        repeat(20) {
            val capped = PushStreamPolicy.backoffMs(20, Random(it.toLong()))
            assertTrue("the cap holds: $capped", capped in (PushStreamPolicy.MAX_BACKOFF_MS / 2)..PushStreamPolicy.MAX_BACKOFF_MS)
        }
    }

    @Test
    fun theReadStallTimeoutIsBounded() {
        // Infinite reads were the audit finding; a keepalive watchdog must be finite
        // and longer than a normal keepalive interval.
        assertTrue(PushStreamPolicy.STALL_TIMEOUT_MS in 30_000..300_000)
    }

    @Test
    fun endpointComparisonDrivesTheSwitchDecision() {
        assertTrue(PushStreamPolicy.sameEndpoint("https://relay.example/v1/stream/a", "https://relay.example/v1/stream/a/"))
        assertFalse(PushStreamPolicy.sameEndpoint("https://relay.example/v1/stream/a", "https://relay.example/v1/stream/b"))
        assertFalse(PushStreamPolicy.sameEndpoint(null, "https://relay.example/v1/stream/a"))
        assertFalse(PushStreamPolicy.sameEndpoint("https://relay.example/v1/stream/a", null))
    }
}
