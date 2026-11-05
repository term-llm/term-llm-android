package com.termllm.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.termllm.app.push.PushCenter
import com.termllm.app.push.PushNotifications
import com.termllm.app.ui.AppRoot
import com.termllm.app.ui.AppViewModel
import com.termllm.app.ui.TermLlmTheme
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class MainActivity : ComponentActivity() {

    /** A push tap's target, handed to the Compose tree once it exists. */
    private var pendingDeepLink by mutableStateOf<String?>(null)

    /**
     * The saved connection id the tapped notification came from. Root resolves the
     * link against *that* connection's origin/mount; there is deliberately no
     * singleton "active route" involved, because the route of a notification is a
     * property of the notification, not of whatever page happened to be open.
     */
    private var pendingPushSource by mutableStateOf<String?>(null)

    /** `termllm-auth://callback?...` from the system browser after a passkey ceremony. */
    private var pendingAuthCallback by mutableStateOf<String?>(null)

    /** `termllm://connect?server=…` pairing link or `termllm://voice` shortcut. */
    private var pendingAppLink by mutableStateOf<String?>(null)

    /** The in-flight POST_NOTIFICATIONS prompt, if a page asked for one. */
    private var permissionContinuation: CancellableContinuation<Boolean>? = null

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val continuation = permissionContinuation
            permissionContinuation = null
            if (continuation != null && continuation.isActive) continuation.resume(granted)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The pipeline reads and writes its route table, so it initializes on IO; the
        // Compose root can wait for PushCenter.awaitReady before trusting its state.
        PushCenter.initAsync(this)
        // The page (`requestPermission`) has no Activity; this is the only place that
        // may show the Android 13 prompt, and it is asked for on the main thread.
        PushCenter.permissionRequester = { requestNotificationPermission() }
        consumeNotificationIntent(intent)
        consumeLinkIntent(intent)
        setContent {
            TermLlmTheme {
                val viewModel: AppViewModel = viewModel()
                AppRoot(
                    vm = viewModel,
                    deepLink = pendingDeepLink,
                    deepLinkSourceId = pendingPushSource,
                    authCallback = pendingAuthCallback,
                    onAuthCallbackHandled = { pendingAuthCallback = null },
                    appLink = pendingAppLink,
                    onAppLinkHandled = { pendingAppLink = null },
                    onDeepLinkHandled = { _ ->
                        // Opening a notification is not acknowledgement: the relay already
                        // had the message ACKed on delivery, and unread state stays with the
                        // Attention store. Nothing about the link is logged: it names a
                        // conversation on the user's server.
                        pendingDeepLink = null
                        pendingPushSource = null
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeNotificationIntent(intent)
        consumeLinkIntent(intent)
    }

    override fun onDestroy() {
        permissionContinuation?.takeIf { it.isActive }?.resume(false)
        permissionContinuation = null
        PushCenter.permissionRequester = null
        super.onDestroy()
    }

    /**
     * Take a push tap out of the intent exactly once. The extras are removed as they
     * are read, so an activity recreate (or an intent the system delivers twice) can
     * never replay a conversation the user already opened.
     */
    private fun consumeNotificationIntent(intent: Intent?) {
        if (intent == null) return
        intent.getStringExtra(PushNotifications.EXTRA_DEEP_LINK)?.let { pendingDeepLink = it }
        intent.getStringExtra(PushNotifications.EXTRA_ROUTE)?.let { pendingPushSource = it }
        intent.removeExtra(PushNotifications.EXTRA_DEEP_LINK)
        intent.removeExtra(PushNotifications.EXTRA_ROUTE)
        intent.removeExtra(PushNotifications.EXTRA_EVENT)
        intent.removeExtra(PushNotifications.EXTRA_SESSION)
    }

    /** Same one-shot rule for the pairing link and the passkey callback URI. */
    private fun consumeLinkIntent(intent: Intent?) {
        if (intent == null) return
        val data = intent.dataString ?: return
        when {
            data.startsWith("termllm-auth:") -> {
                pendingAuthCallback = data
                intent.data = null
            }
            data.startsWith("termllm:") -> {
                pendingAppLink = data
                intent.data = null
            }
        }
    }

    /**
     * Show the POST_NOTIFICATIONS prompt and report the answer. Only the system
     * dialog is authoritative: a user who denied permanently gets `false` here and
     * Settings then offers the system-settings route instead of silently doing
     * nothing.
     */
    private suspend fun requestNotificationPermission(): Boolean = withContext(Dispatchers.Main) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@withContext true
        if (PushNotifications.permissionGranted(this@MainActivity)) return@withContext true
        if (permissionContinuation != null) return@withContext false
        return@withContext suspendCancellableCoroutine<Boolean> { continuation ->
            permissionContinuation = continuation
            continuation.invokeOnCancellation {
                if (permissionContinuation === continuation) permissionContinuation = null
            }
            PushNotifications.markPromptRequested(this@MainActivity)
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
