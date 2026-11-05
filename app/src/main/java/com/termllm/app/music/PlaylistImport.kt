package com.termllm.app.music
import java.io.InputStream
/** Provider input is untrusted and bounded; caller performs this on Dispatchers.IO. */
object PlaylistImport {
 const val MAX_BYTES = 1024 * 1024
 fun read(input: InputStream): String {
  val output = java.io.ByteArrayOutputStream()
  val buffer=ByteArray(8192)
  while (true) {
   val read=input.read(buffer); if(read<0)break
   require(output.size()+read <= MAX_BYTES) { "Playlist is larger than 1 MiB" }
   output.write(buffer,0,read)
  }
  return output.toString("UTF-8")
 }
}
