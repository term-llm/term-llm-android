package com.termllm.app
import com.termllm.app.music.PlaylistImport
import org.junit.Assert.*
import org.junit.Test
class PlaylistImportTest {
 @Test fun acceptsSmallUtf8AndRejectsOversizedInput() {
  assertEquals("#EXTM3U\n",PlaylistImport.read("#EXTM3U\n".byteInputStream()))
  try {PlaylistImport.read(ByteArray(PlaylistImport.MAX_BYTES+1).inputStream());fail("Oversize accepted")}catch(_:IllegalArgumentException){}
 }
}
