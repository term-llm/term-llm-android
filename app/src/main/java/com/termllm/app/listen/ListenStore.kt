package com.termllm.app.listen

import android.content.Context
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Durable queue + resume positions. Writes are atomic (tmp + rename) because a
 * position write happens while playback continues; a half-written queue would
 * lose the user's place.
 */
class ListenStore private constructor(dir: File, asynchronousLoad: Boolean) {
    constructor(dir: File) : this(dir, false)
    constructor(context: Context) : this(context.applicationContext.filesDir, true)

    val directory: File = dir.absoluteFile
    private val core = shared.computeIfAbsent(dir.absolutePath) { Core(dir, asynchronousLoad) }
    val state: kotlinx.coroutines.flow.StateFlow<ListenQueue> get() = core.state
    val errors: kotlinx.coroutines.flow.StateFlow<String?> get() = core.errors

    fun queue(): ListenQueue = core.state.value
    suspend fun awaitReady() = core.ready.await()
    /** Test/worker durability barrier. Never call from an Android UI callback. */
    fun flushBlocking() { core.writer.submit {}.get() }

    private fun mutate(transform: (ListenQueue) -> ListenQueue) = core.mutate(transform)

    private class Core(val dir: File, asynchronous: Boolean) {
        val state = kotlinx.coroutines.flow.MutableStateFlow(ListenQueue())
        val errors = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
        val ready = kotlinx.coroutines.CompletableDeferred<Unit>()
        val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "listen-store-io").apply { isDaemon = true } }
        val lock = Any()
        var loaded = false
        val pending = mutableListOf<(ListenQueue) -> ListenQueue>()
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val file = File(dir, "listen-queue.json")
        init {
            val load: () -> Unit = {
                val initial = try {
                    if (file.exists()) json.decodeFromString(ListenQueue.serializer(), file.readText()) else ListenQueue()
                } catch (e: Exception) {
                    errors.value = "Could not read queue: " + e.javaClass.simpleName
                    ListenQueue()
                }
                synchronized(lock) {
                    state.value = pending.fold(initial) { value, update -> update(value) }
                    loaded = true
                    if (pending.isNotEmpty()) persist(state.value)
                    pending.clear()
                    ready.complete(Unit)
                }
            }
            if (asynchronous) writer.execute(load) else load()
        }
        fun mutate(update: (ListenQueue) -> ListenQueue) = synchronized(lock) {
            state.value = update(state.value)
            if (!loaded) pending += update else {
                val snapshot = state.value
                writer.execute { persist(snapshot) }
            }
        }
        private fun persist(queue: ListenQueue) {
            try {
                dir.mkdirs()
                val tmp = File(dir, "listen-queue.json.tmp")
                java.io.FileOutputStream(tmp).use { output ->
                    output.write(json.encodeToString(ListenQueue.serializer(), queue).toByteArray())
                    output.fd.sync()
                }
                try { java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE) }
                catch (_: java.nio.file.AtomicMoveNotSupportedException) { java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
                errors.value = null
            } catch (e: Exception) { errors.value = "Queue save failed: " + e.javaClass.simpleName }
        }
    }
    companion object {
        private val shared = java.util.concurrent.ConcurrentHashMap<String, Core>()
        /** New-process simulation for isolated tests, only after a durability barrier. */
        fun forgetForTest(dir: File) { shared.remove(dir.absolutePath)?.writer?.shutdown() }
    }

    fun add(item: ListenItem): ListenItem {
        var result = item
        mutate { current ->
            val previous = current.items.firstOrNull { it.url == item.url }
            result = if (previous != null) item.copy(
                id = previous.id, positionMs = previous.positionMs,
                downloaded = previous.downloaded, localPath = previous.localPath, etag = previous.etag,
            ) else item
            current.copy(items = current.items.filterNot { it.url == item.url } + result)
        }
        return result
    }
    fun items(): List<ListenItem> = queue().items
    fun item(id: String): ListenItem? = queue().items.firstOrNull { it.id == id }
    fun remove(id: String) = mutate { current -> current.copy(items = current.items.filterNot { it.id == id }, currentId = current.currentId?.takeIf { it != id }) }
    fun move(id: String, offset: Int): Boolean {
        var moved = false
        mutate { current ->
            val from = current.items.indexOfFirst { it.id == id }; val to = from + offset
            if (from < 0 || to !in current.items.indices) current else {
                moved = true
                val items = current.items.toMutableList(); items.add(to, items.removeAt(from)); current.copy(items = items)
            }
        }
        return moved
    }
    fun clearPlayed() = mutate { it.copy(items = it.items.filterNot { item -> item.played }) }
    fun setCurrent(id: String?) = mutate { it.copy(currentId = id) }
    fun current(): ListenItem? = queue().currentId?.let { item(it) }
    fun updatePosition(id: String, positionMs: Long, durationMs: Long = 0L, played: Boolean? = null) = mutate { current ->
        current.copy(items = current.items.map { item -> if (item.id != id) item else item.copy(
            positionMs = positionMs.coerceAtLeast(0), durationMs = durationMs.takeIf { it > 0 } ?: item.durationMs, played = played ?: item.played,
        ) })
    }
    fun clearDownloaded(id: String) = mutate { current -> current.copy(items = current.items.map {
        if (it.id == id) it.copy(downloaded = false, localPath = null, etag = null) else it
    }) }
    fun markDownloaded(id: String, path: String, etag: String? = null) = mutate { current ->
        current.copy(items = current.items.map { if (it.id == id) it.copy(downloaded = true, localPath = path, etag = etag ?: it.etag) else it })
    }
    fun next(afterId: String?): ListenItem? {
        val items = items(); if (items.isEmpty()) return null
        if (afterId == null) return items.first()
        val index = items.indexOfFirst { it.id == afterId }
        if (index < 0) return null
        return items[(index + 1) % items.size]
    }
    fun previous(beforeId: String?): ListenItem? {
        val items = items(); if (items.isEmpty()) return null
        if (beforeId == null) return items.first()
        val index = items.indexOfFirst { it.id == beforeId }
        if (index < 0) return null
        return items[(index - 1 + items.size) % items.size]
    }
}
