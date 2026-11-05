package com.termllm.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device smoke test for the agreed information architecture:
 * Agents · Chat · Listen · Music tabs, Attention inside Agents, Settings via the
 * Agents gear.
 */
@RunWith(AndroidJUnit4::class)
class AgentsNavigationTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private fun tab(label: String) = rule.onNode(hasText(label) and isSelectable())

    @Test
    fun bottomNavigationOffersTheAgreedDestinations() {
        listOf("Agents", "Chat", "Listen", "Music").forEach { label ->
            tab(label).assertIsDisplayed()
        }
    }

    @Test
    fun agentsWorkspaceOffersAttentionAndSettings() {
        tab("Agents").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Attention", substring = true).assertExists()
        rule.onNodeWithContentDescription("Settings").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("SOURCES").assertExists()
        // Settings is a LazyColumn: with saved sources the About section sits below the
        // fold, so scroll to it instead of assuming a short list.
        rule.onNode(androidx.compose.ui.test.hasScrollAction())
            .performScrollToNode(androidx.compose.ui.test.hasText("ABOUT"))
        rule.onNodeWithText("ABOUT").assertExists()
    }

    @Test
    fun listenTabShowsTheNativePlayerSurface() {
        tab("Listen").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Podcasts & audio").assertIsDisplayed()
        // Every speed stays visible at the device width (no clipped last chip).
        listOf("0.75×", "1.25×", "1.5×", "2×", "3×").forEach {
            rule.onNodeWithText(it).assertIsDisplayed()
        }
        assertTrue(rule.onAllNodes(hasText("1×")).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun musicTabShowsTheDeviceLibraryOrItsPermissionGate() {
        tab("Music").performClick()
        rule.waitForIdle()
        // Either the library is already readable or the screen asks for the
        // permission it needs; both are the real screen, neither is a placeholder.
        val granted = rule.onAllNodesWithText("Search this library").fetchSemanticsNodes().isNotEmpty()
        if (granted) {
            rule.onNodeWithText("Search this library").assertIsDisplayed()
        } else {
            rule.onNodeWithText("Allow media access").assertIsDisplayed()
        }
    }
}
