package com.termllm.app.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.termllm.app.BuildConfig
import com.termllm.app.data.ServerAddress
import com.termllm.app.data.ServerKind
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Chat is the server's own term-llm web UI inside a native shell, exactly as on
 * iOS. Authentication handoff mirrors HubConnectionSecurity.directBearerScript
 * and HubClient.login_bearer:
 *
 *  - Hub: a scoped `term_llm_hub_token` cookie at the configured mount.
 *  - Direct node: a document-start script that writes the frontend token
 *    (`localStorage.term_llm_token` + a mount-scoped cookie) only on the exact
 *    origin and mount. The page reads localStorage to authorize mutations.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: AppViewModel,
    holder: ChatDocumentHolder,
    connectionId: String,
    path: String,
    onBack: () -> Unit,
) {
    val connection = vm.connection(connectionId)
    val token = remember(connectionId) { vm.token(connectionId) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<String?>(null) }
    var capturing by remember { mutableStateOf(holder.capturing) }
    androidx.activity.compose.PredictiveBackHandler { progress ->
        try {
            progress.collect { /* Commit web navigation only when predictive gesture completes. */ }
            // Decide at commit time: SPA history may change without a load callback.
            if (holder.webView?.canGoBack() == true) holder.webView?.goBack() else onBack()
        } catch (_: kotlinx.coroutines.CancellationException) { /* Cancelled gesture leaves the document intact. */ }
    }

    if (connection == null) {
        Scaffold(topBar = { TopAppBar(title = { Text("Chat") }) }) { padding ->
            Surface(Modifier.fillMaxSize().padding(padding)) {
                EmptyState(
                    title = "Source removed",
                    body = "This conversation belongs to a source that is no longer saved, so its document was closed."
                )
            }
        }
        return
    }

    val target = remember(connectionId, path) { vm.chatUrl(connectionId, path) }

    DisposableEffect(holder, connectionId) {
        val listener = object : ChatDocumentHolder.Listener {
            override fun onLoading() {
                loading = true
                failed = null
            }

            override fun onLocationChanged(path: String?, canGoBack: Boolean) {
                if (path != null && holder.loadedConnectionId == connectionId) {
                    vm.openChat(connectionId, path)
                }
            }

            override fun onLoaded() {
                loading = false
                if (BuildConfig.DEBUG && !token.isNullOrEmpty()) {
                    holder.webView?.evaluateJavascript(
                        "JSON.stringify({token: localStorage.getItem('term_llm_token') !== null," +
                            "cookie: document.cookie.indexOf('term_llm_token') >= 0})"
                    ) { result -> android.util.Log.d("TermLLM", "chat handoff " + result) }
                }
            }

            override fun onError(message: String) {
                loading = false
                failed = message
            }
        }
        holder.listener = listener
        holder.onCaptureChange = { capturing = it }
        onDispose { if (holder.listener === listener) holder.listener = null }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(connection.name, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Chat · server web UI",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { if (holder.webView?.canGoBack() == true) holder.webView?.goBack() else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        failed = null
                        holder.reload(connection, path, token)
                    }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Reload")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (capturing) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Live voice: this page is using the microphone. Starting music or a podcast ends the call.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
            (failed ?: holder.lastError)?.let { message ->
                Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            failed = null
                            holder.reload(connection, path, token)
                        }) { Text("Retry") }
                    }
                }
            }
            if (target == null) {
                Surface(Modifier.fillMaxSize()) {
                    EmptyState(
                        title = "Nothing to open",
                        body = "This conversation link is outside the configured server mount, so the app will not load it."
                    )
                }
            } else {
                // The document is retained across tab switches: AndroidView only
                // re-parents the existing WebView and a navigation happens when the
                // destination actually changes (or on explicit reload).
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context -> android.widget.FrameLayout(context) },
                    update = { container ->
                        // A history callback may already have advanced the destination
                        // while this composition still carries the previous path. Never
                        // reload a live SPA from a stale AndroidView update lambda.
                        val latest = vm.chatTarget.value
                        if (latest?.first == connectionId) holder.attach(container, connection, latest.second, token)
                    }
                )
            }
        }
    }

}

/** Port of HubConnectionSecurity.directBearerScript. */
internal fun directBearerScript(token: String, origin: String, cookiePath: String): String {
    val mount = cookiePath.trimEnd('/')
    fun json(value: String) = Wire_jsonQuote(value)
    return """
        (() => {
          if (location.origin !== ${json(origin)}) return;
          const mount = ${json(mount)};
          if (!(location.pathname === mount.slice(0, -1) || location.pathname.startsWith(mount + '/'))) return;
          localStorage.setItem('term_llm_token', ${json(token)});
          document.cookie = 'term_llm_token=' + encodeURIComponent(${json(token)}) + '; path=' + mount + '/; SameSite=Strict' + (location.protocol === 'https:' ? '; Secure' : '');
        })();
    """.trimIndent()
}

internal fun Wire_jsonQuote(value: String): String {
    val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
    return "\"" + escaped + "\""
}
