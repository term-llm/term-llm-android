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
    val body=if(second) "<h1>Second back fixture</h1>" else "<h1>First back fixture</h1><a href='/ui/two'>Open second fixture</a>"
    return okhttp3.mockwebserver.MockResponse().setHeader("Content-Type","text/html").setBody("<html><body>$body</body></html>")
   }
  }
  server.start()
  val fixtureUrl=server.url("/ui/")
  val device=androidx.test.uiautomator.UiDevice.getInstance(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation())
  val vm=androidx.lifecycle.ViewModelProvider(rule.activity)[com.termllm.app.ui.AppViewModel::class.java]
  var source:com.termllm.app.data.Connection?=null
  try {
   rule.runOnIdle {
    source=vm.addConnection("Back QA fixture",com.termllm.app.ui.ProbeResult(fixtureUrl,com.termllm.app.model.ServerRole.DIRECT_NODE),null)
    vm.openChat(source!!.id,"/ui/one")
   }
   tab("Chat").performClick();rule.waitForIdle()
   fun visible(text:String) = device.wait(androidx.test.uiautomator.Until.hasObject(androidx.test.uiautomator.By.text(text)),10000)
   org.junit.Assert.assertTrue(visible("First back fixture"))
   device.findObject(androidx.test.uiautomator.By.text("Open second fixture")).click()
   org.junit.Assert.assertTrue(visible("Second back fixture"));rule.waitForIdle()
   device.pressBack()
   org.junit.Assert.assertTrue("System Back must navigate web history, not leave Chat",visible("First back fixture"))
   device.findObject(androidx.test.uiautomator.By.text("Open second fixture")).click()
   org.junit.Assert.assertTrue(visible("Second back fixture"));rule.waitForIdle()
   // Real edge gesture, derived from current display rather than fixed app coordinates.
   device.swipe(1,device.displayHeight/2,device.displayWidth/2,device.displayHeight/2,30)
   org.junit.Assert.assertTrue("Committed predictive gesture must navigate web history",visible("First back fixture"))
   tab("Chat").assertIsSelected()
  } finally {
   source?.let {connection -> rule.runOnIdle {vm.removeConnection(connection.id)}}
   server.shutdown()
  }
 }

}
