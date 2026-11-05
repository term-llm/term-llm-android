package com.termllm.app

import com.termllm.app.chat.LiveHangUp
import com.termllm.app.chat.MediaCapturePolicy
import com.termllm.app.chat.MediaCapturePolicy.Decision
import com.termllm.app.data.PairingLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure policies ported from iOS: capture permission, pairing links, live hang-up timing. */
class VoiceAndLinksTest {

    private val mic = listOf(MediaCapturePolicy.MICROPHONE)
    private val cam = listOf(MediaCapturePolicy.CAMERA)

    @Test
    fun microphoneOnTheSourceOriginIsGrantedEverythingElsePromptsOrDenies() {
        assertEquals(Decision.GRANT, MediaCapturePolicy.decide(true, "https://hub.example.com", "https://hub.example.com/work/", mic))
        // default ports normalise
        assertEquals(Decision.GRANT, MediaCapturePolicy.decide(true, "https://hub.example.com:443", "https://hub.example.com", mic))
        assertEquals(Decision.PROMPT, MediaCapturePolicy.decide(true, "https://hub.example.com", "https://hub.example.com", cam))
        assertEquals(Decision.PROMPT, MediaCapturePolicy.decide(true, "https://hub.example.com", "https://hub.example.com", mic + cam))
        assertEquals(Decision.PROMPT, MediaCapturePolicy.decide(true, "https://other.example.com", "https://hub.example.com", mic))
        assertEquals(Decision.DENY, MediaCapturePolicy.decide(false, "https://hub.example.com", "https://hub.example.com", mic))
        assertEquals(Decision.DENY, MediaCapturePolicy.decide(true, "https://hub.example.com", "https://hub.example.com", listOf("android.webkit.resource.PROTECTED_MEDIA_ID")))
        assertEquals(Decision.DENY, MediaCapturePolicy.decide(true, "https://hub.example.com", "https://hub.example.com", emptyList()))
        assertEquals(listOf("android.permission.RECORD_AUDIO", "android.permission.CAMERA"), MediaCapturePolicy.androidPermissions(mic + cam))
    }

    @Test
    fun pairingLinksCarryExactlyOneHttpsServerAndNothingLocal() {
        assertEquals("https://hub.example.com/work/", PairingLink.server("termllm://connect?server=https%3A%2F%2Fhub.example.com%2Fwork%2F").toString())
        assertEquals("https://hub.example.com/", PairingLink.server("termllm://connect?server=hub.example.com").toString())
        assertNull(PairingLink.server("termllm://connect?server=http://hub.example.com/"))
        assertNull(PairingLink.server("termllm://connect?server=https://localhost/"))
        assertNull(PairingLink.server("termllm://connect?server=https://dev.localhost/"))
        assertNull(PairingLink.server("termllm://connect?server=https://127.0.0.1/"))
        assertNull(PairingLink.server("termllm://connect?server=https://hub.example.com/&token=abc"))
        assertNull(PairingLink.server("termllm://connect?server=https://hub.example.com/#frag"))
        assertNull(PairingLink.server("termllm://connect/extra?server=https://hub.example.com/"))
        assertNull(PairingLink.server("termllm://user@connect?server=https://hub.example.com/"))
        assertNull(PairingLink.server("termllm://voice"))
        assertTrue(PairingLink.isVoiceLaunch("termllm://voice"))
        assertFalse(PairingLink.isVoiceLaunch("termllm://connect?server=https://hub.example.com/"))
    }

    private class FakeScheduler {
        val pending = mutableListOf<Pair<Long, () -> Unit>>()
        fun schedule(delay: Long, action: () -> Unit): LiveHangUp.Coordinator.Cancellable {
            val entry = delay to action
            pending.add(entry)
            return object : LiveHangUp.Coordinator.Cancellable {
                override fun cancel() { pending.remove(entry) }
            }
        }
        fun fire() { val copy = pending.toList(); pending.clear(); copy.forEach { it.second() } }
    }

    @Test
    fun userPlaybackHangsUpAtOnceToolPlaybackWaitsForTheReplyThenFlushes() {
        val scripts = mutableListOf<String>()
        val scheduler = FakeScheduler()
        val coordinator = LiveHangUp.Coordinator(run = { scripts.add(it) }, schedule = scheduler::schedule)

        coordinator.onPlaybackStarting(fromTool = false)
        assertEquals(listOf(LiveHangUp.requestScript(false)), scripts)
        scripts.clear()

        coordinator.onToolReplied() // nothing armed: no-op
        assertTrue(scripts.isEmpty())

        coordinator.onPlaybackStarting(fromTool = true)
        assertTrue(scripts.isEmpty()) // the call must survive until the tool result is uploaded
        coordinator.onToolReplied()
        assertEquals(listOf(LiveHangUp.requestScript(true)), scripts)
        assertEquals(1, scheduler.pending.size)
        assertEquals(LiveHangUp.TOOL_RESULT_GRACE_MS, scheduler.pending[0].first)
        scripts.clear()
        scheduler.fire()
        assertEquals(listOf(LiveHangUp.FLUSH_SCRIPT), scripts)
        scripts.clear()

        // A newer tool start cancels an armed grace without discarding its own result.
        coordinator.onPlaybackStarting(fromTool = true)
        coordinator.onToolReplied()
        scripts.clear()
        coordinator.onPlaybackStarting(fromTool = true)
        assertEquals(listOf(LiveHangUp.CANCEL_SCRIPT), scripts)
        assertTrue(scheduler.pending.isEmpty())
        scripts.clear()
        coordinator.reset()
        assertTrue(scripts.isEmpty())
        assertTrue(LiveHangUp.userScript.contains("__termLLMLiveHangUp"))
        assertTrue(LiveHangUp.userScript.contains("tool_calls"))
    }
}
