package com.termllm.app.tools

import com.termllm.app.listen.ListenDownloader
import com.termllm.app.listen.ListenItem
import com.termllm.app.listen.ListenStore
import com.termllm.app.listen.PlayerHub
import org.json.JSONArray
import org.json.JSONObject

/**
 * `media_*` device tools with the iOS argument shapes:
 *  - media_add:    `items: [{url, title, show, kind, placement}]`
 *  - media_play:   `item` = id or exact title (omitted = resume)
 *  - media_control:`action` from the iOS control set (+ position/delta/rate/minutes)
 *  - media_list:   `filter` = up_next|history|all, `limit`
 *  - media_manage: `items: [id|title]` + action from the iOS manage set
 */
class MediaTools(
    private val store: ListenStore,
    private val downloader: ListenDownloader,
    /**
     * The source whose chat page is calling. Items remember it so media and
     * artwork requests can be credentialed for that server only, never for an
     * arbitrary host.
     */
    private val sourceId: () -> String? = { null },
) {

    companion object {
        val CONTROL_ACTIONS = setOf(
            "pause", "resume", "stop", "seek", "skip", "next", "previous", "rate",
            "sleep", "sleep_at_end", "cancel_sleep", "hide_player", "show_player",
        )
        val MANAGE_ACTIONS = setOf(
            "remove", "mark_played", "mark_unplayed", "archive", "move", "clear_history",
            "download", "cancel_download", "delete_download",
        )
    }

    private var sleepUntil: Long? = null

    fun handle(name: String, argumentsJson: String): String {
        val args = runCatching { if (argumentsJson.isBlank()) JSONObject() else JSONObject(argumentsJson) }
            .getOrElse { JSONObject() }
        return when (name) {
            "media_add" -> add(args)
            "media_list" -> list(args)
            "media_play" -> play(args)
            "media_control" -> control(args)
            "media_now_playing" -> nowPlaying()
            "media_manage" -> manage(args)
            else -> throw IllegalArgumentException("Unknown tool: " + name)
        }.toString()
    }

    // ---- add ---------------------------------------------------------------
    private fun add(args: JSONObject): JSONObject {
        val items = args.optJSONArray("items")
        val added = JSONArray()
        if (items != null) {
            for (index in 0 until items.length()) {
                added.put(JSONObject(addOne(items.getJSONObject(index), args.optString("placement"))))
                if (items.length() > 1) break
            }
        } else {
            added.put(JSONObject(addOne(args, args.optString("placement"))))
        }
        return JSONObject().put("added", added).put("count", added.length())
    }

    private fun addOne(args: JSONObject, placement: String?): String {
        val url = args.optString("url")
        require(url.startsWith("http://") || url.startsWith("https://")) {
            "url must be an http(s) media file, not a web page"
        }
        val item = store.add(
            ListenItem(
                id = "listen-" + System.currentTimeMillis() + "-" + (url.hashCode() and 0xffff),
                url = url,
                title = args.optString("title").ifBlank { url.substringAfterLast('/') },
                show = args.optString("show").ifBlank { args.optString("artist").ifBlank { null } },
                kind = args.optString("kind").ifBlank { "podcast" },
                artworkUrl = args.optString("artwork_url").ifBlank { null },
                sourceId = sourceId(),
                durationMs = args.optLong("duration_ms", 0L),
            )
        )
        val placementResolved = placement?.ifBlank { null } ?: args.optString("placement").ifBlank { "end" }
        if (placementResolved == "now" || args.optBoolean("play", false)) {
            PlayerHub.play(item)
            store.setCurrent(item.id)
        }
        return JSONObject()
            .put("id", item.id)
            .put("title", item.title)
            .put("show", item.show ?: JSONObject.NULL)
            .put("placement", placementResolved)
            .toString()
    }

    // ---- list --------------------------------------------------------------
    private fun list(args: JSONObject): JSONObject {
        val filter = args.optString("filter").ifBlank { "up_next" }
        val limit = args.optInt("limit", 20).coerceIn(1, 50)
        val all = store.items()
        val filtered = when (filter) {
            "history" -> all.filter { it.played }
            "all" -> all
            else -> all.filterNot { it.played }
        }
        val items = JSONArray()
        filtered.take(limit).forEach { item ->
            items.put(
                JSONObject()
                    .put("id", item.id)
                    .put("title", item.title)
                    .put("show", item.show ?: JSONObject.NULL)
                    .put("duration_ms", item.durationMs)
                    .put("position_ms", item.positionMs)
                    .put("played", item.played)
                    .put("downloaded", item.downloaded)
                    .put("kind", item.kind)
            )
        }
        return JSONObject().put("filter", filter).put("items", items).put("count", items.length())
    }

    // ---- play --------------------------------------------------------------
    private fun resolve(args: JSONObject, key: String = "item"): ListenItem? {
        val ref = args.optString(key)
        if (ref.isBlank()) return null
        return store.item(ref) ?: store.items().firstOrNull { it.title.equals(ref, ignoreCase = true) }
    }

    private fun play(args: JSONObject): JSONObject {
        val item = resolve(args) ?: store.current() ?: store.items().firstOrNull()
        ?: throw IllegalArgumentException("Nothing to play; the Listen queue is empty")
        PlayerHub.play(item)
        store.setCurrent(item.id)
        return JSONObject().put("status", "playing").put("id", item.id).put("title", item.title)
    }

    // ---- control -----------------------------------------------------------
    private fun control(args: JSONObject): JSONObject {
        val action = args.optString("action")
        require(action in CONTROL_ACTIONS) { "Unknown action: " + action }
        when (action) {
            "pause" -> PlayerHub.pause()
            "resume" -> PlayerHub.resume()
            "stop" -> {
                PlayerHub.pause()
                PlayerHub.seekTo(0)
            }
            "seek" -> PlayerHub.seekTo(args.optDouble("position", 0.0).toLong())
            "skip" -> PlayerHub.seekTo(PlayerHub.state.value.positionMs + args.optDouble("delta", 0.0).toLong())
            "next" -> PlayerHub.next()
            "previous" -> PlayerHub.previous()
            "rate" -> PlayerHub.setSpeed(args.optDouble("rate", 1.0).toFloat())
            "sleep" -> PlayerHub.startSleepTimer(args.optInt("minutes",30))
            "sleep_at_end" -> PlayerHub.sleepAtEnd()
            "cancel_sleep" -> PlayerHub.cancelSleepTimer()
            "hide_player" -> PlayerHub.hideShelf()
            "show_player" -> PlayerHub.showShelf()
        }
        return nowPlaying()
    }

    // ---- now playing -------------------------------------------------------
    private fun nowPlaying(): JSONObject {
        val state = PlayerHub.state.value
        return JSONObject()
            .put("id", state.itemId ?: JSONObject.NULL)
            .put("title", state.title)
            .put("show", state.show ?: JSONObject.NULL)
            .put("playing", state.playing)
            .put("position_ms", state.positionMs)
            .put("duration_ms", state.durationMs)
            .put("rate", state.speed.toDouble())
            .put("sleep_until", state.sleepDeadlineMs ?: JSONObject.NULL)
            .apply { if (com.termllm.app.BuildConfig.DEBUG) put("debug_dispatch_count",WebMCPBridge.debugDispatchCount) }
    }

    // ---- manage ------------------------------------------------------------
    private fun manage(args: JSONObject): JSONObject {
        val action = args.optString("action")
        require(action in MANAGE_ACTIONS) { "Unknown action: " + action }
        val refs = mutableListOf<String>()
        args.optJSONArray("items")?.let { array ->
            for (index in 0 until array.length()) refs.add(array.getString(index))
        }
        if (refs.isEmpty() && args.has("item")) refs.add(args.optString("item"))
        if (action == "clear_history") {
            store.clearPlayed()
            return JSONObject().put("action", action).put("affected", 1)
        }
        require(refs.isNotEmpty()) { "items is required for " + action }
        var affected = 0
        refs.forEach { ref ->
            val item = store.item(ref) ?: store.items().firstOrNull { it.title.equals(ref, ignoreCase = true) }
            if (item != null) {
                when (action) {
                    "remove", "archive" -> { downloader.removeDownload(item.id); store.remove(item.id) }
                    "delete_download" -> downloader.removeDownload(item.id)
                    "mark_played" -> store.updatePosition(item.id, item.positionMs, item.durationMs, played = true)
                    "mark_unplayed" -> store.updatePosition(item.id, 0, item.durationMs, played = false)
                    "download" -> downloader.download(item)
                    "cancel_download" -> downloader.cancel(item.id)
                    "move" -> Unit
                }
                affected += 1
            }
        }
        return JSONObject().put("action", action).put("affected", affected)
    }
}
