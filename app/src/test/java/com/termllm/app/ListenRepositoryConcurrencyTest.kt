package com.termllm.app
import com.termllm.app.listen.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
class ListenRepositoryConcurrencyTest {
 @Test fun concurrentWritersShareOneSerializedRepositoryAndPersistAllMutations() {
  val dir=Files.createTempDirectory("queue-concurrent").toFile()
  try {
   val ui=ListenStore(dir); val service=ListenStore(dir); val downloader=ListenStore(dir)
   ui.add(ListenItem("playing","https://example.org/a.mp3","A"))
   val pool=Executors.newFixedThreadPool(4)
   repeat(80) { i -> pool.submit { ui.add(ListenItem("id-$i","https://example.org/$i.mp3","Track $i")) } }
   repeat(80) { i -> pool.submit { service.updatePosition("playing",i.toLong(),1000) } }
   pool.submit { downloader.markDownloaded("playing","/saved/a","etag") }
   pool.shutdown(); assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS))
   ui.remove("id-4"); downloader.markDownloaded("id-4","/must-not-resurrect")
   assertEquals(80,ui.items().size); assertTrue(service.item("playing")!!.downloaded)
   ui.flushBlocking(); ListenStore.forgetForTest(dir)
   val restarted=ListenStore(dir)
   assertEquals(80,restarted.items().size); assertNull(restarted.item("id-4"))
   assertTrue(restarted.item("playing")!!.downloaded); assertEquals("etag",restarted.item("playing")!!.etag)
   assertNull(restarted.next("music-not-a-podcast")); assertNull(restarted.previous("music-not-a-podcast"))
   restarted.flushBlocking(); ListenStore.forgetForTest(dir)
  } finally { dir.deleteRecursively() }
 }
}
