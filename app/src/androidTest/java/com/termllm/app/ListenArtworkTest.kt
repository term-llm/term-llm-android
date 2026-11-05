package com.termllm.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.termllm.app.listen.ListenArtwork
import com.termllm.app.listen.ListenItem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Listen artwork cache: keys are owner-scoped, saved files are reused, a failed
 * fetch backs off, pruning drops images nothing uses, and a saved PNG decodes.
 */
@RunWith(AndroidJUnit4::class)
class ListenArtworkTest {

    private lateinit var dir: File
    private lateinit var artwork: ListenArtwork

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "artwork-test-" + System.nanoTime())
        artwork = ListenArtwork(dir = dir)
        artwork.ensureDir()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun item(artworkUrl: String?, sourceId: String? = "src-a") = ListenItem(
        id = "item-" + (artworkUrl ?: "none"),
        url = "https://example.com/a.mp3",
        title = "Episode",
        artworkUrl = artworkUrl,
        sourceId = sourceId,
    )

    @Test
    fun fileNamesAreStableAndOwnerScoped() {
        val one = artwork.fileName("https://example.com/a.jpg", "src-a")
        assertEquals(one, artwork.fileName("https://example.com/a.jpg", "src-a"))
        assertNotEquals(one, artwork.fileName("https://example.com/a.jpg", "src-b"))
        assertNotEquals(one, artwork.fileName("https://example.com/b.jpg", "src-a"))
        assertTrue(one.endsWith(".img"))
    }

    @Test
    fun savedFileIsReusedWithoutFetching() {
        val wanted = item("https://example.com/a.jpg")
        val target = File(dir, artwork.fileName(wanted.artworkUrl!!, wanted.sourceId))
        target.writeBytes(PNG)
        assertNotNull(artwork.file(wanted))
        // fetch() returns the existing file rather than touching the network
        assertNotNull(artwork.fetch(wanted))
    }

    @Test
    fun savedPngDecodesToABitmap() {
        val wanted = item("https://example.com/a.jpg")
        File(dir, artwork.fileName(wanted.artworkUrl!!, wanted.sourceId)).writeBytes(PNG)
        val bitmap = artwork.bitmap(wanted)
        assertNotNull(bitmap)
        assertTrue(bitmap!!.width > 0 && bitmap.height > 0)
    }

    @Test
    fun itemsWithoutArtworkHaveNoFile() {
        assertNull(artwork.file(item(null)))
        assertNull(artwork.bitmap(item(null)))
    }

    @Test
    fun pruneRemovesImagesNothingUses() {
        val kept = item("https://example.com/kept.jpg")
        val dropped = item("https://example.com/dropped.jpg")
        File(dir, artwork.fileName(kept.artworkUrl!!, kept.sourceId)).writeBytes(PNG)
        File(dir, artwork.fileName(dropped.artworkUrl!!, dropped.sourceId)).writeBytes(PNG)
        artwork.prune(listOf(kept))
        assertNotNull(artwork.file(kept))
        assertNull(artwork.file(dropped))
    }

    @Test
    fun failedFetchBacksOff() {
        var attempts = 0
        val unreachable = ListenArtwork(
            dir = dir,
            decorate = { builder, _, _ -> attempts += 1; builder },
            now = { 1_000_000L },
        )
        val wanted = item("http://127.0.0.1:1/missing.png")
        assertNull(unreachable.fetch(wanted))
        assertEquals(1, attempts)
        // second attempt inside the retry window does not touch the network again
        assertNull(unreachable.fetch(wanted))
        assertEquals(1, attempts)
        assertFalse(File(dir, unreachable.fileName(wanted.artworkUrl!!, wanted.sourceId)).exists())
    }

    companion object {
        /** 1×1 transparent PNG. */
        private val PNG = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4.toByte(), 0x89.toByte(),
            0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41, 0x54,
            0x78, 0x9C.toByte(), 0x63, 0x00, 0x01, 0x00, 0x00, 0x05, 0x00, 0x01,
            0x0D, 0x0A, 0x2D, 0xB4.toByte(),
            0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
        )
    }
}
