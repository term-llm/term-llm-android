package com.termllm.app
/** Debug-only deterministic SAF-like slow provider, never in release. */
class SlowPlaylistProvider : android.content.ContentProvider() {
 override fun onCreate()=true
 override fun getType(uri:android.net.Uri)="audio/x-mpegurl"
 override fun query(uri:android.net.Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):android.database.Cursor =
  android.database.MatrixCursor(arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf("Slow playlist.m3u8")) }
 override fun insert(uri:android.net.Uri,values:android.content.ContentValues?):android.net.Uri?=null
 override fun delete(uri:android.net.Uri,selection:String?,selectionArgs:Array<out String>?)=0
 override fun update(uri:android.net.Uri,values:android.content.ContentValues?,selection:String?,selectionArgs:Array<out String>?)=0
 override fun openFile(uri:android.net.Uri,mode:String):android.os.ParcelFileDescriptor {
  require(mode=="r")
  val pipe=android.os.ParcelFileDescriptor.createPipe()
  Thread {android.os.ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use {Thread.sleep(1500);it.write("#EXTM3U\n#PLAYLIST:Slow fixture\nhttps://example.org/demo.mp3\n".toByteArray())}}.start()
  return pipe[0]
 }
}
