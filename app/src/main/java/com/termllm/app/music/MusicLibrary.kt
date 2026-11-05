package com.termllm.app.music

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat

/**
 * The phone's own music, read straight from MediaStore.
 *
 * Apple Music's catalogue is an Apple-account service and stays out of scope; this
 * provider is the local library the device actually owns, which is what a native
 * Android port can offer honestly.
 */
data class MusicTrack(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val uri: String,
    val albumArtUri: String?,
) {
    /** Media id namespaced so the media session never confuses music with Listen items. */
    val mediaId: String get() = "music-" + id
}

class MusicLibrary(private val context: Context) {

    fun hasPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    /** Every track MediaStore knows about, newest-looking titles first is not assumed: keep its order. */
    fun tracks(limit: Int = 500): List<MusicTrack> {
        if (!hasPermission()) return emptyList()
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.ALBUM_ID,
        )
        val selection = MediaStore.Audio.Media.IS_MUSIC + " != 0"
        val order = MediaStore.Audio.Media.TITLE + " COLLATE NOCASE ASC"
        return context.contentResolver.query(collection, projection, selection, null, order)?.use { cursor ->
            collect(cursor, limit)
        } ?: emptyList()
    }

    companion object {
        /**
         * Map cursor rows to tracks. Column indices are resolved by name so a device
         * that omits one (album art, say) still yields the rest of the row.
         */
        fun collect(cursor: Cursor, limit: Int = 500): List<MusicTrack> {
            fun index(name: String) = cursor.getColumnIndex(name)
            val idIndex = index(MediaStore.Audio.Media._ID)
            val titleIndex = index(MediaStore.Audio.Media.TITLE)
            val artistIndex = index(MediaStore.Audio.Media.ARTIST)
            val albumIndex = index(MediaStore.Audio.Media.ALBUM)
            val durationIndex = index(MediaStore.Audio.Media.DURATION)
            val albumIdIndex = index(MediaStore.Audio.Media.ALBUM_ID)
            if (idIndex < 0 || titleIndex < 0) return emptyList()
            val out = ArrayList<MusicTrack>(minOf(limit, cursor.count))
            while (cursor.moveToNext() && out.size < limit) {
                val id = cursor.getLong(idIndex)
                val albumId = if (albumIdIndex >= 0) cursor.getLong(albumIdIndex) else -1L
                out.add(
                    MusicTrack(
                        id = id,
                        title = cursor.getString(titleIndex)?.takeIf { it.isNotBlank() } ?: "Untitled",
                        artist = if (artistIndex >= 0) cursor.getString(artistIndex).orEmpty() else "",
                        album = if (albumIndex >= 0) cursor.getString(albumIndex).orEmpty() else "",
                        durationMs = if (durationIndex >= 0) cursor.getLong(durationIndex) else 0L,
                        uri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id.toString()).toString(),
                        albumArtUri = if (albumId >= 0) {
                            android.content.ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId).toString()
                        } else {
                            null
                        },
                    )
                )
            }
            return out
        }

        /** Filters a library by a free-text query over title, artist and album. */
        fun filter(tracks: List<MusicTrack>, query: String): List<MusicTrack> {
            val needle = query.trim().lowercase()
            if (needle.isEmpty()) return tracks
            return tracks.filter {
                it.title.lowercase().contains(needle) ||
                    it.artist.lowercase().contains(needle) ||
                    it.album.lowercase().contains(needle)
            }
        }
    }
}
