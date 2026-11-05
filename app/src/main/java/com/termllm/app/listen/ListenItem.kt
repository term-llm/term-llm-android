package com.termllm.app.listen

import kotlinx.serialization.Serializable

/**
 * One queued audio item. Field names follow the iOS `ListenItem` model where the
 * same facts exist, so tool output and the UI describe the same thing:
 * what it is, where it came from, how far the user got, and whether it is offline.
 */
@Serializable
data class ListenItem(
    val id: String,
    val url: String,
    val title: String,
    val show: String? = null,
    /** Lock-screen/list image; fetched with this item's source credentials only. */
    val artworkUrl: String? = null,
    val kind: String = "podcast",
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val played: Boolean = false,
    val downloaded: Boolean = false,
    val localPath: String? = null,
    val etag: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
    /** Connection id the item was added from, so media requests can reuse that source only. */
    val sourceId: String? = null,
)

@Serializable
data class ListenQueue(
    val items: List<ListenItem> = emptyList(),
    val currentId: String? = null,
)
