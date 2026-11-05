package com.termllm.app.listen

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Artwork kept on the phone, ported from the iOS `ListenArtworkStore`:
 *
 *  - each image is fetched once and saved to a file keyed by **owner + URL**, so
 *    one source's image can never appear for another source's item;
 *  - a server image is fetched only with its owner's credentials (the same
 *    `MediaAuth` supplier the media transfer uses); other hosts never get them;
 *  - an image that failed is not retried for [retryDelayMillis];
 *  - saved files for items that are no longer queued are pruned;
 *  - decoded bitmaps are cached in memory so rows and the shelf share one decode.
 */
class ListenArtwork(
    private val dir: File,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
    /** Applies the owning source's credentials; public hosts get none. */
    private val decorate: (Request.Builder, String, String?) -> Request.Builder = { builder, _, _ -> builder },
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    private val memory = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean = size > MAX_MEMORY
    }
    private val failures = mutableMapOf<String, Long>()

    fun ensureDir() {
        if (!dir.exists()) dir.mkdirs()
    }

    /** Stable file name for an item's artwork; the owner is part of the key. */
    fun fileName(url: String, ownerId: String?): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(((ownerId ?: "public") + " " + url).toByteArray())
        val hex = digest.take(16).joinToString("") { String.format("%02x", it) }
        return hex + ".img"
    }

    fun file(item: ListenItem): File? = savedFile(item.artworkUrl, item.sourceId)

    fun savedFile(url: String?, ownerId: String?): File? {
        if (url.isNullOrBlank()) return null
        val candidate = File(dir, fileName(url, ownerId))
        return candidate.takeIf { it.exists() && it.length() > 0 }
    }

    /** Fetch and save the image if it is not on disk already (60 s retry backoff). */
    fun fetch(item: ListenItem): File? {
        val url = item.artworkUrl ?: return null
        savedFile(url, item.sourceId)?.let { return it }
        val key = fileName(url, item.sourceId)
        val failedAt = failures[key]
        if (failedAt != null && now() - failedAt < RETRY_DELAY_MS) return null
        ensureDir()
        return try {
            val builder = Request.Builder().url(url).get()
            val request = decorate(builder, url, item.sourceId).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    failures[key] = now()
                    return null
                }
                val bytes = response.body?.bytes() ?: return null
                if (bytes.isEmpty()) {
                    failures[key] = now()
                    return null
                }
                val target = File(dir, key)
                val temporary = File(dir, key + ".part")
                temporary.writeBytes(bytes)
                if (!temporary.renameTo(target)) {
                    temporary.delete()
                    failures[key] = now()
                    return null
                }
                failures.remove(key)
                target
            }
        } catch (e: Exception) {
            failures[key] = now()
            null
        }
    }

    /** Decoded bitmap for an item, from memory or its saved file. */
    fun bitmap(item: ListenItem): Bitmap? {
        val key = item.artworkUrl?.let { fileName(it, item.sourceId) } ?: return null
        synchronized(memory) { memory[key]?.let { return it } }
        val file = savedFile(item.artworkUrl, item.sourceId) ?: return null
        val decoded = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        synchronized(memory) { memory[key] = decoded }
        return decoded
    }

    /** Drop saved images that no queued item uses any more. */
    fun prune(keeping: List<ListenItem>) {
        ensureDir()
        val used = keeping.mapNotNull { item -> item.artworkUrl?.let { fileName(it, item.sourceId) } }.toSet()
        dir.listFiles()?.forEach { file ->
            if (file.name !in used) file.delete()
        }
        synchronized(memory) { memory.keys.retainAll(used) }
    }

    companion object {
        const val RETRY_DELAY_MS = 60_000L
        private const val MAX_MEMORY = 24
    }
}
