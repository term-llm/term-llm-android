package com.termllm.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.webkit.Profile
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.termllm.app.BuildConfig
import com.termllm.app.data.ServerAddress
import com.termllm.app.data.ServerKind
import com.termllm.app.data.Connection
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Retains the actual chat document across tab switches.
 *
 * The iOS app keeps one WKWebView per selected source and never discards it when
 * the user visits another tab, so drafts, scroll position and the live page
 * survive. Re-creating a WebView (or only remembering the URL) is not
 * retention; here a single WebView instance is detached from its Compose
 * container and re-attached on return, and it is only navigated when the
 * destination really changes.
 */
class ChatDocumentHolder(private val context: Context) {

    var webView: WebView? = null
        private set
    var loadedConnectionId: String? = null
        private set
    private var loadedPath: String? = null
    private var loadedUrl: String? = null
    var lastError: String? = null
        private set

    /** Source whose document is currently loaded (used for fail-closed purge). */
    val loadedSource: String? get() = loadedConnectionId

    interface Listener {
        fun onLoading()
        fun onLoaded()
        fun onError(message: String)
        fun onLocationChanged(path: String?, canGoBack: Boolean) {}
    }

    var listener: Listener? = null

    /** Native device-tool handler (media_*); installed once per WebView. */
    var toolHandler: ((String, String) -> String)? = null
    /**
     * Whether this source may use the phone's device tools. The bridge is only
     * installed when it answers true, so a source that was never granted has no
     * `document.modelContext` at all.
     */
    var sharingAllowed: (connectionId: String) -> Boolean = { false }
    /** Session cookie a passkey sign-in redeemed for the source, if any. */
    var sessionCookie: (connectionId: String) -> com.termllm.app.auth.NativeLogin.SessionCookie? = { null }
    /** Whether the page-facing push bridge should exist for this source. */
    var pushAllowed: (connectionId: String) -> Boolean = { false }
    var pushHandler: ((method: String, arguments: String) -> String)? = null
    var pushHandlerFactory: ((connectionId: String, origin: String, mount: String) -> com.termllm.app.push.PushHandler?)? = null
    private var pushInstalledFor: String? = null

    // ---- Live voice (capture policy, hang-up, voice launch) and web-process recovery ----
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    /** Asks the UI for an Android runtime permission (RECORD_AUDIO / CAMERA). */
    var permissionRequester: ((permission: String, decide: (Boolean) -> Unit) -> Unit)? = null
    /** Asks the user whether a page may use the camera / another origin's capture (iOS WebKit prompt). */
    var capturePrompt: ((origin: String, resources: List<String>, decide: (Boolean) -> Unit) -> Unit)? = null
    /** The page started or stopped capturing the microphone/camera (a Live call). */
    var onCaptureChange: ((capturing: Boolean) -> Unit)? = null
    var capturing: Boolean = false
        private set
    private var captureBridgeFor: String? = null
    private var pageLoading = false
    private var pendingVoiceLaunch = false
    private var voiceDeadline = 0L
    var onVoiceLaunchFinished: ((error: String?) -> Unit)? = null
    private val renderGoneAt = ArrayDeque<Long>()
    /** The Compose-owned container the document lives in, so recovery can re-parent a fresh WebView. */
    private var container: ViewGroup? = null
    private var lastConnection: Connection? = null
    private var lastPath: String? = null
    private var lastToken: String? = null
    private var unresponsiveSince: Long? = null
    /** Set when the web process died too often; cleared by an explicit reload. */
    var recoveryBlocked: Boolean = false
        private set
    /** iOS LiveHangUp coordinator: scripts run on the loaded document only. */
    val hangUp = com.termllm.app.chat.LiveHangUp.Coordinator(
        run = { script -> runOnPage(script) },
        schedule = { delayMs, action ->
            val runnable = Runnable { action() }
            mainHandler.postDelayed(runnable, delayMs)
            object : com.termllm.app.chat.LiveHangUp.Coordinator.Cancellable {
                override fun cancel() { mainHandler.removeCallbacks(runnable) }
            }
        },
    )

    private fun runOnPage(script: String) {
        val view = webView ?: return
        if (loadedConnectionId == null) return
        mainHandler.post { runCatching { view.evaluateJavascript(script, null) } }
    }
    /** Origin+key+token marker for the document-start web-storage seed. */
    private var webStoragePrimedFor: String? = null
    private var bridgeInstalledFor: String? = null
    private var activeProfile: Profile? = null
    /** Profile this WebView instance is bound to; a WebView can be bound exactly once. */
    private var boundProfileName: String? = null

    /** Feature probe so the UI can fail closed instead of sharing a data store. */
    fun profilesSupported(): Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)

    private fun profileName(connectionId: String): String =
        "src-" + connectionId.replace(Regex("[^A-Za-z0-9-]"), "").take(24)

    /**
     * Bind this WebView to the source's own WebView data profile (the Android
     * counterpart of one WKWebsiteDataStore per source): cookies, WebStorage and
     * service workers live there, so a different source can never read them.
     * Returns false when the platform lacks profiles - callers must fail closed.
     */
    private fun bindProfile(view: WebView, connection: Connection): Boolean {
        if (!profilesSupported()) return false
        val name = profileName(connection.id)
        val store = ProfileStore.getInstance()
        val profile = store.getOrCreateProfile(name)
        if (boundProfileName == name) {
            activeProfile = profile
            return true
        }
        return try {
            WebViewCompat.setProfile(view, profile.name)
            activeProfile = profile
            boundProfileName = name
            true
        } catch (e: Exception) {
            android.util.Log.w("TermLLM", "profile bind failed: " + e.message)
            false
        }
    }

    fun isLoaded(connectionId: String, path: String): Boolean =
        webView != null && loadedConnectionId == connectionId && loadedPath == path

    /** Attach into [parent], never re-creating a document for the same destination. */
    fun attach(parent: ViewGroup, connection: Connection, path: String, token: String?): WebView {
        container = parent
        var existing = webView
        if (recoveryBlocked) {
            // Too many web-process deaths: show a blank surface until the user reloads.
            val blank = existing ?: create().also { webView = it }
            reattach(blank, parent)
            return blank
        }
        if (existing != null && boundProfileName != null && boundProfileName != profileName(connection.id)) {
            // A WebView is bound to one profile for its lifetime (androidx.webkit refuses a
            // second setProfile), so another source gets a fresh WebView with its own
            // cookies and storage; the previous source's document is destroyed, not reused.
            purge(null)
            destroy()
            existing = null
        }
        if (existing != null && isLoaded(connection.id, path)) {
            val server = connection.url.toHttpUrl()
            val origin = ServerAddress.origin(server)
            val mount = ServerAddress.mountPath(server).let { if (it.isEmpty()) "/" else it + "/" }
            val desiredPush = if (pushAllowed(connection.id)) connection.id + "|" + origin + "|" + mount else null
            val desiredTools = if (sharingAllowed(connection.id)) origin else null
            reattach(existing, parent)
            if (pushInstalledFor != desiredPush || bridgeInstalledFor != desiredTools) load(existing, connection, path, token)
            return existing
        }
        val view = existing ?: create().also { webView = it }
        reattach(view, parent)
        // Each source has its own WebView data profile; switching sources blanks
        // the previous document and its storage before the new profile is bound.
        val previous = loadedConnectionId
        if (previous != null && previous != connection.id) {
            purge(null)
        }
        load(view, connection, path, token)
        return view
    }

    private fun reattach(view: WebView, parent: ViewGroup) {
        val current = view.parent
        if (current === parent) return
        (current as? ViewGroup)?.removeView(view)
        parent.addView(view)
    }

    private fun requireTrustedDocument(view: WebView, connection: Connection) {
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
            val check = java.util.concurrent.FutureTask<Unit> { requireTrustedDocument(view, connection) }
            mainHandler.post(check)
            check.get(5, java.util.concurrent.TimeUnit.SECONDS)
            return
        }
        val current = view.url?.let { runCatching { it.toHttpUrl() }.getOrNull() }
        require(current != null && ServerAddress.isWithinMount(current, connection.url.toHttpUrl())) {
            "Document is outside this source mount"
        }
    }

    private fun allowNavigation(view: WebView, raw: String, mainFrame: Boolean): Boolean {
        if (!mainFrame) return false
        val connection = lastConnection ?: return true
        val url = runCatching { raw.toHttpUrl() }.getOrNull()
        if (url != null && ServerAddress.isWithinMount(url, connection.url.toHttpUrl())) return false
        if (url != null && (url.scheme == "https" || ServerAddress.allowsLocalHttp(url.host))) {
            // External documents never retain this source's privileged WebView bridges.
            runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(raw))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        return true // no javascript/file/content/custom-scheme navigation in the privileged view
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(): WebView = WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.mediaPlaybackRequiresUserGesture = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
                handlePermissionRequest(request)
            }
        }
        // Hung-page recovery (iOS WebKitHealthMonitor): a renderer unresponsive for
        // longer than HUNG_LIMIT_MS is terminated, which lands in onRenderProcessGone.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_VIEW_RENDERER_CLIENT_BASIC_USAGE)) {
            WebViewCompat.setWebViewRenderProcessClient(this, object : androidx.webkit.WebViewRenderProcessClient() {
                override fun onRenderProcessUnresponsive(view: WebView, renderer: androidx.webkit.WebViewRenderProcess?) {
                    val since = unresponsiveSince ?: System.currentTimeMillis().also { unresponsiveSince = it }
                    if (System.currentTimeMillis() - since > HUNG_LIMIT_MS) {
                        android.util.Log.w("TermLLM", "web process unresponsive for " + HUNG_LIMIT_MS + " ms: terminating")
                        renderer?.terminate()
                    }
                }

                override fun onRenderProcessResponsive(view: WebView, renderer: androidx.webkit.WebViewRenderProcess?) {
                    unresponsiveSince = null
                }
            })
        }
        webViewClient = object : android.webkit.WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean =
                allowNavigation(view, request.url.toString(), request.isForMainFrame)

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                val server = lastConnection?.url?.let { runCatching { it.toHttpUrl() }.getOrNull() }
                val actual = url?.let { runCatching { it.toHttpUrl() }.getOrNull() }
                if (server != null && url != "about:blank" && (actual == null || !ServerAddress.isWithinMount(actual, server))) {
                    view?.stopLoading()
                    lastError = "Navigation left the configured source mount"
                    listener?.onError(lastError!!)
                    view?.loadUrl("about:blank")
                    return
                }
                pageLoading = true
                // A new document ends any capture the previous one held (tracks die with
                // the page without firing events), so the native side is reset here too.
                setCapturing(false)
                listener?.onLoading()
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                // Chromium can call this before its Java back/forward list is
                // committed. Read authoritative current state on the next main turn,
                // never the possibly stale URL argument or a detached View.post queue.
                mainHandler.post {
                    if (view !== webView || view == null) return@post
                    val connection = lastConnection ?: return@post
                    if (loadedConnectionId != connection.id) return@post
                    val target = view.url?.let { runCatching { it.toHttpUrl() }.getOrNull() }
                    val trusted = target != null && ServerAddress.isWithinMount(target, connection.url.toHttpUrl())
                    val path = if (trusted) target!!.encodedPath + (target.encodedQuery?.let { "?" + it } ?: "") else null
                    if (path != null) {
                        loadedPath = path
                        lastPath = path
                        loadedUrl = target.toString()
                    }
                    listener?.onLocationChanged(path, view.canGoBack())
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                pageLoading = false
                listener?.onLoaded()
                if (pendingVoiceLaunch && view != null) startVoiceLaunch(view)
            }

            override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                if (view !== webView) return true
                handleRenderGone(if (detail?.didCrash() == true) "crashed" else "was stopped by the system")
                return true
            }

            override fun onReceivedError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?,
            ) {
                if (request?.isForMainFrame == true) {
                    listener?.onError("This page could not be loaded. Check the source address and your network connection.")
                }
            }
        }
    }

    private fun ensureBridge(view: WebView, connection: Connection) {
        if (!sharingAllowed(connection.id)) {
            // Consent is the gate: no grant, no tools. Any earlier install is dropped
            // so a revoked source cannot keep calling through a stale listener.
            if (bridgeInstalledFor != null) {
                com.termllm.app.tools.WebMCPBridge.uninstall(view)
                bridgeInstalledFor = null
            }
            return
        }
        val origin = ServerAddress.origin(connection.url.toHttpUrl())
        if (bridgeInstalledFor == origin) return
        toolHandler?.let { handler ->
            com.termllm.app.tools.WebMCPBridge.install(view, origin, "Android phone", handler) { name, args ->
                // Defence in depth: the page cannot call a tool while consent is off,
                // even if it kept a reference from before.
                if (!sharingAllowed(connection.id)) {
                    throw SecurityException("Device tools are not shared with this source")
                }
                requireTrustedDocument(view, connection)
            }
            bridgeInstalledFor = origin
        }
    }

    private fun ensurePushBridge(view: WebView, connection: Connection) {
        if (!pushAllowed(connection.id)) {
            if (pushInstalledFor != null) com.termllm.app.push.PushBridge.uninstall(view)
            pushInstalledFor = null
            return
        }
        val origin = ServerAddress.origin(connection.url.toHttpUrl())
        val mount = ServerAddress.mountPath(connection.url.toHttpUrl()).let { if (it.isEmpty()) "/" else it + "/" }
        val key = connection.id + "|" + origin + "|" + mount
        if (pushInstalledFor == key) return
        val handler = pushHandlerFactory?.invoke(connection.id, origin, mount)
        if (handler == null && pushHandler == null) return
        com.termllm.app.push.PushBridge.install(view, origin, routeKey = connection.id, mountPrefix = mount) { method, args ->
            requireTrustedDocument(view, connection)
            check(pushAllowed(connection.id)) { "Notifications are disabled for this source" }
            handler?.handle(method, args) ?: pushHandler!!.invoke(method, args)
        }
        pushInstalledFor = key
    }

    /** Revoke: drop the bridge and every byte of this source's page state at once. */
    fun revokeTools(view: WebView, connection: Connection) {
        com.termllm.app.tools.WebMCPBridge.uninstall(view)
        bridgeInstalledFor = null
        webStoragePrimedFor = null
        purge(connection)
        view.loadUrl("about:blank")
    }

    fun load(view: WebView, connection: Connection, path: String, token: String?) {
        val server = connection.url.toHttpUrl()
        val target = ServerAddress.absolute(server, path) ?: run {
            lastError = "This conversation link is outside the configured server mount."
            return
        }
        if (!ServerAddress.isWithinMount(target, server)) {
            lastError = "This conversation link is outside the configured server mount."
            return
        }
        lastError = null
        recoveryBlocked = false
        lastConnection = connection
        lastPath = path
        lastToken = token
        if (!bindProfile(view, connection)) {
            lastError = "Per-source WebView isolation is unavailable on this device, so chat stays closed."
            return
        }
        ensureCaptureBridge(view, connection)
        ensureBridge(view, connection)
        ensurePushBridge(view, connection)
        primeCookies(connection, target.toString(), token)
        primeSessionCookie(connection, target.toString())
        primeWebStorage(view, connection, token)
        view.loadUrl(target.toString())
        loadedConnectionId = connection.id
        loadedPath = path
        loadedUrl = target.toString()
    }

    /**
     * The web UI reads its bearer from `localStorage` (`term_llm_token` for a Direct
     * Node, `term_llm_hub_token` for a Hub) before it makes any request, so a cookie
     * alone leaves it on "Connection expired". Seed the key at document start, in
     * this source's origin only, and only when it is missing or stale.
     */
    private fun primeWebStorage(view: WebView, connection: Connection, token: String?) {
        if (token.isNullOrEmpty()) return
        val origin = ServerAddress.origin(connection.url.toHttpUrl())
        val key = if (connection.kind == ServerKind.HUB) "term_llm_hub_token" else "term_llm_token"
        val mount = ServerAddress.mountPath(connection.url.toHttpUrl())
        // Only this source's mount, and only the top frame, ever receive the token.
        val mountPrefix = if (mount.isEmpty()) "/" else mount + "/"
        val marker = origin + "|" + mountPrefix + "|" + key + "|" + token.hashCode()
        if (webStoragePrimedFor == marker) return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            // Without a document-start hook the page can only be primed after load,
            // so say so instead of silently failing on the auth screen.
            lastError = "This WebView cannot seed the web UI token before load."
            return
        }
        val script = (
            "(() => { try { " +
                "if (window.top !== window) return; " +
                "if (location.origin !== " + jsLiteral(origin) + ") return; " +
                "if (location.pathname.indexOf(" + jsLiteral(mountPrefix) + ") !== 0) return; " +
                "var key = " + jsLiteral(key) + "; " +
                "var token = " + jsLiteral(token) + "; " +
                "if (localStorage.getItem(key) !== token) localStorage.setItem(key, token); " +
                "} catch (e) {} })();"
            )
        WebViewCompat.addDocumentStartJavaScript(view, script, setOf(origin))
        webStoragePrimedFor = marker
    }

    /** The passkey session cookie goes into the source's own profile before its first load. */
    private fun primeSessionCookie(connection: Connection, url: String) {
        val cookie = sessionCookie(connection.id) ?: return
        val manager = activeProfile?.cookieManager ?: return // fail closed: never the shared jar
        manager.setCookie(url, com.termllm.app.auth.NativeLogin.webViewCookie(cookie))
        manager.flush()
    }

    /** Import the session a native passkey sign-in just redeemed (iOS `cookieSink`). */
    fun importSessionCookie(connection: Connection, cookie: com.termllm.app.auth.NativeLogin.SessionCookie) {
        val manager = profileFor(connection.id)?.cookieManager ?: return // fail closed
        val url = connection.url.toHttpUrl().newBuilder().encodedPath(cookie.path).query(null).build().toString()
        manager.setCookie(url, com.termllm.app.auth.NativeLogin.webViewCookie(cookie))
        manager.flush()
    }

    /** A JavaScript string literal, quoting and escaping by hand (no JSON dependency here). */
    internal fun jsLiteral(value: String): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("<", "\\u003c")
        return "\"" + escaped + "\""
    }

    private fun primeCookies(connection: Connection, url: String, token: String?) {
        if (token.isNullOrEmpty()) return
        val mount = ServerAddress.mountPath(connection.url.toHttpUrl())
        val cookiePath = if (mount.isEmpty()) "/" else mount + "/"
        val secure = if (connection.url.startsWith("https")) "; Secure" else ""
        val cookie = if (connection.kind == ServerKind.HUB) {
            "term_llm_hub_token=" + token + "; Path=" + cookiePath + "; HttpOnly" + secure
        } else {
            "term_llm_token=" + token + "; Path=" + cookiePath + "; SameSite=Strict" + secure
        }
        val manager = activeProfile?.cookieManager ?: CookieManager.getInstance()
        manager.setCookie(url, cookie)
        manager.flush()
    }

    /**
     * Fail-closed purge: called on sign-out, on 401/403 and when a source is
     * removed. Protected content must disappear immediately, not on next
     * navigation, and the cookie/localStorage tokens must not survive.
     */
    private fun profileFor(connectionId: String): Profile? =
        if (!profilesSupported()) null else ProfileStore.getInstance().getOrCreateProfile(profileName(connectionId))

    // ---- capture policy / live call ----

    /** LiveHangUp + capture-report scripts for this source's origin, installed once per WebView. */
    private fun ensureCaptureBridge(view: WebView, connection: Connection) {
        val origin = ServerAddress.origin(connection.url.toHttpUrl())
        if (captureBridgeFor == origin) return
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(view, CAPTURE_JS_NAME, setOf(origin)) { _, message, sourceOrigin, isMainFrame, _ ->
                if (!isMainFrame || sourceOrigin.toString().trimEnd('/') != origin.trimEnd('/')) return@addWebMessageListener
                if (runCatching { requireTrustedDocument(view, connection) }.isFailure) return@addWebMessageListener
                setCapturing(message.data == "start")
            }
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(view, com.termllm.app.chat.LiveHangUp.userScript, setOf(origin))
            WebViewCompat.addDocumentStartJavaScript(view, CAPTURE_REPORT_SCRIPT, setOf(origin))
        }
        captureBridgeFor = origin
    }

    private fun setCapturing(active: Boolean) {
        if (capturing == active) return
        capturing = active
        android.util.Log.i("TermLLM", "page capture " + (if (active) "started" else "stopped"))
        onCaptureChange?.invoke(active)
        if (active) com.termllm.app.chat.LiveCallService.start(context, "Live voice conversation")
        else com.termllm.app.chat.LiveCallService.stop(context)
    }

    /** iOS `mediaCaptureDecision`: the source origin's microphone is granted (after the OS permission), the rest prompts. */
    private fun handlePermissionRequest(request: android.webkit.PermissionRequest) {
        val view = webView
        val connection = lastConnection
        val source = connection?.url?.let { runCatching { ServerAddress.origin(it.toHttpUrl()) }.getOrNull() }
        fun stillTrusted(): Boolean = view != null && connection != null &&
            webView === view && lastConnection?.id == connection.id &&
            request.origin.toString().trimEnd('/') == source &&
            runCatching { requireTrustedDocument(view, connection) }.isSuccess
        // Capture is a capability of the currently bound document, not of a stale
        // origin or an earlier consent prompt. Foreign frames cannot borrow it.
        if (!stillTrusted()) { request.deny(); return }
        val resources = request.resources.toList()
        val decision = if (source == null) com.termllm.app.chat.MediaCapturePolicy.Decision.DENY
        else com.termllm.app.chat.MediaCapturePolicy.decide(true, request.origin.toString(), source, resources)
        if (BuildConfig.DEBUG) android.util.Log.i("TermLLM", "capture request origin=" + request.origin + " resources=" + resources + " decision=" + decision)
        val grantAfterPermissions: () -> Unit = {
            ensurePermissions(com.termllm.app.chat.MediaCapturePolicy.androidPermissions(resources)) { ok ->
                if (ok && stillTrusted()) request.grant(request.resources) else request.deny()
            }
        }
        when (decision) {
            com.termllm.app.chat.MediaCapturePolicy.Decision.DENY -> request.deny()
            com.termllm.app.chat.MediaCapturePolicy.Decision.GRANT -> grantAfterPermissions()
            com.termllm.app.chat.MediaCapturePolicy.Decision.PROMPT -> {
                val prompt = capturePrompt
                if (prompt == null) request.deny()
                else prompt(request.origin.toString(), resources) { allowed -> if (allowed && stillTrusted()) grantAfterPermissions() else request.deny() }
            }
        }
    }

    private fun ensurePermissions(permissions: List<String>, decide: (Boolean) -> Unit) {
        if (permissions.isEmpty()) { decide(true); return }
        val requester = permissionRequester ?: run { decide(false); return }
        fun next(index: Int) {
            if (index >= permissions.size) { decide(true); return }
            requester(permissions[index]) { ok -> if (ok) next(index + 1) else decide(false) }
        }
        next(0)
    }

    // ---- voice shortcut: press the page's own Start button ----

    fun requestVoiceLaunch() {
        pendingVoiceLaunch = true
        android.util.Log.i("TermLLM", "voice launch requested (document loaded=" + (loadedConnectionId != null) + ", loading=" + pageLoading + ")")
        val view = webView ?: return
        if (loadedConnectionId != null && !pageLoading) startVoiceLaunch(view)
    }

    private fun startVoiceLaunch(view: WebView) {
        pendingVoiceLaunch = false
        voiceDeadline = System.currentTimeMillis() + com.termllm.app.chat.VoiceLaunch.START_TIMEOUT_MS
        pollVoice(view, confirming = false)
    }

    private fun pollVoice(view: WebView, confirming: Boolean) {
        if (webView !== view || loadedConnectionId == null) {
            onVoiceLaunchFinished?.invoke("Voice launch interrupted.")
            return
        }
        val script = if (confirming) com.termllm.app.chat.VoiceLaunch.confirm else com.termllm.app.chat.VoiceLaunch.start
        view.evaluateJavascript(script) { raw ->
            android.util.Log.d("TermLLM", "voice launch poll confirming=" + confirming + " state=" + raw)
            when (com.termllm.app.chat.VoiceLaunch.unquote(raw)) {
                "active" -> onVoiceLaunchFinished?.invoke(null)
                "failed" -> onVoiceLaunchFinished?.invoke("The page could not start live voice.")
                "clicked" -> {
                    voiceDeadline = System.currentTimeMillis() + com.termllm.app.chat.VoiceLaunch.CONFIRM_TIMEOUT_MS
                    mainHandler.postDelayed({ pollVoice(view, confirming = true) }, com.termllm.app.chat.VoiceLaunch.POLL_MS)
                }
                else -> if (System.currentTimeMillis() > voiceDeadline) {
                    onVoiceLaunchFinished?.invoke(if (confirming) "Live voice did not start." else "The page offered no live voice control.")
                } else {
                    mainHandler.postDelayed({ pollVoice(view, confirming) }, com.termllm.app.chat.VoiceLaunch.POLL_MS)
                }
            }
        }
    }

    // ---- web-process recovery (iOS WebKitHealthMonitor teardown + reopen) ----

    private fun handleRenderGone(reason: String) {
        val view = webView
        setCapturing(false)
        hangUp.reset()
        view?.let { (it.parent as? ViewGroup)?.removeView(it); runCatching { it.destroy() } }
        webView = null
        boundProfileName = null
        activeProfile = null
        bridgeInstalledFor = null
        pushInstalledFor = null
        webStoragePrimedFor = null
        captureBridgeFor = null
        unresponsiveSince = null
        val now = System.currentTimeMillis()
        while (renderGoneAt.isNotEmpty() && now - renderGoneAt.first() > RENDER_GONE_WINDOW_MS) renderGoneAt.removeFirst()
        renderGoneAt.addLast(now)
        // Clearing the loaded path makes the next attach() rebuild and reload the same
        // destination; past the budget the UI must ask for an explicit reload instead.
        loadedPath = null
        loadedUrl = null
        recoveryBlocked = renderGoneAt.size > RENDER_GONE_BUDGET
        lastError = if (recoveryBlocked) "The page's web process " + reason + " " + renderGoneAt.size + " times in 10 minutes. Reload to try again."
        else "The page's web process " + reason + "; it was reopened."
        android.util.Log.w("TermLLM", "web process gone: " + lastError)
        listener?.onError(lastError!!)
        if (!recoveryBlocked) {
            // Reopen the same destination in a fresh WebView bound to the same profile.
            val connection = lastConnection
            val path = lastPath
            val parent = container
            if (connection != null && path != null && parent != null) {
                val fresh = create().also { webView = it }
                reattach(fresh, parent)
                val message = lastError
                load(fresh, connection, path, lastToken)
                lastError = message // keep the explanation visible until the page loads
            }
        }
    }

    /** Explicit reload from the UI; also the way out after the recovery budget is spent. */
    fun reload(connection: Connection, path: String, token: String?) {
        recoveryBlocked = false
        val view = webView ?: create().also { fresh ->
            webView = fresh
            container?.let { reattach(fresh, it) }
        }
        load(view, connection, path, token)
    }

    fun purge(connection: Connection?) {
        // Blank the document only when it actually belongs to the purged source.
        val view = webView
        if (view != null && (connection == null || loadedConnectionId == connection.id)) {
            setCapturing(false)
            hangUp.reset()
            view.stopLoading()
            view.loadUrl("about:blank")
            view.clearHistory()
            view.clearCache(true)
            view.evaluateJavascript(
                "try{localStorage.clear();sessionStorage.clear();}catch(e){}",
                null
            )
        }
        // Clear the purged source's own profile (cookies + WebStorage). Other
        // sources keep their data: this is the "revoke one source" case.
        val target = connection?.let { profileFor(it.id) } ?: activeProfile
        target?.let { profile ->
            profile.cookieManager.removeAllCookies(null)
            profile.cookieManager.flush()
            profile.webStorage.deleteAllData()
        }
        if (connection == null || loadedConnectionId == connection.id) {
            loadedConnectionId = null
            loadedPath = null
            loadedUrl = null
            lastError = null
        }
    }

    fun destroy() {
        webView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            view.stopLoading()
            view.destroy()
        }
        webView = null
        loadedConnectionId = null
        loadedPath = null
        loadedUrl = null
        boundProfileName = null
        activeProfile = null
        bridgeInstalledFor = null
        pushInstalledFor = null
        webStoragePrimedFor = null
        captureBridgeFor = null
        hangUp.reset()
        setCapturing(false)
    }

    companion object {
        const val CAPTURE_JS_NAME = "termllmCapture"
        const val RENDER_GONE_BUDGET = 3
        const val RENDER_GONE_WINDOW_MS = 10 * 60 * 1000L
        const val HUNG_LIMIT_MS = 15_000L

        /**
         * Reports when the page starts/stops a getUserMedia capture, so the app can
         * hold a microphone foreground service for a backgrounded Live call (iOS
         * observes `microphoneCaptureState`). It wraps nothing else.
         */
        val CAPTURE_REPORT_SCRIPT: String = """
            (() => {
              if (window.top !== window || !navigator.mediaDevices || !window.termllmCapture) return;
              const post = (s) => { try { window.termllmCapture.postMessage(s); } catch (e) {} };
              const original = navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);
              let live = 0;
              window.addEventListener('pagehide', () => { if (live > 0) { live = 0; post('stop'); } });
              navigator.mediaDevices.getUserMedia = async (constraints) => {
                const stream = await original(constraints);
                live += 1; if (live === 1) post('start');
                const tracks = stream.getTracks();
                let ended = 0;
                for (const track of tracks) {
                  let finished = false;
                  const done = () => { if (finished) return; finished = true; ended += 1;
                    if (ended === tracks.length) { live = Math.max(0, live - 1); if (live === 0) post('stop'); } };
                  track.addEventListener('ended', done, { once: true });
                  const stop = track.stop.bind(track);
                  track.stop = () => { stop(); done(); };
                }
                return stream;
              };
            })();
        """.trimIndent()
    }
}
