package com.termllm.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.termllm.app.listen.ListenDownloader
import com.termllm.app.listen.ListenStore
import com.termllm.app.tools.MediaTools
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The `media_*` tool surface with the iOS argument/response shapes, exercised
 * against the real queue store on device.
 */
@RunWith(AndroidJUnit4::class)
class MediaToolsTest {

    private lateinit var dir: File
    private lateinit var store: ListenStore
    private lateinit var tools: MediaTools

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        dir = File(context.cacheDir, "media-tools-" + System.nanoTime()).apply { mkdirs() }
        store = ListenStore(dir)
        tools = MediaTools(store, ListenDownloader(context, store))
    }

    @Test
    fun mediaAddQueuesItemsWithTheIosArgumentShape() {
        val reply = JSONObject(
            tools.handle(
                "media_add",
                """{"items":[{"url":"https://example.com/episode.mp3","title":"Test episode","show":"Test show","placement":"end"}]}"""
            )
        )
        assertEquals(1, reply.getInt("count"))
        val added = reply.getJSONArray("added").getJSONObject(0)
        assertEquals("Test episode", added.getString("title"))
        assertEquals("end", added.getString("placement"))

        val list = JSONObject(tools.handle("media_list", """{"filter":"up_next","limit":10}"""))
        assertEquals(1, list.getInt("count"))
        val item = list.getJSONArray("items").getJSONObject(0)
        assertEquals("Test episode", item.getString("title"))
        assertEquals("Test show", item.getString("show"))
        assertTrue(!item.getBoolean("downloaded"))
    }

    @Test
    fun mediaAddRejectsNonHttpUrls() {
        val failed = runCatching { tools.handle("media_add", """{"items":[{"url":"file:///etc/passwd"}]}""") }
        assertTrue(failed.isFailure)
    }

    @Test
    fun nowPlayingReportsThePlayerStateShape() {
        val state = JSONObject(tools.handle("media_now_playing", "{}"))
        assertTrue(state.has("playing"))
        assertTrue(state.has("position_ms"))
        assertTrue(state.has("rate"))
        assertTrue(state.has("duration_ms"))
    }

    @Test
    fun mediaManageRemovesAnItem() {
        val added = JSONObject(
            tools.handle("media_add", """{"items":[{"url":"https://example.com/x.mp3","title":"X"}]}""")
        )
        val id = added.getJSONArray("added").getJSONObject(0).getString("id")
        val removed = JSONObject(tools.handle("media_manage", """{"action":"remove","items":["$id"]}"""))
        assertEquals("remove", removed.getString("action"))
        assertEquals(1, removed.getInt("affected"))
        assertEquals(0, JSONObject(tools.handle("media_list", "{}")).getInt("count"))
    }

    @Test
    fun mediaControlRejectsUnknownActions() {
        assertTrue(runCatching { tools.handle("media_control", """{"action":"explode"}""") }.isFailure)
    }

    @Test
    fun unknownToolFails() {
        assertTrue(runCatching { tools.handle("media_nope", "{}") }.isFailure)
    }
}
