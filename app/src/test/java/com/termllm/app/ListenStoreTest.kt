package com.termllm.app

import com.termllm.app.listen.ListenItem
import com.termllm.app.listen.ListenStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** Queue semantics: dedupe, ordering, positions, played/downloaded state. */
class ListenStoreTest {

    private lateinit var dir: File
    private lateinit var store: ListenStore

    @Before
    fun setUp() {
        dir = createTempDir(prefix = "listen-store")
        store = ListenStore(dir)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun item(url: String, title: String = url) = ListenItem(id = "id-" + url.hashCode(), url = url, title = title)

    @Test
    fun addKeepsOrderAndDedupesByUrl() {
        store.add(item("https://a/1.mp3"))
        store.add(item("https://a/2.mp3"))
        assertEquals(2, store.items().size)
        store.add(item("https://a/1.mp3"))
        assertEquals("re-adding a URL must not duplicate the queue", 2, store.items().size)
    }

    @Test
    fun reAddKeepsTheExistingPosition() {
        store.add(item("https://a/1.mp3"))
        val id = store.items().first().id
        store.updatePosition(id, 12_345, 60_000)
        store.add(item("https://a/1.mp3"))
        assertEquals(12_345L, store.items().first().positionMs)
    }

    @Test
    fun positionSurvivesReload() {
        store.add(item("https://a/1.mp3"))
        val id = store.items().first().id
        store.updatePosition(id, 7_000, 30_000)
        val reloaded = ListenStore(dir)
        assertEquals(7_000L, reloaded.item(id)!!.positionMs)
        assertEquals(30_000L, reloaded.item(id)!!.durationMs)
    }

    @Test
    fun nextAndPreviousWrapAround() {
        store.add(item("https://a/1.mp3"))
        store.add(item("https://a/2.mp3"))
        val first = store.items()[0].id
        val second = store.items()[1].id
        assertEquals(second, store.next(first)!!.id)
        assertEquals(first, store.next(second)!!.id)
        assertEquals(second, store.previous(first)!!.id)
    }

    @Test
    fun finishedItemIsMarkedPlayedAndRemoveClearsCurrent() {
        store.add(item("https://a/1.mp3"))
        val id = store.items().first().id
        store.setCurrent(id)
        store.updatePosition(id, 30_000, 30_000, played = true)
        assertTrue(store.item(id)!!.played)
        assertEquals(id, store.current()!!.id)
        store.remove(id)
        assertNull(store.current())
        assertEquals(0, store.items().size)
    }

    @Test
    fun downloadStateRecordsPathAndEtag() {
        store.add(item("https://a/1.mp3"))
        val id = store.items().first().id
        store.markDownloaded(id, "/tmp/file.audio", "\"etag-1\"")
        val stored = store.item(id)!!
        assertTrue(stored.downloaded)
        assertEquals("/tmp/file.audio", stored.localPath)
        assertEquals("\"etag-1\"", stored.etag)
    }

    @Test
    fun clearPlayedKeepsUnplayedItems() {
        store.add(item("https://a/1.mp3"))
        store.add(item("https://a/2.mp3"))
        val id = store.items().first().id
        store.updatePosition(id, 10, 10, played = true)
        store.clearPlayed()
        assertEquals(1, store.items().size)
        assertTrue(!store.items().first().played)
    }
}
