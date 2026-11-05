package com.termllm.app

import com.termllm.app.tools.DeviceToolSharing
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Consent contract for device tools: off until the user turns it on (iOS
 * `DeviceToolsSettings.isEnabled` defaults to false), a saved source gets nothing
 * unless it was granted, and a corrupt file can never grant anything.
 */
class DeviceToolSharingTest {

    private lateinit var file: File
    private lateinit var sharing: DeviceToolSharing

    @Before
    fun setUp() {
        file = File(System.getProperty("java.io.tmpdir"), "device-tools-" + System.nanoTime() + ".json")
        sharing = DeviceToolSharing(file)
    }

    @After
    fun tearDown() {
        file.delete()
    }

    @Test
    fun nothingIsSharedUntilTheUserOptsIn() {
        assertFalse(sharing.isGloballyEnabled())
        assertFalse(sharing.isShared("any-source"))
        assertEquals(emptySet<String>(), sharing.grantedSources())
    }

    @Test
    fun aGrantNeedsBothTheMasterSwitchAndTheSource() {
        sharing.grant("source-a")
        assertFalse("grant alone must not share", sharing.isShared("source-a"))
        sharing.setEnabled(true)
        assertTrue(sharing.isShared("source-a"))
        assertFalse("other sources stay untouched", sharing.isShared("source-b"))
    }

    @Test
    fun turningTheMasterSwitchOffSuspendsEveryGrant() {
        sharing.setEnabled(true)
        sharing.grant("source-a")
        sharing.setEnabled(false)
        assertFalse(sharing.isShared("source-a"))
        // The grant is remembered, so it resumes only if the user turns it back on.
        sharing.setEnabled(true)
        assertTrue(sharing.isShared("source-a"))
    }

    @Test
    fun revokeAndForgetRemoveTheGrant() {
        sharing.setEnabled(true)
        sharing.grant("source-a")
        sharing.grant("source-b")
        sharing.revoke("source-a")
        assertFalse(sharing.isShared("source-a"))
        assertTrue(sharing.isShared("source-b"))
        sharing.forget("source-b")
        assertTrue(sharing.grantedSources().isEmpty())
    }

    @Test
    fun stateSurvivesAReopen() {
        sharing.setEnabled(true)
        sharing.grant("source-a")
        val reopened = DeviceToolSharing(file)
        assertTrue(reopened.isGloballyEnabled())
        assertTrue(reopened.isShared("source-a"))
    }

    @Test
    fun anUnreadableFileGrantsNothing() {
        file.writeText("{not json")
        assertFalse(sharing.isGloballyEnabled())
        assertFalse(sharing.isShared("source-a"))
        assertTrue(sharing.grantedSources().isEmpty())
    }
}
