package com.termllm.app

import com.termllm.app.listen.DownloadConstraints
import com.termllm.app.listen.DownloadPolicy
import com.termllm.app.listen.DownloadPreferences
import com.termllm.app.listen.DownloadStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Retry, space and completion rules for durable downloads. These are the rules
 * that decide whether an episode becomes playable, is retried, or gives up — so
 * they are pure functions with direct tests.
 */
class DownloadPolicyTest {

    @Test
    fun backoffGrowsExponentiallyAndCaps() {
        assertEquals(DownloadPolicy.MIN_BACKOFF_SECONDS, DownloadPolicy.backoffSeconds(1))
        assertEquals(20L, DownloadPolicy.backoffSeconds(2))
        assertEquals(40L, DownloadPolicy.backoffSeconds(3))
        assertEquals(1800L, DownloadPolicy.backoffSeconds(9))
        assertEquals("a long history must not overflow", 1800L, DownloadPolicy.backoffSeconds(50))
        assertEquals("attempt zero is still the first attempt", DownloadPolicy.MIN_BACKOFF_SECONDS, DownloadPolicy.backoffSeconds(0))
    }

    @Test
    fun serverRetryAfterNeverShortensTheLocalBackoff() {
        assertEquals(600L, DownloadPolicy.retryDelaySeconds(1, 600L))
        assertEquals(10L, DownloadPolicy.retryDelaySeconds(1, 1L))
        assertEquals(10L, DownloadPolicy.retryDelaySeconds(1, null))
    }

    @Test
    fun attemptsAreCapped() {
        assertTrue(DownloadPolicy.canRetry(1, true))
        assertTrue(DownloadPolicy.canRetry(DownloadPolicy.MAX_ATTEMPTS - 1, true))
        assertFalse(DownloadPolicy.canRetry(DownloadPolicy.MAX_ATTEMPTS, true))
        assertFalse("a non-retryable outcome never retries", DownloadPolicy.canRetry(1, false))
    }

    @Test
    fun completionRequiresAnExactKnownLength() {
        assertTrue(DownloadPolicy.canMarkComplete(2048, 2048))
        assertFalse("a longer file is not complete", DownloadPolicy.canMarkComplete(2049, 2048))
        assertFalse("a shorter file is not complete", DownloadPolicy.canMarkComplete(1024, 2048))
        assertFalse("an unknown length can never be complete", DownloadPolicy.canMarkComplete(1024, null))
        assertFalse("a zero-length audio file is not playable", DownloadPolicy.canMarkComplete(0, 0))
    }

    @Test
    fun spaceRulesKeepTheReserve() {
        val reserve = 100L
        assertTrue(DownloadPolicy.hasSpace(1000, 500, reserve))
        assertTrue(DownloadPolicy.hasSpace(1000, null, reserve))
        assertFalse("the reserve must survive the download", DownloadPolicy.hasSpace(500, 401, reserve))
        assertTrue(DownloadPolicy.hasSpace(500, 400, reserve))
        assertFalse("a nearly full device refuses new work", DownloadPolicy.hasSpace(50, null, reserve))
        assertTrue("an unknown free-space figure does not block", DownloadPolicy.hasSpace(null, 500, reserve))
        assertEquals(1L, DownloadPolicy.missingSpace(500, 401, reserve))
        assertNull(DownloadPolicy.missingSpace(1000, 100, reserve))
    }

    @Test
    fun meteredPreferenceBecomesAWorkConstraint() {
        val metered = DownloadPolicy.constraintsFor(DownloadPreferences(allowMetered = true))
        assertEquals(DownloadConstraints(networkRequired = true, unmetered = false), metered)
        val unmeteredOnly = DownloadPolicy.constraintsFor(DownloadPreferences(allowMetered = false))
        assertEquals(DownloadConstraints(networkRequired = true, unmetered = true), unmeteredOnly)
    }

    @Test
    fun fileNamesAreStableAndPathSafe() {
        assertEquals("listen-123.audio", DownloadPolicy.fileNameFor("listen-123"))
        assertEquals("a_b.audio", DownloadPolicy.fileNameFor("a/b"))
        assertEquals(".._.._x.audio", DownloadPolicy.fileNameFor("../../x"))
        assertFalse(DownloadPolicy.fileNameFor("a/b").contains('/'))
    }

    @Test
    fun aBudgetPauseAlwaysReschedules() {
        assertEquals(DownloadStep.RETRY, DownloadPolicy.step(paused = true, failures = 0))
        assertEquals(
            "a long episode may be paused any number of times",
            DownloadStep.RETRY,
            DownloadPolicy.step(paused = true, failures = DownloadPolicy.MAX_ATTEMPTS * 3),
        )
        assertEquals(DownloadStep.RETRY, DownloadPolicy.step(paused = false, failures = 0))
        assertEquals(DownloadStep.GIVE_UP, DownloadPolicy.step(paused = false, failures = DownloadPolicy.MAX_ATTEMPTS))
    }

    @Test
    fun theAttemptBudgetFitInsideTheWorkManagerJobLimit() {
        assertTrue("a bounded attempt is what replaces an unbounded foreground service",
            DownloadPolicy.ATTEMPT_BUDGET_MILLIS in 1..(9L * 60L * 1000L))
    }

    @Test
    fun defaultsAreBounded() {
        val defaults = DownloadPreferences()
        assertTrue("a single episode is capped", defaults.maxBytes > 0L && defaults.maxBytes <= 4L * 1024 * 1024 * 1024)
        assertTrue("a reserve is kept free", defaults.reserveBytes > 0L)
        assertTrue("metered downloads work by default", defaults.allowMetered)
    }
}
