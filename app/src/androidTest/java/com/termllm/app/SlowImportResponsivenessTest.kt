package com.termllm.app
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class SlowImportResponsivenessTest {
 @Test fun slowProviderIsReadOnIoWhileMainRemainsResponsive() = runBlocking {
  val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
  val job=async(Dispatchers.IO) {context.contentResolver.openInputStream(android.net.Uri.parse("content://"+context.packageName+".slow-playlist/one"))!!.use {com.termllm.app.music.PlaylistImport.read(it)}}
  delay(100)
  val start=System.nanoTime();var heartbeat=false
  instrumentation.runOnMainSync {heartbeat=true}
  assertTrue(heartbeat);assertTrue("UI blocked by provider",(System.nanoTime()-start)/1000000<700)
  assertFalse(job.isCompleted)
  assertTrue(job.await().contains("Slow fixture"))
 }
}
