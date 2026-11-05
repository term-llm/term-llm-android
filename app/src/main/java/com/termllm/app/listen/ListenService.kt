package com.termllm.app.listen

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Playback host for Listen. One ExoPlayer + one MediaSession, exposed to the UI
 * through [PlayerHub] and to the system through the media notification, lock
 * screen and headset buttons. Positions are persisted while playing so a
 * relaunch resumes where the user stopped.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class ListenService : MediaSessionService() {

    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private lateinit var store: ListenStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ticker: Job? = null
    private var currentItemId: String? = null
    private val snapshots = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val snapshotLock = kotlinx.coroutines.sync.Mutex()
    private suspend fun readSnapshot(): PlaybackSnapshot = kotlinx.coroutines.withContext(Dispatchers.IO) {
        runCatching { snapshots.decodeFromString(PlaybackSnapshot.serializer(), File(filesDir,"playback-session.json").readText()) }.getOrDefault(PlaybackSnapshot())
    }
    private fun snapshot() {
        if (!::player.isInitialized || player.mediaItemCount == 0) return
        val value = PlaybackSnapshot((0 until player.mediaItemCount).map { i -> player.getMediaItemAt(i).let { item ->
            PlaybackEntry(item.mediaId,item.localConfiguration?.uri.toString(), item.mediaMetadata.title?.toString().orEmpty(),
                item.mediaMetadata.artist?.toString().orEmpty(),item.mediaMetadata.artworkUri?.toString(),item.mediaMetadata.extras?.getString("playback_mode") ?: "music")
        } },player.currentMediaItemIndex.coerceAtLeast(0),player.currentPosition.coerceAtLeast(0))
        scope.launch(Dispatchers.IO) {
            snapshotLock.lock()
            try {
                val file=File(filesDir,"playback-session.json"); val temp=File(filesDir,"playback-session.tmp")
                java.io.FileOutputStream(temp).use { it.write(snapshots.encodeToString(PlaybackSnapshot.serializer(),value).toByteArray());it.fd.sync() }
                java.nio.file.Files.move(temp.toPath(),file.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } finally { snapshotLock.unlock() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        store = ListenStore(this)
        player = ExoPlayer.Builder(this).setHandleAudioBecomingNoisy(true)
            .setAudioAttributes(androidx.media3.common.AudioAttributes.Builder()
                .setUsage(androidx.media3.common.C.USAGE_MEDIA).setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setWakeMode(androidx.media3.common.C.WAKE_MODE_LOCAL).build()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_ENDED -> {
                        persistPosition(played = true)
                        stopTicker() // End of this actual timeline; never jump music into Listen.
                    }
                    Player.STATE_READY -> startTicker()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) startTicker() else persistPosition()
                snapshot()
            }

            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) oldPosition.mediaItem?.mediaId?.let {
                    store.updatePosition(it, oldPosition.positionMs, oldPosition.positionMs, played = true)
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                mediaItem?.mediaId?.let { currentItemId = it }
                snapshot()
                if (mediaItem?.mediaMetadata?.extras?.getString("playback_mode") == "listen") store.setCurrent(currentItemId)
            }
        })
        val sessionActivity = PendingIntent.getActivity(
            this,
            0,
            Intent().setClassName(packageName, "com.termllm.app.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                    // System media surfaces are trusted; arbitrary external controllers cannot inject URLs.
                    if (controller.packageName != packageName && !controller.isTrusted) return MediaSession.ConnectionResult.reject()
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                            .add(androidx.media3.session.SessionCommand("timer.set", android.os.Bundle.EMPTY))
                            .add(androidx.media3.session.SessionCommand("timer.end", android.os.Bundle.EMPTY)).build()).build()
                }
                override fun onPlaybackResumption(session: MediaSession, controller: MediaSession.ControllerInfo): com.google.common.util.concurrent.ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                    val future = com.google.common.util.concurrent.SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
                    scope.launch {
                        runCatching { readSnapshot() }.fold(onSuccess = { value -> future.set(MediaSession.MediaItemsWithStartPosition(value.items.map { it.mediaItem() },value.index,value.position)) }, onFailure = { future.setException(it) })
                    }
                    return future
                }
                override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, command: androidx.media3.session.SessionCommand, args: android.os.Bundle): com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.SessionResult> {
                    if (command.customAction == "timer.end") {
                        player.setPauseAtEndOfMediaItems(true)
                        return com.google.common.util.concurrent.Futures.immediateFuture(androidx.media3.session.SessionResult(androidx.media3.session.SessionResult.RESULT_SUCCESS))
                    }
                    if (command.customAction != "timer.set") return com.google.common.util.concurrent.Futures.immediateFuture(androidx.media3.session.SessionResult(androidx.media3.session.SessionError.ERROR_NOT_SUPPORTED))
                    setTimer(args.getLong("deadline"))
                    return com.google.common.util.concurrent.Futures.immediateFuture(androidx.media3.session.SessionResult(androidx.media3.session.SessionResult.RESULT_SUCCESS))
                }
            })
            .setSessionActivity(sessionActivity)
            .build()
        scope.launch {
            val deadline = kotlinx.coroutines.withContext(Dispatchers.IO) { getSharedPreferences("playback", MODE_PRIVATE).getLong("sleep_deadline", 0) }
            if (deadline > 0) setTimer(deadline)
            val saved = readSnapshot()
            if (player.mediaItemCount == 0 && saved.items.isNotEmpty()) {
                player.setMediaItems(saved.items.map { it.mediaItem() },saved.index.coerceIn(saved.items.indices),saved.position)
                player.prepare() // Restore paused; playback requires an explicit user/media action.
            }
        }
    }

    private var timer: Job? = null
    private fun setTimer(deadline: Long) {
        timer?.cancel()
        player.setPauseAtEndOfMediaItems(false)
        session?.setSessionExtras(android.os.Bundle().apply { putLong("sleep_deadline",deadline) })
        scope.launch(Dispatchers.IO) { getSharedPreferences("playback", MODE_PRIVATE).edit().putLong("sleep_deadline", deadline).commit() }
        if (deadline <= 0) return
        timer = scope.launch {
            delay((deadline - System.currentTimeMillis()).coerceAtLeast(0))
            player.pause()
            session?.setSessionExtras(android.os.Bundle.EMPTY)
            kotlinx.coroutines.withContext(Dispatchers.IO) { getSharedPreferences("playback", MODE_PRIVATE).edit().remove("sleep_deadline").commit() }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    fun playItem(item: ListenItem) {
        currentItemId = item.id
        store.setCurrent(item.id)
        val metadata = MediaMetadata.Builder()
            .setTitle(item.title)
            .setArtist(item.show ?: "Listen")
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .build()
        val source = item.localPath?.takeIf { item.downloaded && File(it).exists() } ?: item.url
        val mediaItem = MediaItem.Builder()
            .setMediaId(item.id)
            .setUri(source)
            .setMediaMetadata(metadata)
            .build()
        player.setMediaItem(mediaItem, item.positionMs.coerceAtLeast(0))
        player.prepare()
        player.playWhenReady = true
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (true) {
                delay(5000)
                if (player.isPlaying) { persistPosition(); snapshot() }
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    private fun persistPosition(played: Boolean? = null) {
        val id = currentItemId ?: return
        val duration = player.duration.takeIf { it > 0 } ?: 0L
        store.updatePosition(id, player.currentPosition.coerceAtLeast(0), duration, played)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        persistPosition()
        if (!player.isPlaying) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        persistPosition()
        stopTicker()
        session?.run {
            player.release()
            release()
        }
        session = null
        timer?.cancel()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        super.onDestroy()
    }
}
