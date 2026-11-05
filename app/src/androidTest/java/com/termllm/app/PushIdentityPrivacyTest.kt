package com.termllm.app
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.termllm.app.push.PushIdentity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
@RunWith(AndroidJUnit4::class)
class PushIdentityPrivacyTest {
 @Test fun newInstallIdsAreRandomAndPersistWithoutAHardwareIdentifier() {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val dirs=(1..2).map {File(context.cacheDir,"identity-privacy-"+it+"-"+System.nanoTime()).apply {mkdirs()}}
  try {
   fun identity(dir:File)=PushIdentity(object:ContextWrapper(context){override fun getFilesDir()=dir})
   val a=identity(dirs[0]);val b=identity(dirs[1])
   val one=a.ensure("fixture");val two=b.ensure("fixture")
   assertTrue("Separate installations must not share an Android-derived window id",one.windowId!=two.windowId)
   assertEquals(one.windowId,identity(dirs[0]).windowId)
   assertEquals(two.windowId,identity(dirs[1]).windowId)
  } finally {dirs.forEach {it.deleteRecursively()}}
 }
}
