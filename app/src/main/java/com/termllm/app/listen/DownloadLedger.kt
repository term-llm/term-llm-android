package com.termllm.app.listen

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Daily network ledger for Listen transfers: bytes fetched per day, kept in a
 * small JSON file so the "MB today" figure survives restarts. Written under a
 * per-file lock and rewritten whole, because the ledger is tiny and a torn or
 * double-counted byte figure is worse than a lost one. The lock is shared per
 * file path because the UI process and the worker each construct a ledger.
 */
class DownloadLedger(private val file: File) {

    private val lock: Any = lockFor(file)

    fun file(): File = file

    fun record(bytes: Long, transfers: Int = 1, at: Long = System.currentTimeMillis()): Long =
        synchronized(lock) {
            val addedBytes = bytes.coerceAtLeast(0L)
            val addedTransfers = transfers.coerceAtLeast(0).toLong()
            val day = dayKey(at)
            val root = readRoot()
            val previous = root[day]?.let { entry -> runCatching { entry.jsonObject }.getOrNull() }
            val previousBytes = previous?.get("bytes")?.jsonPrimitive?.longOrNull ?: 0L
            val previousTransfers = previous?.get("transfers")?.jsonPrimitive?.longOrNull ?: 0L
            val totalBytes = previousBytes + addedBytes
            val entry = buildJsonObject {
                put("bytes", totalBytes)
                put("transfers", previousTransfers + addedTransfers)
            }
            val updated = JsonObject(root + (day to entry))
            runCatching {
                file.parentFile?.mkdirs()
                file.writeText(updated.toString())
            }
            totalBytes
        }

    fun bytesToday(at: Long = System.currentTimeMillis()): Long = bytesOnDay(dayKey(at))

    fun bytesOnDay(day: String): Long = synchronized(lock) {
        runCatching { readRoot()[day]?.jsonObject?.get("bytes")?.jsonPrimitive?.longOrNull }.getOrNull() ?: 0L
    }

    fun transfersOnDay(day: String): Long = synchronized(lock) {
        runCatching { readRoot()[day]?.jsonObject?.get("transfers")?.jsonPrimitive?.longOrNull }.getOrNull() ?: 0L
    }

    private fun readRoot(): JsonObject = runCatching {
        if (file.exists()) Json.parseToJsonElement(file.readText()).jsonObject else JsonObject(emptyMap())
    }.getOrElse { JsonObject(emptyMap()) }

    private fun dayKey(at: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(at))

    private companion object {
        val locks = ConcurrentHashMap<String, Any>()

        // absolutePath (not canonicalPath) keeps construction free of filesystem calls:
        // the download facade may be built from `remember { }` on the UI thread.
        fun lockFor(file: File): Any = locks.computeIfAbsent(file.absolutePath) { Any() }
    }
}
