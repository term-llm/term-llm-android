package com.termllm.app.listen

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import java.io.File

data class PlaybackState(
    val itemId: String? = null,
    val title: String = "",
    val show: String? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1f,
    val connected: Boolean = false,
    val sleepDeadlineMs: Long? = null,
)

/**
 * UI-side handle on the media session. All playback goes through the service, so
 * the same state drives the Listen screen, the shelf and the system notification.
 */
object PlayerHub {

    private var controller: MediaController? = null
    private var connecting: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    private var generation = 0
    val shelfVisible = MutableStateFlow(true)
    fun hideShelf() { shelfVisible.value = false }
    fun showShelf() { shelfVisible.value = true }
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state

    fun connect(context: Context, store: ListenStore) {
        storeRef = store
        if (controller != null || connecting != null) return
        val requestGeneration = ++generation
        val token = SessionToken(context, ComponentName(context, ListenService::class.java))
        val future = MediaController.Builder(context.applicationContext, token)
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) {
                    if (this@PlayerHub.controller === controller) { this@PlayerHub.controller = null; connecting = null; _state.value = PlaybackState() }
                }
            }).buildAsync()
        connecting = future
        future.addListener({
            try {
                val c = future.get()
                if (requestGeneration != generation) { c.release(); return@addListener }
                connecting = null
                controller = c
                c.addListener(object : androidx.media3.common.Player.Listener {
                    override fun onEvents(player: androidx.media3.common.Player, events: androidx.media3.common.Player.Events) {
                        publish(c)
                    }
                })
                publish(c)
            } catch (e: Exception) {
                connecting = null
                Log.w("TermLLM", "media controller unavailable")
            }
        }, MoreExecutors.directExecutor())
    }

    private fun publish(c: MediaController) {
        val id = c.currentMediaItem?.mediaId
        val item = id?.let { storeRef?.item(it) }
        _state.value = PlaybackState(
            itemId = id,
            title = item?.title ?: c.mediaMetadata.title?.toString().orEmpty(),
            show = item?.show ?: c.mediaMetadata.artist?.toString(),
            playing = c.isPlaying,
            positionMs = c.currentPosition.coerceAtLeast(0),
            durationMs = c.duration.takeIf { it > 0 } ?: (item?.durationMs ?: 0L),
            speed = c.playbackParameters.speed,
            connected = true,
            sleepDeadlineMs = c.sessionExtras.getLong("sleep_deadline").takeIf { it > 0 },
        )
    }

    var storeRef: ListenStore? = null

    /**
     * Called just before this app starts audio (iOS `appWillStartPlayback`), with
     * `fromTool` when a device-tool call started it: the chat page's live voice
     * call is ended so it does not listen over the audio (LiveHangUp).
     */
    var onWillStartPlayback: ((fromTool: Boolean) -> Unit)? = null

    /** > 0 while a device-tool call is being handled, so playback it starts is attributed to it. */
    @Volatile var toolInvocationDepth: Int = 0

    private fun willStartPlayback() {
        onWillStartPlayback?.invoke(toolInvocationDepth > 0)
    }

    /** Shelf "Hide" only hides the panel; audio keeps playing. */
    var pauseHintOnly: Boolean = false

    fun play(item: ListenItem) {
        val c = controller ?: return
        storeRef?.setCurrent(item.id)
        showShelf()
        willStartPlayback()
        val queue = storeRef?.items().orEmpty()
        val index = queue.indexOfFirst { it.id == item.id }
        val upcoming = if (index >= 0) queue.drop(index).filter { !it.played || it.id == item.id } else listOf(item)
        val mediaItems = upcoming.map { entry ->
            val source = entry.localPath?.takeIf { entry.downloaded && File(it).exists() } ?: entry.url
            mediaItem(entry.id, source, entry.title, entry.show ?: "Listen", entry.artworkUrl, "listen")
        }
        val resumeAt = if (item.durationMs > 0 && item.positionMs >= item.durationMs - 1500) 0L else item.positionMs.coerceAtLeast(0)
        c.setMediaItems(mediaItems, 0, resumeAt)
        c.prepare(); c.play()
    }

    private fun mediaItem(id: String, uri: String, title: String, artist: String, artwork: String?, mode: String): androidx.media3.common.MediaItem {
        val extras = android.os.Bundle().apply { putString("playback_mode", mode) }
        val metadata = androidx.media3.common.MediaMetadata.Builder().setTitle(title).setArtist(artist)
            .setIsPlayable(true).setIsBrowsable(false).setExtras(extras)
            .apply { if (!artwork.isNullOrBlank()) setArtworkUri(android.net.Uri.parse(artwork)) }.build()
        return androidx.media3.common.MediaItem.Builder().setMediaId(id).setUri(uri).setMediaMetadata(metadata).build()
    }

    fun playPlaylist(playlist: com.termllm.app.music.Playlist) {
        val c = controller ?: return
        val entries = playlist.entries.mapIndexed { i, entry ->
            val uri = if (entry.location.startsWith("/")) "file://" + entry.location else entry.location
            mediaItem("playlist-" + playlist.id + "-" + i, uri, entry.title, entry.artist, null, "playlist")
        }
        if (entries.isEmpty()) return
        willStartPlayback(); showShelf(); c.setMediaItems(entries); c.prepare(); c.play()
    }

    /** Play a device track (MediaStore URI) with its own metadata. */
    fun playUri(mediaId: String, uri: String, title: String, artist: String, artworkUri: String? = null) {
        val c = controller ?: return
        willStartPlayback()
        showShelf()
        c.setMediaItem(mediaItem(mediaId, uri, title, artist, artworkUri, "music"), 0L)
        c.prepare(); c.play()
    }

    fun toggle() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun pause() = controller?.pause()
    fun resume() { controller?.play() }
    fun next() { controller?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() } }
    fun previous() { controller?.let { if (it.hasPreviousMediaItem()) it.seekToPreviousMediaItem() else it.seekTo(0) } }
    fun sleepAtEnd() { controller?.sendCustomCommand(androidx.media3.session.SessionCommand("timer.end", android.os.Bundle.EMPTY), android.os.Bundle()) }

    var sleepAt: Long? = null
        private set
    fun startSleepTimer(minutes: Int) {
        require(minutes in 1..1440)
        sleepAt = System.currentTimeMillis() + minutes * 60_000L
        controller?.sendCustomCommand(androidx.media3.session.SessionCommand("timer.set", android.os.Bundle.EMPTY),
            android.os.Bundle().apply { putLong("deadline", sleepAt!!) })
    }
    fun cancelSleepTimer() {
        sleepAt = null
        controller?.sendCustomCommand(androidx.media3.session.SessionCommand("timer.set", android.os.Bundle.EMPTY), android.os.Bundle())
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs.coerceAtLeast(0))
        publishTick()
    }

    fun setSpeed(speed: Float) {
        controller?.setPlaybackSpeed(speed)
        publishTick()
    }

    fun artworkBytes(): ByteArray? = controller?.mediaMetadata?.artworkData

    fun publishTick() {
        controller?.let { publish(it) }
    }

    fun release() {
        generation++
        connecting?.let { MediaController.releaseFuture(it) }
        connecting = null
        controller?.release()
        controller = null
        _state.value = PlaybackState()
    }
}
