package com.termllm.app

import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.termllm.app.data.Connection
import com.termllm.app.data.ServerKind
import com.termllm.app.ui.ChatDocumentHolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Per-source WebView profile isolation, using the reviewer's adversarial shape:
 * two sources on the SAME origin and port, different mount paths, with colliding
 * node/session identifiers.
 */
@RunWith(AndroidJUnit4::class)
class SourceProfileIsolationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val holder = ChatDocumentHolder(context)

    // Same host:port, different mounts -> same origin, different trust identity.
    private val sourceA = Connection(
        id = "aaaaaaaa-1111-2222-3333-444444444444", name = "Work",
        kind = ServerKind.HUB, url = "http://127.0.0.1:8766/work/",
    )
    private val sourceB = Connection(
        id = "bbbbbbbb-1111-2222-3333-444444444444", name = "Home",
        kind = ServerKind.HUB, url = "http://127.0.0.1:8766/home/",
    )

    @Test
    fun multiProfileIsActuallySupportedByThisWebView() {
        val supported = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)
        assertTrue(
            "WebView reports no MULTI_PROFILE support, so per-source isolation cannot be provided",
            supported,
        )
        assertTrue(holder.profilesSupported())
    }

    @Test
    fun sourcesOnSameOriginGetSeparateProfilesAndCookies() {
        val results = mutableMapOf<String, Pair<String, Boolean>>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val viewA = WebView(context)
            holder.load(viewA, sourceA, "/work/node/shared/chat/1", "token-for-work")
            val profileA = WebViewCompat.getProfile(viewA)
            assertNotNull("WebView was not bound to a profile", profileA)
            val nameA = profileA!!.name
            val hasTokenA = profileA.cookieManager.getCookie("http://127.0.0.1:8766/work/")
                ?.contains("token-for-work") == true

            val viewB = WebView(context)
            holder.load(viewB, sourceB, "/home/node/shared/chat/1", "token-for-home")
            val profileB = WebViewCompat.getProfile(viewB)
            assertNotNull(profileB)
            val nameB = profileB!!.name
            val leaksA = profileB.cookieManager.getCookie("http://127.0.0.1:8766/work/")
                ?.contains("token-for-work") == true
            val hasTokenB = profileB.cookieManager.getCookie("http://127.0.0.1:8766/home/")
                ?.contains("token-for-home") == true

            assertNotEquals("both sources landed in one profile", nameA, nameB)
            assertTrue("source A's token cookie missing from its own profile", hasTokenA)
            assertTrue("source B's token cookie missing from its own profile", hasTokenB)
            assertFalse("source A's cookie leaked into source B's profile", leaksA)
            results["a"] = nameA to hasTokenA
            results["b"] = nameB to hasTokenB
        }
        // A -> B -> A: coming back keeps A's profile and its cookie (persistence).
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val viewA2 = WebView(context)
            holder.load(viewA2, sourceA, "/work/node/shared/chat/1", "token-for-work")
            val profileA2 = WebViewCompat.getProfile(viewA2)
            assertEquals(results["a"]!!.first, profileA2!!.name)
            assertTrue(profileA2.cookieManager.getCookie("http://127.0.0.1:8766/work/")
                ?.contains("token-for-work") == true)
        }
    }

    @Test
    fun signOutPurgesOnlyThatSourcesProfile() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val viewA = WebView(context)
            holder.load(viewA, sourceA, "/work/node/shared/chat/1", "token-for-work")
            val viewB = WebView(context)
            holder.load(viewB, sourceB, "/home/node/shared/chat/1", "token-for-home")

            holder.purge(sourceA)

            val profileA = WebViewCompat.getProfile(viewA)
            val profileB = WebViewCompat.getProfile(viewB)
            val aCookies = profileA!!.cookieManager.getCookie("http://127.0.0.1:8766/work/") ?: ""
            val bCookies = profileB!!.cookieManager.getCookie("http://127.0.0.1:8766/home/") ?: ""
            assertFalse("purge did not clear source A's cookie", aCookies.contains("token-for-work"))
            assertTrue("purge of source A also cleared source B", bCookies.contains("token-for-home"))
        }
    }

    @Test
    fun profilesAreNamedPerConnectionAndDeletable() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = WebView(context)
            holder.load(view, sourceA, "/work/node/shared/chat/1", "t")
            val name = WebViewCompat.getProfile(view)!!.name
            assertTrue("profile name should derive from the connection", name.startsWith("src-"))
            assertTrue(ProfileStore.getInstance().getAllProfileNames().contains(name))
        }
    }
}
