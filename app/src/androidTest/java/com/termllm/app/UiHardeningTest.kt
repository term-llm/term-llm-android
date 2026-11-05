package com.termllm.app
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class UiHardeningTest {
 @get:Rule val rule=createAndroidComposeRule<MainActivity>()
 private fun tab(text:String)=rule.onNode(hasText(text) and isSelectable())
 @Test fun musicQueryAndDestinationSurviveRealActivityRecreation() {
  tab("Music").performClick();rule.waitForIdle()
  val granted=rule.onAllNodesWithText("Search this library").fetchSemanticsNodes().isNotEmpty()
  org.junit.Assume.assumeTrue("Grant READ_MEDIA_AUDIO in test setup",granted)
  rule.onNode(hasSetTextAction()).performTextReplacement("Harbour")
  rule.activityRule.scenario.recreate();rule.waitForIdle()
  rule.onNode(hasSetTextAction()).assertTextContains("Harbour")
  tab("Music").assertIsSelected()
 }
 @SdkSuppress(minSdkVersion=34)
 @Test fun listenActionsPassAutomatedAccessibilityChecks() {
  rule.enableAccessibilityChecks()
  tab("Listen").performClick();rule.waitForIdle()
  rule.onNodeWithContentDescription("Seek back 15 seconds").assertHasClickAction()
  rule.onNodeWithContentDescription("Seek forward 30 seconds").assertHasClickAction()
  rule.onNodeWithContentDescription("Playback position").assertExists()
  rule.onNodeWithText("3×").assertIsDisplayed()
 }
 @Test fun realWebHistoryHandlesSystemAndPredictiveBack() {
  val server=okhttp3.mockwebserver.MockWebServer()
  server.dispatcher=object:okhttp3.mockwebserver.Dispatcher() {
   override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest):okhttp3.mockwebserver.MockResponse {
    val second=request.path?.startsWith("/ui/two")==true
    val body=if(second) "<h1>Second back fixture</h1>" else """<h1>First back fixture</h1><a href='/ui/two'>Open second fixture</a>
     <button onclick="history.pushState({},'', '/ui/spa');document.getElementById('spa').textContent='SPA current fixture'">Push SPA history</button>
     <p id='spa'>SPA initial fixture</p><script>addEventListener('popstate',()=>document.getElementById('spa').textContent='SPA initial fixture')</script>"""
    return okhttp3.mockwebserver.MockResponse().setHeader("Content-Type","text/html").setBody("<html><body>$body</body></html>")
   }
  }
  server.start()
  val fixtureUrl=server.url("/ui/")
  val device=androidx.test.uiautomator.UiDevice.getInstance(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation())
  val config=androidx.test.uiautomator.Configurator.getInstance()
  val oldIdle=config.waitForIdleTimeout
  config.waitForIdleTimeout=0 // Compose owns frame/idleness; do not block its clock in UiDevice queries.
  val vm=androidx.lifecycle.ViewModelProvider(rule.activity)[com.termllm.app.ui.AppViewModel::class.java]
  var source:com.termllm.app.data.Connection?=null
  var secondary:com.termllm.app.data.Connection?=null
  try {
   rule.runOnIdle {
    source=vm.addConnection("Back QA fixture",com.termllm.app.ui.ProbeResult(fixtureUrl,com.termllm.app.model.ServerRole.DIRECT_NODE),null)
    vm.openChat(source!!.id,"/ui/one")
   }
   tab("Chat").performClick();rule.waitForIdle()
   // Assert the REAL attached Chromium document. UIAutomator's virtual-node
   // cache is unreliable across profile/SPA changes in a Compose test clock;
   // native Back/edge gestures still enter through the actual system dispatcher.
   fun findWeb(view:android.view.View):android.webkit.WebView? {
    if(view is android.webkit.WebView)return view
    if(view is android.view.ViewGroup)for(i in view.childCount-1 downTo 0) {
     findWeb(view.getChildAt(i))?.let {return it}
    }
    return null
   }
   fun js(expression:String):String {
    val latch=java.util.concurrent.CountDownLatch(1);var value="null"
    rule.runOnUiThread {
     val web=findWeb(rule.activity.window.decorView)
     if(web==null)latch.countDown() else web.evaluateJavascript(expression){value=it?:"null";latch.countDown()}
    }
    org.junit.Assert.assertTrue("Real WebView evaluation must answer",latch.await(5,java.util.concurrent.TimeUnit.SECONDS))
    return value
   }
   fun requireWebBack() {
    var canBack=false;var size=0;var index=0
    rule.runOnUiThread {findWeb(rule.activity.window.decorView)?.let {canBack=it.canGoBack();val h=it.copyBackForwardList();size=h.size;index=h.currentIndex}}
    org.junit.Assert.assertTrue("Real history must have a previous entry: size=$size index=$index",canBack)
   }
   fun visible(text:String):Boolean = try {
    val quoted=org.json.JSONObject.quote(text)
    rule.waitUntil(10000) {js("!!document.body && document.body.innerText.includes("+quoted+")")=="true"};true
   } catch(_:androidx.compose.ui.test.ComposeTimeoutException) {false}
   fun click(text:String) {
    org.junit.Assert.assertTrue(text,visible(text))
    val quoted=org.json.JSONObject.quote(text)
    val bounds=org.json.JSONArray(js("(()=>{const el=[...document.querySelectorAll('a,button')].find(e=>e.textContent==="+quoted+");el.scrollIntoView({block:'center'});const r=el.getBoundingClientRect();return [r.left+r.width/2,r.top+r.height/2,innerWidth]})()"))
    val origin=IntArray(2);var scale=1.0
    rule.runOnUiThread {val web=findWeb(rule.activity.window.decorView)!!;web.getLocationOnScreen(origin);scale=web.width/bounds.getDouble(2)}
    // Trusted touch at the real DOM element bounds. A JS .click() lacks user
    // activation, so Chromium intentionally makes that history entry skippable.
    device.click(origin[0]+(bounds.getDouble(0)*scale).toInt(),origin[1]+(bounds.getDouble(1)*scale).toInt())
   }
   org.junit.Assert.assertTrue(visible("First back fixture"))
   click("Open second fixture")
   org.junit.Assert.assertTrue(visible("Second back fixture"));rule.waitUntil(10000) {vm.chatTarget.value?.second == "/ui/two"};rule.waitForIdle()
   requireWebBack();device.pressBack()
   org.junit.Assert.assertTrue("System Back must navigate web history, not leave Chat",visible("First back fixture"))
   rule.waitUntil(10000) {vm.chatTarget.value?.second == "/ui/one"};rule.waitForIdle()
   click("Open second fixture")
   org.junit.Assert.assertTrue(visible("Second back fixture"));rule.waitUntil(10000) {vm.chatTarget.value?.second == "/ui/two"};rule.waitForIdle()
   // Real edge gesture, derived from current display rather than fixed app coordinates.
   device.swipe(1,device.displayHeight/2,device.displayWidth/2,device.displayHeight/2,30)
   org.junit.Assert.assertTrue("Committed predictive gesture must navigate web history",visible("First back fixture"))
   rule.waitUntil(10000) {vm.chatTarget.value?.second == "/ui/one"};rule.waitForIdle()
   tab("Chat").assertIsSelected()
   click("Push SPA history")
   org.junit.Assert.assertTrue("SPA navigation must not reload the retained document",visible("SPA current fixture"))
   rule.waitUntil(10000) { vm.chatTarget.value?.second == "/ui/spa" }
   rule.waitForIdle()
   requireWebBack();device.pressBack()
   rule.waitUntil(10000) { vm.chatTarget.value?.second == "/ui/one" };rule.waitForIdle()
   org.junit.Assert.assertTrue("Back must pop pushState history, not leave Chat",visible("SPA initial fixture"))
   tab("Chat").assertIsSelected()
   // The listener must be rebound after a source/profile switch as well.
   rule.runOnIdle {
    secondary=vm.addConnection("Back QA second source",com.termllm.app.ui.ProbeResult(fixtureUrl,com.termllm.app.model.ServerRole.DIRECT_NODE),null)
    vm.openChat(secondary!!.id,"/ui/one")
   }
   rule.waitForIdle();click("Push SPA history")
   org.junit.Assert.assertTrue(visible("SPA current fixture"))
   rule.waitUntil(10000) {vm.chatTarget.value == (secondary!!.id to "/ui/spa")}
   rule.waitForIdle();requireWebBack();device.pressBack()
   rule.waitUntil(10000) {vm.chatTarget.value == (secondary!!.id to "/ui/one")};rule.waitForIdle()
   org.junit.Assert.assertTrue(visible("SPA initial fixture"))
   device.pressBack();rule.waitForIdle()
   tab("Agents").assertIsSelected()
  } catch (failure:Throwable) {
   val cache=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
   device.dumpWindowHierarchy(java.io.File(cache,"history-failure.xml"))
   device.takeScreenshot(java.io.File(cache,"history-failure.png"))
   throw failure
  } finally {
   config.waitForIdleTimeout=oldIdle
   secondary?.let {connection -> rule.runOnIdle {vm.removeConnection(connection.id)}}
   source?.let {connection -> rule.runOnIdle {vm.removeConnection(connection.id)}}
   server.shutdown()
  }
 }

}
