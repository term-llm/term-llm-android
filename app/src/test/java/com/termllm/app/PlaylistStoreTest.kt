package com.termllm.app

import com.termllm.app.music.Playlist
import com.termllm.app.music.PlaylistEntry
import com.termllm.app.music.PlaylistStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Playlists are `.m3u8` files on the phone. The parser follows the iOS
 * `PlaylistFile` contract: `#EXTINF:<seconds>,<Artist> - <Title>` then the entry,
 * unknown lines skipped, and a round trip through export keeps the same entries.
 */
class PlaylistStoreTest {

    private lateinit var dir: File
    private lateinit var store: PlaylistStore

    @Before
    fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "playlists-" + System.nanoTime())
        store = PlaylistStore(dir)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun parsesExtinfMetadataAndEntries() {
        val playlist = PlaylistStore.parse(
            "Road trip",
            """
            #EXTM3U
            #EXTINF:213,The Band - The Weight
            https://example.com/a.mp3
            # a comment
            #EXTINF:180,Miles Davis - So What
            content://media/external/audio/media/42
            """.trimIndent(),
        )
        assertEquals(2, playlist.entries.size)
        assertEquals("The Weight", playlist.entries[0].title)
        assertEquals("The Band", playlist.entries[0].artist)
        assertEquals(213_000L, playlist.entries[0].durationMs)
        assertEquals("So What", playlist.entries[1].title)
        assertTrue(playlist.entries[1].location.startsWith("content://"))
    }

    @Test
    fun entryWithoutTitleFallsBackToTheFileName() {
        val playlist = PlaylistStore.parse("Bare", "#EXTM3U\nhttps://example.com/dir/track-nine.mp3?token=1\n")
        assertEquals(1, playlist.entries.size)
        assertEquals("track-nine.mp3", playlist.entries.first().title)
        assertEquals(0L, playlist.entries.first().durationMs)
    }

    @Test
    fun exportRoundTripsThroughTheStore() {
        val original = Playlist(
            id = "mix",
            name = "Mix",
            entries = listOf(
                PlaylistEntry("https://example.com/a.mp3", "A", "Artist", 65_000L),
                PlaylistEntry("https://example.com/b.mp3", "B"),
            ),
        )
        store.save(original)
        val loaded = store.list().single()
        assertEquals("Mix", loaded.name)
        assertEquals(original.entries.map { it.title }, loaded.entries.map { it.title })
        assertEquals(65_000L, loaded.entries[0].durationMs)
    }

    @Test
    fun importWritesTheFileAndRemoveDeletesIt() {
        val playlist = store.import("Imported list", "#EXTM3U\n#EXTINF:2,X - Y\nhttps://example.com/x.mp3\n")
        assertEquals(1, playlist.entries.size)
        assertTrue(File(dir, "imported-list.m3u8").exists())
        store.remove(playlist.id)
        assertTrue(store.list().isEmpty())
    }
}
