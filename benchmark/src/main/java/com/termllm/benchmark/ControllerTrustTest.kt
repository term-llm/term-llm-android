package com.termllm.benchmark
import android.content.ComponentName
import android.content.pm.ApplicationInfo
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
/** A REAL second Android UID, not a mocked ControllerInfo.isTrusted flag. */
@RunWith(AndroidJUnit4::class)
class ControllerTrustTest {
 @Test fun unrelatedUidCannotConnectToThePrimaryPlaybackSession() {
  val instrumentation=InstrumentationRegistry.getInstrumentation()
  val context=instrumentation.context
  val primary=context.packageManager.getApplicationInfo("com.termllm.app",0)
  assertTrue("Only run against the owned DEBUG primary fixture",primary.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
  assertTrue("This regression requires a genuinely different UID",primary.uid != context.applicationInfo.uid)
  UiDevice.getInstance(instrumentation).executeShellCommand("am start -W -n com.termllm.app/.MainActivity")
  val future=MediaController.Builder(context,SessionToken(context,ComponentName("com.termllm.app","com.termllm.app.listen.ListenService"))).buildAsync()
  try {
   future.get(10,TimeUnit.SECONDS)
   fail("An unrelated untrusted UID must not acquire a controller capable of injecting media")
  } catch(expected:ExecutionException) {
   assertTrue("The session must reject authorization, not time out",expected.cause is SecurityException)
  } finally {instrumentation.runOnMainSync {MediaController.releaseFuture(future)}}
 }
}
