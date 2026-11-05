package com.termllm.app.listen
import kotlinx.serialization.Serializable
@Serializable data class PlaybackEntry(val id:String,val uri:String,val title:String,val artist:String,val artwork:String?=null,val mode:String="music") {
 fun mediaItem(): androidx.media3.common.MediaItem = androidx.media3.common.MediaItem.Builder().setMediaId(id).setUri(uri)
  .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(title).setArtist(artist)
   .setExtras(android.os.Bundle().apply { putString("playback_mode",mode) })
   .apply { artwork?.let { setArtworkUri(android.net.Uri.parse(it)) } }.build()).build()
}
@Serializable data class PlaybackSnapshot(val items:List<PlaybackEntry> = emptyList(),val index:Int=0,val position:Long=0)
