package com.termllm.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.termllm.app.listen.PlaybackState
import com.termllm.app.listen.PlayerHub

/**
 * Shared playback shelf above the bottom navigation. Hide only hides the panel;
 * it never stops audio (the iOS shelf behaves the same way).
 */
@Composable
fun PlaybackShelf(playback: PlaybackState, onOpenListen: () -> Unit, onHide: () -> Unit) {
    if (playback.itemId == null) return
    Surface(tonalElevation = 3.dp, color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth()) {
            val progress = if (playback.durationMs > 0) {
                (playback.positionMs.toFloat() / playback.durationMs).coerceIn(0f, 1f)
            } else 0f
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f).clickable(onClick = onOpenListen)) {
                    Text(
                        playback.title.ifBlank { "Audio" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        playback.show ?: "Listen",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { PlayerHub.toggle() }) {
                    Icon(
                        if (playback.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playback.playing) "Pause" else "Play",
                        modifier = Modifier.size(26.dp),
                    )
                }
                IconButton(onClick = onHide) {
                    Icon(Icons.Filled.Close, contentDescription = "Hide player", modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}
