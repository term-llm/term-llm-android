package com.termllm.app
import android.content.ComponentName
import android.content.Context
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.termllm.app.listen.*
import com.termllm.app.music.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
@RunWith(AndroidJUnit4::class)
class PlaybackHardeningTest {
 // User-initiated playback needs a visible app on Android 15+. Do not inherit
 // foreground eligibility accidentally from a previous test's activity.
 @get:org.junit.Rule val foreground=androidx.test.ext.junit.rules.ActivityScenarioRule(MainActivity::class.java)
 private val instrumentation=InstrumentationRegistry.getInstrumentation()
 private val context=instrumentation.targetContext
 private fun main(block:()->Unit)=instrumentation.runOnMainSync(block)
 private fun waitFor(block:()->Boolean) { val end=System.currentTimeMillis()+12000;while(System.currentTimeMillis()<end){var ok=false;main{ok=block()};if(ok)return;Thread.sleep(100)};error("Playback condition timed out") }
 private fun wave(name:String,seconds:Int):File {
  val file=File(context.cacheDir,name);val samples=8000*seconds;val data=ByteBuffer.allocate(44+samples*2).order(ByteOrder.LITTLE_ENDIAN)
  data.put("RIFF".toByteArray()).putInt(36+samples*2).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16).put("data".toByteArray()).putInt(samples*2)
  repeat(samples){data.putShort((kotlin.math.sin(it*2*Math.PI*220/8000)*1500).toInt().toShort())};file.writeBytes(data.array());return file
 }
 @Test fun completePlaylistStaysInItsOwnTimelineAndHideDoesNotPause() {
  val one=wave("playlist-one.wav",1);val two=wave("playlist-two.wav",1)
  val future=MediaController.Builder(context,SessionToken(context,ComponentName(context,ListenService::class.java))).buildAsync()
  val c=future.get(10,TimeUnit.SECONDS)
  try {
   main { PlayerHub.connect(context,ListenStore(context)) }
   waitFor { PlayerHub.state.value.connected }
   main { PlayerHub.playPlaylist(Playlist("fixture-playlist","Fixture",listOf(PlaylistEntry(one.toURI().toString(),"One"),PlaylistEntry(two.toURI().toString(),"Two")))) }
   waitFor { c.isPlaying }
   main { assertEquals(androidx.media3.common.C.USAGE_MEDIA,c.audioAttributes.usage);PlayerHub.hideShelf();assertFalse(PlayerHub.shelfVisible.value);assertTrue(c.isPlaying) }
   waitFor { c.playbackState==Player.STATE_ENDED }
   main { assertEquals(2,c.mediaItemCount);assertEquals("playlist-fixture-playlist-1",c.currentMediaItem!!.mediaId);assertFalse(c.isPlaying) }
   // Music cannot fall through into whatever podcast queue the user already has.
   main { PlayerHub.playUri("music-fixture",one.toURI().toString(),"Standalone","Fixture") }
   waitFor { c.playbackState==Player.STATE_ENDED }
   main { assertEquals("music-fixture",c.currentMediaItem!!.mediaId);assertEquals(1,c.mediaItemCount) }
  } finally { main { c.stop(); c.clearMediaItems(); c.release(); PlayerHub.release() };one.delete();two.delete() }
 }
 @Test fun repositoryDiskWritesAreNotOnTheUiThread() {
  val dir=File(context.cacheDir,"strict-queue-"+System.nanoTime());val store=ListenStore(dir)
  try {
   main {
    val prior=android.os.StrictMode.getThreadPolicy()
    android.os.StrictMode.setThreadPolicy(android.os.StrictMode.ThreadPolicy.Builder().detectDiskWrites().penaltyDeath().build())
    try { repeat(50){store.add(ListenItem("strict-$it","https://example.org/$it","Strict $it"))};store.updatePosition("strict-1",500) }
    finally {android.os.StrictMode.setThreadPolicy(prior)}
   }
   store.flushBlocking();ListenStore.forgetForTest(dir)
   assertEquals(50,ListenStore(dir).items().size)
  } finally {ListenStore.forgetForTest(dir);dir.deleteRecursively()}
 }
 @Test fun playingPositionQueueEditsAndWorkerCompletionPersistTogether() {
  val store=(context.applicationContext as TermLlmApplication).listenStore
  runBlockingReady(store)
  val audio=wave("concurrent-play.wav",9)
  val prefix="concurrent-"+System.nanoTime()
  val server=okhttp3.mockwebserver.MockWebServer()
  server.dispatcher=object:okhttp3.mockwebserver.Dispatcher() {
   override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest)=okhttp3.mockwebserver.MockResponse().setBody(okio.Buffer().write(ByteArray(131072)))
  };server.start()
  val playing=ListenItem(prefix+"-playing",audio.toURI().toString(),"Concurrent QA",downloaded=true,localPath=audio.absolutePath)
  val download=ListenItem(prefix+"-download",server.url("/episode.wav").toString(),"Concurrent worker QA")
  val downloader=ListenDownloader(context,store)
  val pool=java.util.concurrent.Executors.newFixedThreadPool(3)
  try {
   store.add(playing);store.add(download)
   main {PlayerHub.connect(context,store)};waitFor {PlayerHub.state.value.connected}
   main {PlayerHub.play(playing)};waitFor {PlayerHub.state.value.playing}
   repeat(20){i->pool.submit {store.add(ListenItem(prefix+"-$i","https://example.org/"+prefix+"/$i","Concurrent $i"))}}
   downloader.download(download)
   pool.shutdown();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS))
   repeat(10){store.remove(prefix+"-$it")}
   // Await real Media3 position and the pause acknowledgement, not a wall-clock
   // guess that can race the service's five-second persistence tick.
   waitFor { PlayerHub.state.value.positionMs >= 4500 }
   main {PlayerHub.pause()}
   waitFor { !PlayerHub.state.value.playing && (store.item(playing.id)?.positionMs ?: 0) >= 4000 }
   val end=System.currentTimeMillis()+20000
   while(store.item(download.id)?.downloaded!=true&&System.currentTimeMillis()<end)Thread.sleep(100)
   store.flushBlocking()
   assertTrue(store.item(playing.id)!!.positionMs>=4000)
   assertTrue("worker record="+downloader.downloads.value[download.id]+" requests="+server.requestCount,store.item(download.id)!!.downloaded)
   val disk=kotlinx.serialization.json.Json {ignoreUnknownKeys=true}.decodeFromString(ListenQueue.serializer(),File(store.directory,"listen-queue.json").readText())
   assertTrue(disk.items.first{it.id==download.id}.downloaded)
   assertTrue(disk.items.first{it.id==playing.id}.positionMs>=4000)
   repeat(10){assertFalse(disk.items.any{item->item.id==prefix+"-$it"})}
   assertEquals(12,disk.items.count{it.id.startsWith(prefix)})
  } finally {
   main {PlayerHub.pause();PlayerHub.release()}
   store.items().filter{it.id.startsWith(prefix)}.forEach{downloader.removeDownload(it.id);store.remove(it.id)}
   store.flushBlocking();pool.shutdownNow();audio.delete();server.shutdown()
  }
 }
 private fun runBlockingReady(store:ListenStore) {kotlinx.coroutines.runBlocking{store.awaitReady()}}

 @Test fun realAudioFocusInterruptionAndServiceDeadlinePausePlayback() {
  val audio=wave("focus-and-timer.wav",9)
  val future=MediaController.Builder(context,SessionToken(context,ComponentName(context,ListenService::class.java))).buildAsync()
  val c=future.get(10,TimeUnit.SECONDS)
  val audioManager=context.getSystemService(android.media.AudioManager::class.java)
  val request=android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
   .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
   .setOnAudioFocusChangeListener({ },android.os.Handler(android.os.Looper.getMainLooper())).build()
  try {
   main {PlayerHub.connect(context,ListenStore(context))};waitFor{PlayerHub.state.value.connected}
   main {PlayerHub.playUri("focus-fixture",audio.toURI().toString(),"Focus fixture","QA")};waitFor{c.isPlaying}
   assertEquals(android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED,audioManager.requestAudioFocus(request))
   waitFor{!c.isPlaying}
   audioManager.abandonAudioFocusRequest(request)
   waitFor{c.isPlaying}
   var result:com.google.common.util.concurrent.ListenableFuture<androidx.media3.session.SessionResult>?=null
   main {result=c.sendCustomCommand(androidx.media3.session.SessionCommand("timer.set",android.os.Bundle.EMPTY),android.os.Bundle().apply{putLong("deadline",System.currentTimeMillis()+800)})}
   assertEquals(androidx.media3.session.SessionResult.RESULT_SUCCESS,result!!.get(5,TimeUnit.SECONDS).resultCode)
   waitFor{!c.isPlaying&&c.sessionExtras.getLong("sleep_deadline")==0L}
  } finally {audioManager.abandonAudioFocusRequest(request);main{c.stop();c.clearMediaItems();c.release();PlayerHub.release()};audio.delete()}
 }

}
