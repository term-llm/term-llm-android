package com.termllm.app
import android.os.SystemClock
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.termllm.app.data.*
import com.termllm.app.ui.ChatDocumentHolder
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
@RunWith(AndroidJUnit4::class)
class HolderTrustBoundaryTest {
 private fun eval(w:WebView,js:String):String {val l=CountDownLatch(1);var r="null";w.post{w.evaluateJavascript(js){r=it?:"null";l.countDown()}};assertTrue(l.await(10,TimeUnit.SECONDS));return r}
 private fun wait(w:WebView,js:String):String {repeat(70){val r=eval(w,js);if(r!="null"&&r!="false")return r;SystemClock.sleep(100)};error("No page state: $js")}
 @Test fun sameOriginWrongMountAndIframesCannotCallPrivilegedTools() {
  val server=MockWebServer();server.dispatcher=object:Dispatcher(){override fun dispatch(r:RecordedRequest)=MockResponse().setHeader("Content-Type","text/html").setBody(if(r.path=="/ui/frame") "<html><body>Frame</body></html>" else "<html><body>Trust fixture<iframe src='/ui/frame'></iframe></body></html>")};server.start()
  val url=server.url("/ui/").toString();val count=AtomicInteger();var holder:ChatDocumentHolder?=null;var web:WebView?=null
  val scenario=ActivityScenario.launch(BridgeHostActivity::class.java)
  try {
   scenario.onActivity {a->holder=ChatDocumentHolder(a).also{h->h.sharingAllowed={true};h.toolHandler={_,_->count.incrementAndGet();"{}"};web=h.attach(a.container,Connection("trust-"+System.nanoTime(),"Trust",ServerKind.DIRECT_NODE,url),"/ui/index",null)}}
   val w=web!!;wait(w,"!!window.__termLLMDeviceTools")
   wait(w,"!!document.querySelector('iframe').contentWindow.document.body")
   assertEquals("\"undefined\"",eval(w,"typeof document.querySelector('iframe').contentWindow.__termLLMDeviceTools"))
   eval(w,"window.denial='';history.pushState({},'', '/other/');document.modelContext.executeTool('ping',{}).then(()=>denial='ALLOWED',()=>denial='DENIED');true")
   assertEquals("\"DENIED\"",wait(w,"denial || null"));assertEquals(0,count.get())
  } finally {scenario.onActivity{holder?.destroy()};scenario.close();server.shutdown()}
 }
 @Test fun captureDenialAndNavigationRaceCannotGrantMicrophoneOrCamera() {
  val server=MockWebServer();server.dispatcher=object:Dispatcher(){override fun dispatch(r:RecordedRequest)=MockResponse().setHeader("Content-Type","text/html").setBody("<html><body>Capture guard fixture</body></html>")};server.start()
  val source=Connection("capture-"+System.nanoTime(),"Capture fixture",ServerKind.DIRECT_NODE,server.url("/ui/").toString())
  val origin=server.url("/").toString().trimEnd('/')
  val instrumentation=InstrumentationRegistry.getInstrumentation()
  val scenario=ActivityScenario.launch(BridgeHostActivity::class.java)
  var holder:ChatDocumentHolder?=null;var web:WebView?=null
  var answer:((Boolean)->Unit)?=null
  class Request(val kind:String,val from:String=origin):android.webkit.PermissionRequest() {
   var granted=false;var denied=false
   override fun getOrigin()=android.net.Uri.parse(from)
   override fun getResources()=arrayOf(kind)
   override fun grant(resources:Array<out String>){granted=true}
   override fun deny(){denied=true}
  }
  try {
   scenario.onActivity {a->holder=ChatDocumentHolder(a).also {h->
    h.permissionRequester={_,decide->answer=decide};h.capturePrompt={_,_,decide->decide(true)}
    web=h.attach(a.container,source,"/ui/one",null)
   }}
   val w=web!!;wait(w,"document.body.innerText.includes('Capture guard fixture')")
   for(kind in listOf(android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE,android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE)) {
    eval(w,"history.replaceState({},'', '/ui/one');true")
    val denied=Request(kind)
    instrumentation.runOnMainSync {w.webChromeClient!!.onPermissionRequest(denied);answer!!(false)}
    assertTrue("OS denial must reach WebView",denied.denied);assertFalse(denied.granted)
    val race=Request(kind)
    instrumentation.runOnMainSync {w.webChromeClient!!.onPermissionRequest(race)}
    eval(w,"history.replaceState({},'', '/sibling/');true")
    instrumentation.runOnMainSync {answer!!(true)}
    assertTrue("Navigation during a prompt revokes capture",race.denied);assertFalse(race.granted)
    val wrongMount=Request(kind)
    instrumentation.runOnMainSync {w.webChromeClient!!.onPermissionRequest(wrongMount)}
    assertTrue(wrongMount.denied)
    eval(w,"history.replaceState({},'', '/ui/one');true")
    val foreign=Request(kind,"https://other.invalid")
    instrumentation.runOnMainSync {w.webChromeClient!!.onPermissionRequest(foreign)}
    assertTrue(foreign.denied);assertFalse(foreign.granted)
   }
  } finally {scenario.onActivity {holder?.destroy()};scenario.close();server.shutdown()}
 }

}
