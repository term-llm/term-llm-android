package com.termllm.app.listen

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import java.io.File
import java.io.FileOutputStream

/**
 * Byte-range transfer with resume, modelled on the iOS `ListenTransfer`.
 *
 * Guarantees this class exists to provide (and that callers rely on):
 *  - a partial file is resumable: the next attempt asks for `Range: bytes=<len>-`;
 *  - the validator that produced those bytes (ETag/Last-Modified) is stored next
 *    to the partial file, so a resume after process death still sends `If-Range`;
 *  - a changed remote (validator mismatch, or a partial longer than the recorded
 *    total) restarts from zero instead of appending bytes from another resource;
 *  - a 206 whose `Content-Range` does not start at the local length is flagged
 *    `restart` and is never reported complete;
 *  - `complete` is true only on an exact byte match with the advertised length.
 *    A response that does not advertise a length is verified with a HEAD first,
 *    so a truncated body can never look whole;
 *  - a 416 is only treated as complete when a HEAD confirms the local bytes are
 *    the whole resource; that HEAD carries the same credential and the same
 *    redirect guard as the transfer itself;
 *  - body read failures, write failures and oversized bodies leave a resumable
 *    partial and report `complete = false` with an error;
 *  - cancellation is honoured before the first request is sent and during the
 *    body, and leaves the partial file (and its validator) intact;
 *  - a caller-supplied time budget (`shouldPause`) stops the body early, keeping
 *    the bytes and the validator, so a bounded background worker can hand the
 *    rest to its next run instead of being killed mid-transfer.
 */
class RangeTransfer(
    private val client: OkHttpClient,
    private val authHeader: (String) -> Pair<String, String>? = { null },
    private val redirectGuard: ((String) -> Interceptor?)? = null,
) {

    data class Result(
        val file: File,
        val complete: Boolean,
        val bytes: Long,
        val totalBytes: Long?,
        val etag: String?,
        val error: String? = null,
        val cancelled: Boolean = false,
        /** The attempt stopped on its own time budget: resumable, nothing is wrong. */
        val paused: Boolean = false,
        /** The local bytes belong to another resource/size: discard and refetch. */
        val restart: Boolean = false,
        /** The source rejected the credential; no retry can fix this. */
        val unauthorized: Boolean = false,
        /** HTTP status when a response arrived (null for transport failures). */
        val code: Int? = null,
        /** Server-requested pause before the next attempt (`Retry-After`). */
        val retryAfterSeconds: Long? = null,
    )

    private data class ContentRange(val start: Long, val end: Long, val total: Long?)

    private data class Streamed(val bytes: Long, val cancelled: Boolean, val error: String?, val paused: Boolean = false)

    fun fetch(
        url: String,
        target: File,
        etag: String? = null,
        isCancelled: () -> Boolean = { false },
        shouldPause: (() -> Boolean)? = null,
        credential: ((String) -> Pair<String, String>?)? = null,
        onProgress: ((Long, Long?) -> Unit)? = null,
        maxBytes: Long? = null,
    ): Result {
        val header = credential?.invoke(url) ?: authHeader(url)
        val effectiveClient = guardedClient(url)
        val meta = PartialMeta.read(target)
        var existing = if (target.exists()) target.length() else 0L
        val requestedValidator = etag?.takeIf { it.isNotEmpty() }
        val recordedValidator = meta?.etag?.takeIf { it.isNotEmpty() }
        val recordedTotal = meta?.total
        // Bytes on disk that cannot belong to this resource are worse than no bytes.
        if (existing > 0L && (
                (requestedValidator != null && recordedValidator != null && requestedValidator != recordedValidator) ||
                    (recordedTotal != null && recordedTotal > 0L && existing > recordedTotal)
                )
        ) {
            discard(target)
            existing = 0L
        }
        val validator = requestedValidator ?: recordedValidator

        if (isCancelled()) {
            return Result(target, false, existing, recordedTotal, validator, cancelled = true)
        }

        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", "term-llm-android/1.0")
            // Range arithmetic is only sound on identity encoding.
            .header("Accept-Encoding", "identity")
        header?.let { pair -> builder.header(pair.first, pair.second) }
        if (existing > 0L) {
            builder.header("Range", "bytes=" + existing + "-")
            if (validator != null) builder.header("If-Range", validator)
        }
        val response = try {
            effectiveClient.newCall(builder.build()).execute()
        } catch (e: Exception) {
            return Result(target, false, existing, recordedTotal, validator, e.message ?: "request failed")
        }

        return response.use { resp ->
            val validatorNow = resp.header("ETag") ?: resp.header("Last-Modified") ?: validator
            val retryAfter = resp.header("Retry-After")?.trim()?.toLongOrNull()
            if (resp.code == 206 && validator != null && validatorNow != validator) {
                return@use Result(target, false, existing, recordedTotal, validatorNow, "resource validator changed", restart = true, code = 206)
            }
            when (resp.code) {
                206 -> appendRange(
                    url, target, resp, effectiveClient, header, existing, validatorNow,
                    retryAfter, isCancelled, shouldPause, onProgress, maxBytes,
                )
                200 -> replaceWhole(
                    url, target, resp, effectiveClient, header, validatorNow,
                    retryAfter, isCancelled, shouldPause, onProgress, maxBytes,
                )
                416 -> verifyWhole(url, target, effectiveClient, header, existing, validatorNow, retryAfter)
                401, 403 -> Result(
                    target, false, existing, recordedTotal, validatorNow,
                    "HTTP " + resp.code, unauthorized = true, code = resp.code,
                )
                else -> Result(
                    target, false, existing, recordedTotal, validatorNow,
                    "HTTP " + resp.code, code = resp.code, retryAfterSeconds = retryAfter,
                )
            }
        }
    }

    private fun appendRange(
        url: String,
        target: File,
        resp: okhttp3.Response,
        effectiveClient: OkHttpClient,
        header: Pair<String, String>?,
        existing: Long,
        validator: String?,
        retryAfter: Long?,
        isCancelled: () -> Boolean,
        shouldPause: (() -> Boolean)?,
        onProgress: ((Long, Long?) -> Unit)?,
        maxBytes: Long?,
    ): Result {
        val range = parseContentRange(resp.header("Content-Range"))
        val total = range?.total
        if (total != null && maxBytes != null && total > maxBytes) {
            discard(target)
            return Result(target, false, 0L, total, validator, "file too large (" + total + " bytes)", code = 206)
        }
        val body = resp.body ?: return Result(target, false, existing, total, validator, "empty body", code = 206)
        val startMatches = range != null && range.start == existing
        if (!startMatches) return Result(target, false, existing, total, validator, "range start mismatch", restart = true, code = 206)
        var streamed = Streamed(existing, false, null)
        try {
            target.parentFile?.mkdirs()
            PartialMeta.write(target, total, validator)
            FileOutputStream(target, true).use { out ->
                streamed = stream(body, out, existing, total, isCancelled, shouldPause, onProgress)
                runCatching {
                    out.flush()
                    out.fd.sync()
                }
            }
        } catch (e: Exception) {
            return Result(
                target, false, target.length(), total, validator,
                "write failed: " + (e.message ?: e.javaClass.simpleName), code = 206,
            )
        }
        val bytes = target.length()
        if (streamed.cancelled) {
            PartialMeta.write(target, total, validator)
            return Result(target, false, bytes, total, validator, cancelled = true, code = 206)
        }
        if (streamed.paused) {
            // Time budget reached: keep the bytes and the validator so the next
            // attempt resumes with Range/If-Range instead of starting over.
            PartialMeta.write(target, total, validator)
            return Result(target, false, bytes, total, validator, paused = true, code = 206)
        }
        if (streamed.error != null) {
            PartialMeta.write(target, total, validator)
            return Result(
                target, false, bytes, total, validator, streamed.error,
                restart = streamed.error.startsWith(OVERRUN), code = 206,
            )
        }
        PartialMeta.write(target, total, validator)
        if (!startMatches) {
            // The server answered a range we did not ask for: these bytes are not
            // trustworthy, so the caller must refetch from zero.
            return Result(target, false, bytes, total, validator, "range start mismatch", restart = true, code = 206)
        }
        if (downloadComplete(bytes, total)) return Result(target, true, bytes, total, validator, code = 206)
        if (total != null && bytes < total) {
            // Truncated body: keep the partial for the next attempt, no error.
            return Result(target, false, bytes, total, validator, code = 206)
        }
        val head = if (total == null) headLength(effectiveClient, url, header) else null
        val verified = total ?: head?.first
        val validatorNow = head?.second ?: validator
        if (verified != null) PartialMeta.write(target, verified, validatorNow)
        if (downloadComplete(bytes, verified)) return Result(target, true, bytes, verified, validatorNow, code = 206)
        return Result(
            target, false, bytes, verified, validatorNow,
            if (verified == null) "length unknown" else null,
            code = 206, retryAfterSeconds = retryAfter,
        )
    }

    private fun replaceWhole(
        url: String,
        target: File,
        resp: okhttp3.Response,
        effectiveClient: OkHttpClient,
        header: Pair<String, String>?,
        validator: String?,
        retryAfter: Long?,
        isCancelled: () -> Boolean,
        shouldPause: (() -> Boolean)?,
        onProgress: ((Long, Long?) -> Unit)?,
        maxBytes: Long?,
    ): Result {
        discard(target)
        var total = resp.body?.contentLength()?.takeIf { it >= 0L }
        if (total != null && maxBytes != null && total > maxBytes) {
            return Result(target, false, 0L, total, validator, "file too large (" + total + " bytes)", code = 200)
        }
        val body = resp.body ?: return Result(target, false, 0L, total, validator, "empty body", code = 200)
        var streamed = Streamed(0L, false, null)
        try {
            target.parentFile?.mkdirs()
            PartialMeta.write(target, total, validator)
            FileOutputStream(target, false).use { out ->
                streamed = stream(body, out, 0L, total, isCancelled, shouldPause, onProgress)
                runCatching {
                    out.flush()
                    out.fd.sync()
                }
            }
        } catch (e: Exception) {
            return Result(
                target, false, target.length(), total, validator,
                "write failed: " + (e.message ?: e.javaClass.simpleName), code = 200,
            )
        }
        val bytes = target.length()
        if (streamed.cancelled) {
            PartialMeta.write(target, total, validator)
            return Result(target, false, bytes, total, validator, cancelled = true, code = 200)
        }
        if (streamed.paused) {
            PartialMeta.write(target, total, validator)
            return Result(target, false, bytes, total, validator, paused = true, code = 200)
        }
        if (streamed.error != null) {
            PartialMeta.write(target, total, validator)
            return Result(
                target, false, bytes, total, validator, streamed.error,
                restart = streamed.error.startsWith(OVERRUN), code = 200,
            )
        }
        var validatorNow = validator
        if (total == null) {
            // No advertised length: ask the origin for the size instead of guessing.
            val head = headLength(effectiveClient, url, header)
            if (head != null) {
                total = head.first
                validatorNow = head.second ?: validatorNow
            }
        }
        PartialMeta.write(target, total, validatorNow)
        if (downloadComplete(bytes, total)) return Result(target, true, bytes, total, validatorNow, code = 200)
        return Result(
            target, false, bytes, total, validatorNow,
            if (total == null) "length unknown" else null,
            code = 200, retryAfterSeconds = retryAfter,
        )
    }

    private fun verifyWhole(
        url: String,
        target: File,
        effectiveClient: OkHttpClient,
        header: Pair<String, String>?,
        existing: Long,
        validator: String?,
        retryAfter: Long?,
    ): Result {
        val head = headLength(effectiveClient, url, header)
        val headLength = head?.first
        val headValidator = head?.second ?: validator
        val headSecond = head?.second
        val validatorsAgree = validator == null || headSecond == null || validator == headSecond
        if (headLength != null && headLength > 0L && headLength == existing && validatorsAgree) {
            PartialMeta.write(target, headLength, headValidator)
            return Result(target, true, existing, headLength, headValidator, code = 416)
        }
        discard(target)
        return Result(
            target, false, 0L, headLength, headValidator,
            "range not satisfiable; restarted", restart = true, code = 416, retryAfterSeconds = retryAfter,
        )
    }

    /** Reads the body into [out], syncing periodically so a process kill loses little. */
    private fun stream(
        body: ResponseBody,
        out: FileOutputStream,
        base: Long,
        total: Long?,
        isCancelled: () -> Boolean,
        shouldPause: (() -> Boolean)?,
        onProgress: ((Long, Long?) -> Unit)?,
    ): Streamed {
        var bytes = base
        var sinceSync = 0L
        val buffer = ByteArray(BUFFER_SIZE)
        try {
            body.byteStream().use { input ->
                while (true) {
                    if (isCancelled()) return Streamed(bytes, true, null)
                    if (shouldPause != null && shouldPause()) return Streamed(bytes, false, null, paused = true)
                    val read = input.read(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    bytes += read
                    sinceSync += read
                    if (sinceSync >= SYNC_INTERVAL) {
                        out.flush()
                        out.fd.sync()
                        sinceSync = 0L
                    }
                    if (total != null && bytes > total) {
                        return Streamed(bytes, false, OVERRUN + " the advertised length")
                    }
                    onProgress?.invoke(bytes, total)
                }
            }
        } catch (e: Exception) {
            return Streamed(bytes, false, "read failed: " + (e.message ?: e.javaClass.simpleName))
        }
        return Streamed(bytes, false, null)
    }

    private fun headLength(client: OkHttpClient, url: String, header: Pair<String, String>?): Pair<Long, String?>? {
        val builder = Request.Builder()
            .url(url)
            .head()
            .header("User-Agent", "term-llm-android/1.0")
            .header("Accept-Encoding", "identity")
        header?.let { pair -> builder.header(pair.first, pair.second) }
        return try {
            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    null
                } else {
                    resp.header("Content-Length")?.toLongOrNull()?.let { length ->
                        length to (resp.header("ETag") ?: resp.header("Last-Modified"))
                    }
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun guardedClient(url: String): OkHttpClient {
        val origin = originOf(url) ?: return client
        val guard = redirectGuard?.invoke(origin) ?: return client
        return client.newBuilder().addInterceptor(guard).build()
    }

    private fun originOf(url: String): String? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        val port = if (parsed.port == HttpUrl.defaultPort(parsed.scheme)) "" else ":" + parsed.port
        return parsed.scheme + "://" + parsed.host + port
    }

    private fun parseContentRange(value: String?): ContentRange? {
        if (value.isNullOrBlank()) return null
        val spec = value.trim().let { if (it.startsWith("bytes", ignoreCase = true)) it.substring(5).trim() else it }
        val range = spec.substringBefore('/', "")
        val total = spec.substringAfter('/', "").trim().toLongOrNull()
        val start = range.substringBefore('-', "").trim().toLongOrNull() ?: return null
        val end = range.substringAfter('-', "").trim().toLongOrNull() ?: return null
        return ContentRange(start, end, total)
    }

    private fun downloadComplete(bytes: Long, total: Long?): Boolean =
        total != null && total > 0L && bytes == total

    private fun discard(target: File) {
        discardPartial(target)
    }

    companion object {
        /** Drop a partial file and its validator sidecar (untrusted bytes). */
        fun discardPartial(target: File) {
            runCatching { if (target.exists()) target.delete() }
            PartialMeta.clear(target)
        }

        private const val BUFFER_SIZE = 64 * 1024
        private const val SYNC_INTERVAL = 1024L * 1024L
        private const val OVERRUN = "server sent more than"
    }
}

/**
 * Validator/size sidecar for a partial file. Loss is harmless (the next attempt
 * just resumes without `If-Range`), so a damaged sidecar is ignored.
 */
private object PartialMeta {

    data class Meta(val total: Long?, val etag: String?)

    private fun sidecar(target: File): File = File(target.parentFile ?: File("."), target.name + ".meta")

    fun read(target: File): Meta? {
        val file = sidecar(target)
        if (!file.exists()) return null
        return runCatching {
            val lines = file.readLines()
            if (lines.isEmpty() || lines[0] != FORMAT) return null
            val total = lines.getOrNull(1)?.toLongOrNull()
            val etag = lines.getOrNull(2)?.takeIf { it != NONE && it.isNotEmpty() }
            Meta(total, etag)
        }.getOrNull()
    }

    fun write(target: File, total: Long?, etag: String?) {
        runCatching {
            target.parentFile?.mkdirs()
            val file = sidecar(target)
            val tmp = File(file.parentFile ?: File("."), file.name + ".tmp")
            tmp.writeText(FORMAT + "\n" + (total?.toString() ?: NONE) + "\n" + (etag ?: NONE) + "\n")
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }

    fun clear(target: File) {
        runCatching { sidecar(target).delete() }
    }

    private const val FORMAT = "listen-partial-v1"
    private const val NONE = "-"
}
