package com.termllm.app.music

import java.io.File

/**
 * Playlists as plain `.m3u8` files in the app's own storage, ported from the iOS
 * `PlaylistFile` contract: `#EXTM3U`, then `#EXTINF:<seconds>,<Artist> - <Title>`
 * followed by the entry (a URL or a local path). Anything unrecognised is skipped
 * rather than failing the whole import, so a hand-edited file still works.
 */
data class PlaylistEntry(
    val location: String,
    val title: String,
    val artist: String = "",
    val durationMs: Long = 0L,
)

data class Playlist(
    val id: String,
    val name: String,
    val entries: List<PlaylistEntry> = emptyList(),
)

class PlaylistStore(private val dir: File) {

    fun ensureDir() {
        if (!dir.exists()) dir.mkdirs()
    }

    fun list(): List<Playlist> {
        ensureDir()
        return dir.listFiles { file -> file.isFile && file.name.endsWith(".m3u8") }
            ?.sortedBy { it.name.lowercase() }
            ?.mapNotNull { file -> runCatching { parse(file.name.removeSuffix(".m3u8"), file.readText()) }.getOrNull() }
            ?: emptyList()
    }

    /** Import from text; returns the playlist and saves it under [name]. */
    fun import(name: String, text: String): Playlist {
        ensureDir()
        val playlist = parse(name, text)
        atomicWrite(File(dir, fileName(name)), export(playlist))
        return playlist
    }

    fun save(playlist: Playlist) {
        ensureDir()
        atomicWrite(File(dir, fileName(playlist.name)), export(playlist))
    }

    fun remove(id: String) {
        ensureDir()
        dir.listFiles { file -> file.name.removeSuffix(".m3u8") == id }?.forEach { it.delete() }
    }

    private fun atomicWrite(target: File, text: String) {
        val temp = File.createTempFile("playlist-", ".tmp", dir)
        try {
            java.io.FileOutputStream(temp).use { it.write(text.toByteArray()); it.fd.sync() }
            java.nio.file.Files.move(temp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
    }
    private fun fileName(name: String) =
        name.lowercase().replace(Regex("[^a-z0-9_-]+"), "-").trim('-').ifBlank { "playlist" } + ".m3u8"

    companion object {
        fun parse(name: String, text: String): Playlist {
            val entries = mutableListOf<PlaylistEntry>()
            var pendingTitle = ""
            var pendingArtist = ""
            var pendingDuration = 0L
            var declaredName = ""
            text.lineSequence().forEach { raw ->
                val line = raw.trim()
                when {
                    line.isEmpty() -> Unit
                    line.startsWith("#EXTM3U") -> Unit
                    // Display names survive the file (the file name is a slug).
                    line.startsWith("#PLAYLIST:") -> declaredName = line.removePrefix("#PLAYLIST:").trim()
                    line.startsWith("#EXTINF:") -> {
                        val body = line.removePrefix("#EXTINF:")
                        val durationPart = body.substringBefore(',', "")
                        val titlePart = body.substringAfter(',', "")
                        pendingDuration = durationPart.trim().toDoubleOrNull()?.times(1000)?.toLong() ?: 0L
                        val split = titlePart.split(" - ", limit = 2)
                        if (split.size == 2) {
                            pendingArtist = split[0].trim()
                            pendingTitle = split[1].trim()
                        } else {
                            pendingArtist = ""
                            pendingTitle = titlePart.trim()
                        }
                    }
                    line.startsWith("#") -> Unit
                    else -> {
                        val fallback = line.substringAfterLast('/').substringBefore('?')
                        entries.add(
                            PlaylistEntry(
                                location = line,
                                title = pendingTitle.ifBlank { fallback },
                                artist = pendingArtist,
                                durationMs = pendingDuration,
                            )
                        )
                        pendingTitle = ""
                        pendingArtist = ""
                        pendingDuration = 0L
                    }
                }
            }
            return Playlist(
                id = name.lowercase().replace(Regex("[^a-z0-9_-]+"), "-").trim('-').ifBlank { "playlist" },
                name = declaredName.ifBlank { name },
                entries = entries,
            )
        }

        fun export(playlist: Playlist): String = buildString {
            appendLine("#EXTM3U")
            appendLine("#PLAYLIST:" + playlist.name)
            playlist.entries.forEach { entry ->
                val seconds = (entry.durationMs / 1000).coerceAtLeast(0)
                val label = buildString {
                    if (entry.artist.isNotBlank()) append(entry.artist).append(" - ")
                    append(entry.title)
                }
                appendLine("#EXTINF:" + seconds + "," + label)
                appendLine(entry.location)
            }
        }
    }
}
