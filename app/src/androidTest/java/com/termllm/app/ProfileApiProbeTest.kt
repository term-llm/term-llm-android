package com.termllm.app

import android.util.Log
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.junit.Test
import org.junit.runner.RunWith

/** Diagnostic probe: what does this device's WebView actually support? */
@RunWith(AndroidJUnit4::class)
class ProfileApiProbeTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun log(msg: String) = Log.i("TermLLMProbe", msg)

    @Test
    fun probeProfileApis() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            log("MULTI_PROFILE=" + WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE))
            log("provider=" + WebView.getCurrentWebViewPackage()?.versionName)
            val store = ProfileStore.getInstance()
            log("names_before=" + runCatching { store.allProfileNames }.getOrNull())
            val created = runCatching { store.getOrCreateProfile("src-probe-a") }
            log("getOrCreate=" + created.isSuccess + " err=" + created.exceptionOrNull()?.message)
            log("profileName=" + runCatching { created.getOrNull()?.name }.getOrNull())
            log("getProfile=" + runCatching { store.getProfile("src-probe-a")?.name }.getOrNull())
            val view = WebView(context)
            val set = runCatching { WebViewCompat.setProfile(view, "src-probe-a") }
            log("setProfile ok=" + set.isSuccess + " err=" + set.exceptionOrNull()?.message)
            log("viewProfile=" + runCatching { WebViewCompat.getProfile(view)?.name }.getOrNull())
            log("names_after=" + runCatching { store.allProfileNames }.getOrNull())
        }
    }
}
