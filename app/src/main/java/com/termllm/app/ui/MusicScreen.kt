package com.termllm.app.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.termllm.app.listen.PlayerHub
import com.termllm.app.music.MusicLibrary
import com.termllm.app.music.MusicTrack
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The device's own music library (MediaStore), played through the same media
 * session as Listen so the mini player, lock-screen controls and offline rules are
 * shared. Apple Music catalogue search remains an Apple-account service and is not
 * offered here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicScreen(library: MusicLibrary, playlists: com.termllm.app.music.PlaylistStore? = null) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var granted by remember { mutableStateOf(library.hasPermission()) }
    var tracks by remember { mutableStateOf<List<MusicTrack>>(emptyList()) }
    var query by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var playlistList by remember { mutableStateOf<List<com.termllm.app.music.Playlist>>(emptyList()) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(playlists) { playlistList = withContext(Dispatchers.IO) { playlists?.list().orEmpty() } }
    var playlistMessage by remember { mutableStateOf<String?>(null) }
    val importPlaylist = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && playlists != null) scope.launch {
            playlistMessage = "Importing…"
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val displayName = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    }
                    val text = context.contentResolver.openInputStream(uri)?.use { com.termllm.app.music.PlaylistImport.read(it) }
                        ?: error("Could not read that file")
                    val name = displayName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() } ?: "Imported"
                    val playlist = playlists.import(name, text)
                    playlist to playlists.list()
                }
            }
            result.fold(onSuccess = { (playlist, all) -> playlistList = all; playlistMessage = "Imported " + playlist.name + " · " + playlist.entries.size + " entries" },
                onFailure = { playlistMessage = it.message ?: "Import failed" })
        }
    }

    var loading by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }

    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        reload += 1
    }

    LaunchedEffect(granted, reload) {
        if (!granted) {
            tracks = emptyList()
            return@LaunchedEffect
        }
        loading = true
        val result = withContext(Dispatchers.IO) { runCatching { library.tracks() } }
        tracks = result.getOrDefault(emptyList())
        if (result.isFailure) playlistMessage = "Library unavailable. Check media permission and refresh."
        granted = library.hasPermission()
        loading = false
    }

    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        granted = library.hasPermission(); reload += 1
    }
    val visible = MusicLibrary.filter(tracks, query)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Music") },
                actions = {
                    IconButton(onClick = { reload += 1 }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh library")
                    }
                },
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (playlists != null) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Playlists · " + playlistList.size, style = MaterialTheme.typography.titleSmall)
                        TextButton(onClick = {
                            // The iOS app imports `.m3u8` files; the picker keeps the file
                            // scope to the user's choice, no broad storage permission.
                            runCatching { importPlaylist.launch(arrayOf("audio/x-mpegurl", "application/vnd.apple.mpegurl", "text/plain", "*/*")) }
                        }) { Text("Import .m3u8") }
                    }
                }
                playlistMessage?.let { text ->
                    item {
                        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (playlistList.isEmpty()) {
                    item {
                        Text(
                            "No playlists yet. Import a .m3u8 file to keep one on this phone.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(playlistList, key = { "pl-" + it.id }) { playlist ->
                    Surface(
                        onClick = {
                            val first = playlist.entries.firstOrNull()
                            if (first != null) {
                                playlistMessage = "Playing " + first.title + " from " + playlist.name
                                PlayerHub.playPlaylist(playlist)
                            }
                        },
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(playlist.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text(
                                playlist.entries.size.toString() + " entries" +
                                    if (playlist.entries.isNotEmpty()) " · first: " + playlist.entries.first().title else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                item { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }
            }
            if (!granted) {
                item {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Play the music on this phone", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "The library is read from this device's MediaStore. Nothing is uploaded, and Apple Music's " +
                                    "catalogue needs an Apple account, so it is not part of this screen.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = { request.launch(permission) }) { Text("Allow media access") }
                        }
                    }
                }
            } else {
                item {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Search this library") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Text(
                        (if (loading) "Reading library…" else visible.size.toString() + " tracks" +
                            (if (query.isNotBlank()) " matching" else "")),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!loading && visible.isEmpty()) {
                    item {
                        EmptyState(
                            title = "No music found",
                            body = "MediaStore reports no music on this device yet. Copy audio to the phone, let it be " +
                                "scanned, then refresh.",
                        )
                    }
                }
                items(visible, key = { it.id }) { track ->
                    Surface(
                        onClick = {
                            PlayerHub.playUri(track.mediaId, track.uri, track.title, track.artist, track.albumArtUri)
                        },
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "Play " + track.title)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(track.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 2)
                                Text(
                                    buildString {
                                        if (track.artist.isNotBlank()) append(track.artist)
                                        if (track.album.isNotBlank()) {
                                            if (isNotEmpty()) append(" · ")
                                            append(track.album)
                                        }
                                        if (track.durationMs > 0) {
                                            if (isNotEmpty()) append(" · ")
                                            append(formatClock(track.durationMs))
                                        }
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

/** Filesystem paths in a playlist become `file://` URIs; URLs and content URIs stay as they are. */
private fun playlistUri(location: String): String = when {
    location.startsWith("http://") || location.startsWith("https://") -> location
    location.startsWith("content://") || location.startsWith("file://") -> location
    location.startsWith("/") -> "file://" + location
    else -> location
}

private fun formatClock(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return minutes.toString() + ":" + seconds.toString().padStart(2, '0')
}
