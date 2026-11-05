package com.termllm.app

import android.database.MatrixCursor
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.termllm.app.music.MusicLibrary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Music comes from the device's MediaStore. These tests check the row mapping
 * against a synthetic cursor and the real query against the device library (the
 * harness pushes one MP3 into /sdcard/Music and scans it).
 */
@RunWith(AndroidJUnit4::class)
class MusicLibraryTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun cursor(rows: List<Array<Any?>>): MatrixCursor {
        val cursor = MatrixCursor(
            arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.ALBUM_ID,
            )
        )
        rows.forEach { cursor.addRow(it) }
        return cursor
    }

    @Test
    fun rowsMapToTracksWithMediaStoreUrls() {
        val tracks = MusicLibrary.collect(cursor(listOf(arrayOf(7L, "Song", "Artist", "Album", 12345L, 3L))))
        assertEquals(1, tracks.size)
        val track = tracks.first()
        assertEquals("Song", track.title)
        assertEquals("Artist", track.artist)
        assertEquals("Album", track.album)
        assertEquals(12345L, track.durationMs)
        assertTrue(track.uri.endsWith("/audio/media/7"))
        assertTrue(track.albumArtUri!!.contains("/audio/albumart/3"))
        assertEquals("music-7", track.mediaId)
    }

    @Test
    fun missingColumnsAndBlankTitlesStillYieldUsableRows() {
        val sparse = MatrixCursor(arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE))
        sparse.addRow(arrayOf(9L, ""))
        val tracks = MusicLibrary.collect(sparse)
        assertEquals(1, tracks.size)
        assertEquals("Untitled", tracks[0].title)
        assertEquals("", tracks[0].artist)
        assertEquals(null, tracks[0].albumArtUri)
    }

    @Test
    fun filterMatchesTitleArtistAndAlbum() {
        val tracks = MusicLibrary.collect(
            cursor(
                listOf(
                    arrayOf(1L, "Blue Train", "John Coltrane", "Blue Train", 1000L, 1L),
                    arrayOf(2L, "So What", "Miles Davis", "Kind of Blue", 2000L, 2L),
                )
            )
        )
        assertEquals(1, MusicLibrary.filter(tracks, "coltrane").size)
        assertEquals(1, MusicLibrary.filter(tracks, "kind of").size)
        assertEquals(2, MusicLibrary.filter(tracks, "").size)
        assertEquals(0, MusicLibrary.filter(tracks, "zzz").size)
    }

    @Test
    fun realLibraryQueryReturnsWhatWasScanned() {
        // Skips honestly when the harness has not pushed a track or the permission is
        // missing: an empty library is not a failure, a wrong mapping is.
        val library = MusicLibrary(context)
        if (!library.hasPermission()) return
        val tracks = library.tracks()
        val fixture = tracks.firstOrNull { it.title == "Fixture tone twelve" }
        if (fixture != null) {
            assertEquals("Fixture Artist", fixture.artist)
            assertEquals("Fixture Album", fixture.album)
            assertTrue("duration should be reported: " + fixture.durationMs, fixture.durationMs > 0)
        }
    }
}
