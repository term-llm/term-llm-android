package com.termllm.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.termllm.app.tools.DeviceTools
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Built-in device tools against the real device: values must come from the
 * platform (never constants), ping echoes only strings, and unknown names fail
 * loudly so the page sees a tool error rather than a silent success.
 */
@RunWith(AndroidJUnit4::class)
class DeviceToolsTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun tools(foreground: Boolean = true) = DeviceTools(
        context = context,
        appVersion = "test-version",
        foreground = { foreground },
        now = { 1_760_000_000_000L },
    )

    @Test
    fun pingStampsTimeAndEchoesMessage() {
        val reply = JSONObject(tools().handle("ping", """{"message":"hello"}"""))
        assertEquals(
            "pong from term-llm Android app at 2025-10-09T08:53:20Z\necho: hello",
            reply.getString("text"),
        )
    }

    @Test
    fun pingWithoutMessageHasNoEcho() {
        val reply = JSONObject(tools().handle("ping", "{}"))
        assertEquals("pong from term-llm Android app at 2025-10-09T08:53:20Z", reply.getString("text"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun pingRejectsNonStringMessage() {
        tools().handle("ping", """{"message":42}""")
    }

    @Test
    fun deviceInfoReportsPlatformFacts() {
        val info = JSONObject(tools(foreground = true).handle("device_info", "{}"))
        assertTrue(info.getString("model").contains(android.os.Build.MODEL))
        assertTrue(info.getString("system").startsWith("Android " + android.os.Build.VERSION.RELEASE))
        assertEquals("test-version", info.getString("app_version"))
        assertTrue(
            listOf("charging", "full", "unplugged", "unknown").contains(info.getString("battery_state"))
        )
        assertEquals("active", info.getString("app_state"))
        info.optInt("battery_percent", -1).let { percent ->
            assertTrue("battery percent out of range: " + percent, percent == -1 || percent in 0..100)
        }
    }

    @Test
    fun deviceInfoMarksBackgroundWhenNotForeground() {
        val info = JSONObject(tools(foreground = false).handle("device_info", "{}"))
        assertEquals("background", info.getString("app_state"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownToolFails() {
        tools().handle("media_add", "{}")
    }
}
