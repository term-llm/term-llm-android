package com.termllm.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.termllm.app.listen.ListenArtwork
import com.termllm.app.listen.ListenItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Artwork thumbnail for a queued item. The image is fetched once, with the item's
 * own source credentials, then read from disk; until it exists the slot stays a
 * quiet placeholder rather than shifting the row's layout.
 */
@Composable
fun ArtworkThumb(artwork: ListenArtwork?, item: ListenItem, size: Dp = 44.dp) {
    var bitmap by remember(item.id, item.artworkUrl) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(item.id, item.artworkUrl, artwork) {
        if (artwork == null || item.artworkUrl.isNullOrBlank()) return@LaunchedEffect
        bitmap = withContext(Dispatchers.IO) {
            artwork.bitmap(item) ?: artwork.fetch(item)?.let { artwork.bitmap(item) }
        }
    }
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        bitmap?.let { image ->
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
