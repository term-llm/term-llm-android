package com.termllm.app

import com.termllm.app.push.PushPermissionPolicy
import com.termllm.app.push.PushPermissionPolicy.Permission
import com.termllm.app.push.PushPermissionPolicy.ToggleAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Notification permission UX policy (hardening row 6). Before this, the Settings
 * toggle only stored a preference: it never asked for POST_NOTIFICATIONS, so on
 * Android 13+ "on" could mean nothing at all. These tests pin the four distinct
 * states and the recovery action for each.
 */
class PushPermissionPolicyTest {

    private fun view(
        sdk: Int,
        granted: Boolean,
        canAskAgain: Boolean,
        channelOn: Boolean = true,
        preference: Boolean = false,
    ) = PushPermissionPolicy.classify(sdk, granted, canAskAgain, channelOn, preference)

    @Test
    fun androidTwelveNeedsNoRuntimePermission() {
        val off = view(sdk = 31, granted = false, canAskAgain = false)
        assertEquals(Permission.NOT_REQUIRED, off.permission)
        assertEquals("Android 12 has no runtime permission to ask for", ToggleAction.ENABLE, off.toggleAction)
        val on = view(sdk = 31, granted = false, canAskAgain = false, preference = true)
        assertTrue(on.canDeliver)
        assertFalse(on.needsAppSettings)
        assertFalse(on.needsChannelSettings)
    }

    @Test
    fun theFirstAndroidThirteenToggleAsksTheOs() {
        val off = view(sdk = 33, granted = false, canAskAgain = true)
        assertEquals(Permission.DENIED_CAN_ASK, off.permission)
        assertEquals("the toggle itself must request POST_NOTIFICATIONS", ToggleAction.REQUEST_PERMISSION, off.toggleAction)
        assertFalse("a prompt is not a system-settings trip", off.needsAppSettings)
        assertFalse("a denied permission is never reported as delivering", off.canDeliver)
    }

    @Test
    fun permanentDenialSendsTheUserToSystemSettings() {
        val off = view(sdk = 34, granted = false, canAskAgain = false)
        assertEquals(Permission.DENIED_PERMANENT, off.permission)
        assertEquals(ToggleAction.OPEN_APP_SETTINGS, off.toggleAction)
        assertTrue(off.needsAppSettings)
        assertFalse("asking again would be a no-op", off.canAsk)
    }

    @Test
    fun grantedButChannelOffIsASeparateState() {
        val view = view(sdk = 34, granted = true, canAskAgain = false, channelOn = false, preference = true)
        assertEquals(Permission.GRANTED, view.permission)
        assertEquals(ToggleAction.ENABLE, view.toggleAction)
        assertTrue("only the channel can be fixed here", view.needsChannelSettings)
        assertFalse(view.needsAppSettings)
        assertFalse("a silenced channel is not delivery", view.canDeliver)
    }

    @Test
    fun thePreferenceAloneNeverClaimsDelivery() {
        val off = view(sdk = 33, granted = true, canAskAgain = false, preference = false)
        assertFalse(off.canDeliver)
        assertTrue(off.summary.isNotBlank())

        val ready = view(sdk = 33, granted = true, canAskAgain = false, preference = true)
        assertTrue(ready.canDeliver)
        assertEquals(ToggleAction.ENABLE, ready.toggleAction)
        assertFalse(ready.needsAppSettings)
        assertFalse(ready.needsChannelSettings)
    }

    @Test
    fun theRuntimePermissionBoundaryIsAndroidThirteen() {
        assertEquals(33, PushPermissionPolicy.RUNTIME_PERMISSION_SDK)
        assertEquals(
            Permission.NOT_REQUIRED,
            PushPermissionPolicy.classify(32, granted = false, canAskAgain = false, alertsChannelOn = true, preference = false).permission,
        )
        assertEquals(
            Permission.DENIED_CAN_ASK,
            PushPermissionPolicy.classify(33, granted = false, canAskAgain = true, alertsChannelOn = true, preference = false).permission,
        )
    }
}
