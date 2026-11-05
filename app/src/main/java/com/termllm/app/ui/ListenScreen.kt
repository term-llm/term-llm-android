package com.termllm.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.termllm.app.listen.ListenDownloader
import com.termllm.app.listen.ListenItem
import com.termllm.app.listen.ListenStore
import com.termllm.app.listen.PlaybackState
import com.termllm.app.listen.PlayerHub
import kotlinx.coroutines.delay

private val SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f, 3f)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListenScreen(
    store: ListenStore,
    downloader: ListenDownloader,
    playback: PlaybackState,
    onOpenChat: () -> Unit,
    artwork: com.termllm.app.listen.ListenArtwork? = null,
) {
    val downloadProgress by downloader.progress.collectAsState()
    val downloadRecords by downloader.downloads.collectAsState()
    val appContext = LocalContext.current.applicationContext
    val allowMetered by com.termllm.app.listen.DownloadSettings.allowMetered.collectAsState()
    LaunchedEffect(appContext) { com.termllm.app.listen.DownloadSettings.load(appContext) }
    val queue by store.state.collectAsState()
    val items = queue.items
    var filter by remember { mutableStateOf("up_next") }
    var sleepMinutes by remember { mutableStateOf(0) }
    var todayBytes by remember { mutableStateOf(0L) }
    LaunchedEffect(playback.itemId) { todayBytes = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { downloader.todayBytes() } }
    var adding by remember { mutableStateOf(false) }
    var newUrl by remember { mutableStateOf("") }
    var newTitle by remember { mutableStateOf("") }


    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Listen", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Podcasts & audio",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { adding = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "Add audio URL")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { NowPlayingCard(playback) }
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                ) {
                    FilterChip(selected = filter == "up_next", onClick = { filter = "up_next" }, label = { Text("Up Next") })
                    FilterChip(selected = filter == "history", onClick = { filter = "history" }, label = { Text("History") })
                    listOf(15, 30, 60).forEach { minutes ->
                        FilterChip(
                            selected = sleepMinutes == minutes,
                            onClick = {
                                if (sleepMinutes == minutes) {
                                    PlayerHub.cancelSleepTimer()
                                    sleepMinutes = 0
                                } else {
                                    PlayerHub.startSleepTimer(minutes)
                                    sleepMinutes = minutes
                                }
                            },
                            label = { Text("Sleep " + minutes + "m") },
                        )
                    }
                    if (todayBytes > 0) {
                        AssistChip(
                            onClick = {},
                            label = { Text((todayBytes / 1024 / 1024).toString() + " MB today") },
                        )
                    }
                }
            }
            item {
                FilterChip(selected = !allowMetered, onClick = { com.termllm.app.listen.DownloadSettings.set(appContext, !allowMetered) }, label = { Text("Unmetered downloads only") })
            }
            playback.sleepDeadlineMs?.let { deadline -> item {
                AssistChip(onClick = { PlayerHub.cancelSleepTimer() }, label = { Text("Sleep timer: " + ((deadline - System.currentTimeMillis()).coerceAtLeast(0) / 60000 + 1) + " min left · Cancel") })
            } }
            if (items.isEmpty()) {
                item {
                    EmptyState(
                        title = "Your queue is empty",
                        body = "Ask your agent to add a podcast or audio, or add an audio URL.",
                        action = { TextButton(onClick = onOpenChat) { Text("Open Chat") } }
                    )
                }
            }
            val visible = when (filter) {
                "history" -> items.filter { it.played }
                else -> items.filterNot { it.played }
            }
            items(visible, key = { it.id }) { item ->
                QueueRow(
                    item = item,
                    artwork = artwork,
                    isCurrent = item.id == playback.itemId,
                    playing = item.id == playback.itemId && playback.playing,
                    positionMs = if (item.id == playback.itemId) playback.positionMs else item.positionMs,
                    onPlay = { PlayerHub.play(item) },
                    onRemove = { downloader.removeDownload(item.id); store.remove(item.id) },
                    onMoveUp = { store.move(item.id, -1) },
                    onMoveDown = { store.move(item.id, 1) },
                    downloadProgress = downloadProgress[item.id],
                    downloadError = downloadRecords[item.id]?.error,
                    onDownload = { downloader.download(item) { _ ->  } },
                )
            }
        }
    }

    if (adding) {
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("Add audio URL") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Public podcast/audio URL. Items added by your agent appear here too.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = newUrl,
                        onValueChange = { newUrl = it },
                        label = { Text("Audio URL") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = newTitle,
                        onValueChange = { newTitle = it },
                        label = { Text("Title") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val url = newUrl.trim()
                    if (url.isNotEmpty()) {
                        val item = store.add(
                            ListenItem(
                                id = "listen-" + System.currentTimeMillis(),
                                url = url,
                                title = newTitle.ifBlank { url.substringAfterLast('/') },
                                show = null,
                                kind = "audio",
                            )
                        )

                        PlayerHub.play(item)
                    }
                    adding = false
                    newUrl = ""
                    newTitle = ""
                }) { Text("Add and play") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NowPlayingCard(playback: PlaybackState) {
    val duration = playback.durationMs
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (playback.itemId == null) "Nothing playing" else (playback.title.ifBlank { "Audio" }),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                playback.show ?: if (playback.connected) "MediaSession connected" else "Player connecting…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                modifier = Modifier.semantics { contentDescription = "Playback position"; stateDescription = formatTime(playback.positionMs) + " of " + formatTime(duration) },
                value = if (duration > 0) (playback.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                onValueChange = { fraction ->
                    if (duration > 0) PlayerHub.seekTo((duration * fraction).toLong())
                },
                enabled = duration > 0,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { PlayerHub.seekTo(playback.positionMs - 15_000) }, modifier = Modifier.semantics { contentDescription = "Seek back 15 seconds" }) { Text("−15") }
                IconButton(onClick = { PlayerHub.toggle() }) {
                    Icon(
                        if (playback.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playback.playing) "Pause" else "Play",
                    )
                }
                TextButton(onClick = { PlayerHub.seekTo(playback.positionMs + 30_000) }, modifier = Modifier.semantics { contentDescription = "Seek forward 30 seconds" }) { Text("+30") }
                Text(
                    formatTime(playback.positionMs) + " / " + formatTime(duration),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                SPEEDS.forEach { speed ->
                    FilterChip(
                        selected = kotlin.math.abs(playback.speed - speed) < 0.01f,
                        onClick = { PlayerHub.setSpeed(speed) },
                        label = { Text(speed.toString().removeSuffix(".0") + "×") },
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueRow(
    item: ListenItem,
    artwork: com.termllm.app.listen.ListenArtwork? = null,
    isCurrent: Boolean,
    playing: Boolean,
    positionMs: Long,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    downloadProgress: Float? = null,
    downloadError: String? = null,
    onDownload: () -> Unit = {},
    onMoveUp: () -> Unit = {},
    onMoveDown: () -> Unit = {},
) {
    Surface(
        onClick = onPlay,
        shape = MaterialTheme.shapes.medium,
        color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ArtworkThumb(artwork, item, size = 40.dp)
            Icon(
                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (playing) "Pause" else "Play",
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 2)
                Text(
                    buildString {
                        item.show?.let { append(it).append(" · ") }
                        val duration = if (item.durationMs > 0) item.durationMs else 0L
                        if (duration > 0) append(formatTime(duration)) else append("Duration unknown")
                        if (positionMs > 0) append(" · ").append(formatTime(positionMs))
                        if (item.downloaded) append(" · downloaded")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (downloadError != null) Text(downloadError, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, maxLines = 2, modifier = Modifier.weight(0.6f))
            Column {
                IconButton(onClick = onMoveUp, modifier = Modifier.semantics { contentDescription = "Move " + item.title + " up in queue" }) { Text("↑") }
                IconButton(onClick = onMoveDown, modifier = Modifier.semantics { contentDescription = "Move " + item.title + " down in queue" }) { Text("↓") }
            }
            if (item.downloaded) {
                Icon(Icons.Filled.DownloadDone, contentDescription = "Downloaded")
            } else if (downloadProgress != null) {
                Text((downloadProgress * 100).toInt().toString() + "%", style = MaterialTheme.typography.labelSmall)
            } else {
                IconButton(onClick = onDownload) {
                    Icon(Icons.Filled.Download, contentDescription = "Download for offline")
                }
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Delete, contentDescription = "Remove from queue")
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}
