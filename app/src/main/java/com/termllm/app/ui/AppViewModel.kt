package com.termllm.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.termllm.app.BuildConfig
import com.termllm.app.data.Connection
import com.termllm.app.data.ConnectionCatalog
import com.termllm.app.data.ConnectionStore
import com.termllm.app.data.CredentialStore
import com.termllm.app.data.ServerAddress
import com.termllm.app.data.ServerKind
import com.termllm.app.model.AgentSessionPage
import com.termllm.app.model.AgentSummary
import com.termllm.app.model.AttentionItem
import com.termllm.app.model.AttentionSnapshot
import com.termllm.app.model.ServerRole
import com.termllm.app.model.SessionSummary
import com.termllm.app.model.SourceState
import com.termllm.app.net.Discovery
import com.termllm.app.net.SourceApi
import com.termllm.app.net.UnauthorizedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import com.termllm.app.auth.NativeLogin
import java.util.UUID

data class ProbeResult(val url: HttpUrl, val role: ServerRole)

/**
 * App-wide state: the saved-source catalog (ported from the iOS
 * HubConnectionStore) plus one independent refresh state per source, so a slow
 * or unauthorized source never blocks a healthy one.
 */
class AppViewModel(app: Application, private val savedState: androidx.lifecycle.SavedStateHandle) : AndroidViewModel(app) {

    private val store = ConnectionStore(app)
    private val credentials = CredentialStore(app)

    private val catalogState = MutableStateFlow(ConnectionCatalog())
    val catalog: StateFlow<ConnectionCatalog> = catalogState.asStateFlow()

    private val statesState = MutableStateFlow<Map<String, SourceState>>(emptyMap())
    val states: StateFlow<Map<String, SourceState>> = statesState.asStateFlow()

    private val settingsReadyState = MutableStateFlow(false)
    val settingsReady: StateFlow<Boolean> = settingsReadyState.asStateFlow()
    private val notificationsState = MutableStateFlow(false)
    val notificationsEnabled: StateFlow<Boolean> = notificationsState.asStateFlow()
    private val persistence = kotlinx.coroutines.channels.Channel<ConnectionCatalog>(kotlinx.coroutines.channels.Channel.CONFLATED)
    init {
        com.termllm.app.listen.PlayerHub.shelfVisible.value = savedState.get<Boolean>("shelf_visible") ?: true
        viewModelScope.launch(Dispatchers.IO) { for (value in persistence) store.save(value) }
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                notificationsState.value = getApplication<Application>().getSharedPreferences("termllm_settings", Application.MODE_PRIVATE).getBoolean("notifications_enabled", false)
                com.termllm.app.push.PushCenter.noteConsent(notificationsState.value)
                store.load()
            }
            catalogState.value = loaded
            settingsReadyState.value = true
            refreshAll()
        }
    }

    fun saveShelfVisibility(visible: Boolean) { savedState["shelf_visible"] = visible }
    val connections: List<Connection> get() = catalogState.value.connections

    fun connection(id: String): Connection? = connections.firstOrNull { it.id == id }

    // The single retained Chat destination (iOS keeps one Chat tab, not an
    // arbitrary agent or server root).
    private val chatTargetState = MutableStateFlow<Pair<String, String>?>(
        savedState.get<String>("chat_source")?.let { id -> savedState.get<String>("chat_path")?.let { id to it } }
    )
    val chatTarget: StateFlow<Pair<String, String>?> = chatTargetState.asStateFlow()

    fun openChat(connectionId: String, path: String) {
        savedState["chat_source"] = connectionId
        savedState["chat_path"] = path.substringBefore('?').substringBefore('#')
        chatTargetState.value = connectionId to path
    }

    fun lastAgentForChat(): Pair<String, String>? {
        val agent = agents(statesState.value).firstOrNull { it.reachable } ?: return null
        val path = agent.newSessionPath ?: agent.proxyPath ?: return null
        return agent.connectionId to path
    }

    fun token(connectionId: String): String? = credentials.read(connectionId)

    fun api(connection: Connection): SourceApi =
        SourceApi(connection, credentials.read(connection.id)) { onUnauthorized(connection.id) }
            .withSession(credentials.readSession(connection.id))

    fun refreshAll() {
        connections.forEach { refresh(it.id) }
    }

    fun refresh(connectionId: String) {
        val connection = connection(connectionId) ?: return
        // A token is optional: loopback/dev nodes commonly run without one. Attempt the
        // refresh and let a 401/403 decide that the source needs credentials, instead of
        // pre-emptively hiding a reachable node behind a sign-in card.
        val hasToken = !credentials.read(connectionId).isNullOrEmpty()
        update(connectionId) { it.copy(loading = true, needsAuth = if (hasToken) false else it.needsAuth) }
        viewModelScope.launch {
            val api = api(connection)
            var failure: String? = null
            var unauthorized = false
            var loginUrl: String? = null
            val agents = try {
                api.agents()
            } catch (e: UnauthorizedException) {
                unauthorized = true
                loginUrl = e.loginUrl
                failure = e.message
                emptyList()
            } catch (e: Exception) {
                failure = e.message ?: e.toString()
                emptyList()
            }
            val hubNodes = if (connection.kind == ServerKind.HUB && failure == null) {
                try {
                    api.hubNodes()
                } catch (e: Exception) {
                    emptyList()
                }
            } else {
                emptyList()
            }
            val attention = if (agents.isEmpty() && failure != null) {
                AttentionSnapshot()
            } else {
                try {
                    api.attention()
                } catch (e: UnauthorizedException) {
                    unauthorized = true
                    loginUrl = loginUrl ?: e.loginUrl
                    failure = failure ?: e.message
                    AttentionSnapshot()
                } catch (e: Exception) {
                    AttentionSnapshot()
                }
            }
            if (BuildConfig.DEBUG) android.util.Log.d("TermLLM", "source sync agents=" + agents.size +
                " attention=" + attention.items.size + " input=" + attention.inputRequired +
                " unseen=" + attention.unseen + " failed=" + (failure != null))
            update(connectionId) { current ->
                current.copy(
                    loading = false,
                    // A failed refresh keeps previously verified totals, matching the
                    // iOS badge behavior; authorization failures drop the source state.
                    agents = if (agents.isEmpty() && failure != null) current.agents else agents,
                    attention = if (unauthorized) AttentionSnapshot()
                    else if (failure != null) current.attention
                    else attention,
                    hubNodes = if (hubNodes.isEmpty() && failure != null) current.hubNodes else hubNodes,
                    error = failure,
                    needsAuth = unauthorized,
                    loginUrl = if (unauthorized) loginUrl else null,
                    lastSyncedAt = if (failure == null) System.currentTimeMillis() else current.lastSyncedAt,
                )
            }
        }
    }

    suspend fun detect(rawUrl: String): Result<ProbeResult> = withContext(Dispatchers.IO) {
        val url = ServerAddress.parse(rawUrl)
            ?: return@withContext Result.failure(IllegalArgumentException(invalidAddressMessage(rawUrl)))
        try {
            val role = Discovery.detect(url)
            Result.success(ProbeResult(url, role))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun invalidAddressMessage(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return "Enter a server address."
        val looksHttp = trimmed.startsWith("http://", ignoreCase = true)
        if (looksHttp && !BuildConfig.DEBUG) {
            return "This build requires HTTPS. Enter the server as https://host or just host, with an optional path prefix."
        }
        if (looksHttp) {
            return "Plain HTTP is only allowed for local development hosts (localhost, 127.0.0.1, 10.0.2.2)."
        }
        return "Enter a valid server address, for example https://hub.example.com/work/"
    }

    /** Adds a source. A changed endpoint mints a new identity, so tokens never follow it. */
    fun addConnection(name: String, probe: ProbeResult, token: String?): Connection {
        val kind = when (probe.role) {
            ServerRole.HUB -> ServerKind.HUB
            ServerRole.DIRECT_NODE -> ServerKind.DIRECT_NODE
            ServerRole.UNKNOWN -> ServerKind.UNKNOWN
        }
        val connection = Connection(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { defaultSourceName(probe.url) },
            kind = kind,
            url = probe.url.toString(),
        )
        persist(catalogState.value.copy(
            connections = catalogState.value.connections + connection,
            selectedId = connection.id,
        ))
        if (!token.isNullOrEmpty()) credentials.write(connection.id, token)
        update(connection.id) { SourceState() }
        refresh(connection.id)
        return connection
    }

    fun updateConnection(connection: Connection, name: String, rawUrl: String): Result<ProbeResult> {
        val url = ServerAddress.parse(rawUrl)
            ?: return Result.failure(IllegalArgumentException(invalidAddressMessage(rawUrl)))
        val changed = url.toString() != connection.url
        val newId = if (changed) UUID.randomUUID().toString() else connection.id
        val kind = if (changed) ServerKind.UNKNOWN else connection.kind
        val updated = connection.copy(id = newId, name = name.ifBlank { defaultSourceName(url) }, url = url.toString(), kind = kind)
        if (changed) credentials.write(connection.id, null)
        persist(catalogState.value.copy(connections = connections.map { if (it.id == connection.id) updated else it }))
        if (changed) {
            update(connection.id) { SourceState() }
            viewModelScope.launch {
                val detected = detect(url.toString())
                detected.getOrNull()?.let { probe ->
                    val conn = connection(newId) ?: return@launch
                    val k = if (probe.role == ServerRole.HUB) ServerKind.HUB else ServerKind.DIRECT_NODE
                    persist(catalogState.value.copy(connections = connections.map { if (it.id == newId) conn.copy(kind = k) else it }))
                }
                refresh(newId)
            }
            return Result.success(ProbeResult(url, ServerRole.UNKNOWN))
        }
        refresh(connection.id)
        return Result.success(ProbeResult(url, if (kind == ServerKind.HUB) ServerRole.HUB else ServerRole.DIRECT_NODE))
    }

    fun rename(connection: Connection, name: String) {
        persist(catalogState.value.copy(connections = connections.map { if (it.id == connection.id) it.copy(name = name) else it }))
    }

    fun removeConnection(connectionId: String) {
        clearChatFor(connectionId)
        credentials.write(connectionId, null)
        credentials.writeSession(connectionId, null)
        persist(catalogState.value.copy(
            connections = connections.filterNot { it.id == connectionId },
            selectedId = catalogState.value.selectedId?.takeIf { it != connectionId },
        ))
        statesState.value = statesState.value - connectionId
    }

    fun select(connectionId: String) {
        persist(catalogState.value.copy(selectedId = connectionId))
    }

    /** Stores a bearer token and validates it against the source before keeping it. */
    suspend fun signIn(connection: Connection, token: String): Result<Unit> = withContext(Dispatchers.IO) {
        credentials.write(connection.id, token)
        val api = api(connection)
        try {
            if (connection.kind == ServerKind.HUB) {
                api.agents()
            } else {
                api.agents()
            }
            withContext(Dispatchers.Main) { refresh(connection.id) }
            Result.success(Unit)
        } catch (e: UnauthorizedException) {
            credentials.write(connection.id, null)
            withContext(Dispatchers.Main) {
                update(connection.id) { it.copy(needsAuth = true, error = e.message) }
            }
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun signOut(connectionId: String) {
        credentials.write(connectionId, null)
        credentials.writeSession(connectionId, null)
        // Privacy gate: a signed-out source must not keep a visible protected
        // document, its chat target, or its rows on screen.
        clearChatFor(connectionId)
        update(connectionId) { SourceState(needsAuth = true) }
    }

    fun clearChatFor(connectionId: String) {
        if (chatTargetState.value?.first == connectionId) {
            chatTargetState.value = null
            savedState.remove<String>("chat_source"); savedState.remove<String>("chat_path")
        }
    }

    // MARK: - Passkey sign-in (iOS NativeLogin: system browser ceremony, session cookie handoff)

    private var pendingNativeLogin: NativeLogin.Pending? = null
    private val loginMessageState = MutableStateFlow<String?>(null)
    val loginMessage: StateFlow<String?> = loginMessageState.asStateFlow()

    /** Installed by the UI: puts a redeemed session into the source's own WebView profile. */
    var sessionCookieSink: ((Connection, NativeLogin.SessionCookie) -> Unit)? = null

    fun sessionCookie(connectionId: String): NativeLogin.SessionCookie? =
        credentials.readSession(connectionId)?.takeUnless { it.expired() }

    /** Opens `{mount}/auth/native/{challenge}` in a Custom Tab; the verifier never leaves memory. */
    fun signInWithPasskey(connection: Connection, context: android.content.Context) {
        val pending = NativeLogin.begin(connection.id)
        pendingNativeLogin = pending
        loginMessageState.value = null
        val url = NativeLogin.startUrl(connection.url.toHttpUrl(), pending.challenge)
        try {
            androidx.browser.customtabs.CustomTabsIntent.Builder()
                .setShowTitle(true)
                .build()
                .launchUrl(context, android.net.Uri.parse(url.toString()))
        } catch (e: Exception) {
            pendingNativeLogin = null
            loginMessageState.value = "Couldn't open the system sign-in window."
        }
    }

    /** Browser callback: validate against the pending challenge, redeem, import the session. */
    fun completeNativeLogin(callback: String) {
        val pending = pendingNativeLogin
        if (pending == null) {
            loginMessageState.value = "No passkey sign-in was in progress."
            return
        }
        pendingNativeLogin = null
        val connection = connection(pending.connectionId) ?: return
        if (pending.expired()) {
            loginMessageState.value = "The sign-in took too long. Retry sign-in."
            return
        }
        val code = NativeLogin.parseCallback(callback, pending.challenge)
        if (code == null) {
            loginMessageState.value = "The server returned an invalid native login response. Update term-llm and retry."
            return
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { NativeLogin.redeem(connection.url.toHttpUrl(), connection.kind, code, pending.verifier) }
            }
            result.fold(
                onSuccess = { cookie ->
                    credentials.writeSession(connection.id, cookie)
                    // As on iOS, a web node's passkey session replaces any bearer it had.
                    if (connection.kind == ServerKind.DIRECT_NODE) credentials.write(connection.id, null)
                    sessionCookieSink?.invoke(connection, cookie)
                    update(connection.id) { it.copy(needsAuth = false, error = null, loginUrl = null) }
                    loginMessageState.value = "Signed in to " + connection.name + " with a passkey"
                    refresh(connection.id)
                },
                onFailure = { loginMessageState.value = it.message ?: "Sign-in failed." },
            )
        }
    }

    fun clearLoginMessage() {
        loginMessageState.value = null
    }

    /** One-line status for app links and voice launch, shown on the Agents tab. */
    fun postLoginMessage(text: String) {
        loginMessageState.value = text
    }

    fun markSeen(item: AttentionItem) {
        val connection = connection(item.connectionId) ?: return
        viewModelScope.launch {
            try {
                api(connection).markSeen(item)
                update(item.connectionId) { current ->
                    current.copy(attention = current.attention.copy(items = current.attention.items.filterNot { it == item }))
                }
                refresh(item.connectionId)
            } catch (e: Exception) {
                update(item.connectionId) { it.copy(error = e.message) }
            }
        }
    }

    /**
     * Acknowledge exactly one attention item by session + sequence, as a push
     * notification's "read" reports it. Nothing else on the source is cleared.
     */
    suspend fun markAttentionSeen(connectionId: String, sessionId: String, storeInstanceId: String, attentionSeq: Long) {
        val connection = connection(connectionId) ?: return
        withContext(Dispatchers.IO) {
            val item = statesState.value[connectionId]?.attention?.items
                ?.firstOrNull { com.termllm.app.push.PushRouting.matchesReadIdentity(sessionId, storeInstanceId, attentionSeq, it.sessionId, it.storeInstanceId, it.attentionSeq) }
            if (item != null) {
                runCatching { api(connection).markSeen(item) }
            }
            refresh(connectionId)
        }
    }

    data class AcknowledgeOutcome(val cleared: Int, val failed: Int, val error: String? = null)

    /**
     * Acknowledge *unseen results* only; needs-input and running state are not
     * cleared. A Hub has a bulk endpoint; a Direct Node has no bulk clear, so its
     * eligible rows are acknowledged one by one and partial failures are counted.
     */
    suspend fun acknowledgeUnseen(connectionId: String): AcknowledgeOutcome = withContext(Dispatchers.IO) {
        val connection = connection(connectionId) ?: return@withContext AcknowledgeOutcome(0, 0, "Unknown source")
        val api = api(connection)
        if (connection.kind == ServerKind.HUB) {
            try {
                val response = api.clearAttention()
                refresh(connectionId)
                AcknowledgeOutcome(response.cleared, response.failed)
            } catch (e: Exception) {
                update(connectionId) { it.copy(error = e.message) }
                AcknowledgeOutcome(0, 0, e.message)
            }
        } else {
            val eligible = statesState.value[connectionId]?.attention?.items
                ?.filter { it.kind == "unseen" && it.canAcknowledge }
                .orEmpty()
            if (eligible.isEmpty()) return@withContext AcknowledgeOutcome(0, 0, null)
            var cleared = 0
            var failed = 0
            eligible.forEach { item ->
                try {
                    api.markSeen(item)
                    cleared += 1
                } catch (e: Exception) {
                    failed += 1
                }
            }
            refresh(connectionId)
            AcknowledgeOutcome(cleared, failed, null)
        }
    }

    /** Full conversation history for one agent (paged 30 at a time on the node). */
    data class SessionsLoad(
        val sessions: List<SessionSummary>,
        val error: String? = null,
        val nextCursor: String? = null,
    )

    data class SearchHit(val id: String, val title: String, val snippet: String?, val number: Long?)

    /**
     * Default label for a saved source. A bare host collides with the agent that
     * lives behind it (both read "localhost" on the Agents screen), so include the
     * mount path the source is scoped to, e.g. "localhost/ui".
     */
    fun defaultSourceName(url: okhttp3.HttpUrl): String {
        val mount = com.termllm.app.data.ServerAddress.mountPath(url).trim('/')
        val host = url.host + if (url.port != 80 && url.port != 443) ":" + url.port else ""
        return if (mount.isEmpty()) host else host + "/" + mount
    }

    // MARK: - Hub administration

    suspend fun testHubNode(connectionId: String, form: com.termllm.app.model.NodeFormData):
        Result<com.termllm.app.model.HubNodeStatus?> = withContext(Dispatchers.IO) {
        val connection = connection(connectionId) ?: return@withContext Result.failure(IllegalStateException("Unknown source"))
        runCatching { api(connection).testHubNode(form).status }
    }

    suspend fun addHubNode(connectionId: String, form: com.termllm.app.model.NodeFormData):
        Result<com.termllm.app.model.AddNodeResponse> = withContext(Dispatchers.IO) {
        val connection = connection(connectionId) ?: return@withContext Result.failure(IllegalStateException("Unknown source"))
        runCatching {
            val response = api(connection).addHubNode(form)
            refresh(connectionId)
            response
        }
    }

    suspend fun removeHubNode(connectionId: String, nodeId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val connection = connection(connectionId) ?: return@withContext Result.failure(IllegalStateException("Unknown source"))
        runCatching {
            api(connection).removeHubNode(nodeId)
            refresh(connectionId)
        }
    }

    suspend fun reorderHubNodes(connectionId: String, nodeIds: List<String>): Result<List<String>> =
        withContext(Dispatchers.IO) {
            val connection = connection(connectionId) ?: return@withContext Result.failure(IllegalStateException("Unknown source"))
            runCatching {
                val response = api(connection).reorderHubNodes(nodeIds)
                refresh(connectionId)
                response.nodeIds
            }
        }

    suspend fun hubSecurity(connectionId: String): Result<com.termllm.app.model.HubSecurityState> =
        withContext(Dispatchers.IO) {
            val connection = connection(connectionId) ?: return@withContext Result.failure(IllegalStateException("Unknown source"))
            runCatching { api(connection).hubSecurity() }
        }

    suspend fun hubCredentials(connectionId: String): Result<List<com.termllm.app.model.HubCredential>> =
        withContext(Dispatchers.IO) {
            val connection = connection(connectionId) ?: return@withContext Result.failure(IllegalStateException("Unknown source"))
            runCatching { api(connection).hubCredentials() }
        }

    suspend fun hubRevokeOthers(connectionId: String): Result<Int> = withContext(Dispatchers.IO) {
        val connection = connection(connectionId) ?: return@withContext Result.failure(IllegalStateException("Unknown source"))
        runCatching { api(connection).hubRevokeOtherSessions() }
    }

    suspend fun hubLogout(connectionId: String): Result<Unit> = withContext(Dispatchers.IO) {
        val connection = connection(connectionId) ?: return@withContext Result.failure(IllegalStateException("Unknown source"))
        runCatching {
            api(connection).hubLogout()
            // The bearer is the Hub's own credential: drop it locally once revoked, and
            // drop the source's WebView profile so no page state survives the sign-out.
            credentials.write(connectionId, null)
            credentials.writeSession(connectionId, null)
            clearChatFor(connectionId)
            update(connectionId) {
                SourceState(needsAuth = true, error = "Signed out of this Hub. Sign in again to use it.")
            }
        }
    }

    suspend fun delegations(connectionId: String): Result<List<com.termllm.app.model.HubDelegation>> =
        withContext(Dispatchers.IO) {
            val connection = connection(connectionId) ?: return@withContext Result.failure(IllegalStateException("Unknown source"))
            runCatching { api(connection).delegations().delegations }
        }

    /** Reorder pinned conversations; the server answers with its full pin order. */
    suspend fun reorderPinned(
        connectionId: String,
        nodeId: String?,
        orderedIds: List<String>,
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        val connection = connection(connectionId) ?: return@withContext Result.failure(IllegalStateException("Unknown source"))
        runCatching {
            api(connection).reorderPinnedSessions(nodeId, orderedIds).pinned.map { it.id }
        }
    }

    suspend fun patchSession(
        connectionId: String,
        nodeId: String?,
        sessionId: String,
        patch: com.termllm.app.model.AgentSessionPatch,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val connection = connection(connectionId) ?: return@withContext Result.failure(IllegalStateException("Unknown source"))
        runCatching { api(connection).patchSession(nodeId, sessionId, patch) }
    }

    suspend fun search(
        connectionId: String,
        nodeId: String?,
        query: String,
        limit: Int = 30,
    ): Result<List<SearchHit>> = withContext(Dispatchers.IO) {
        val connection = connection(connectionId) ?: return@withContext Result.failure(IllegalStateException("Unknown source"))
        runCatching {
            api(connection).searchSessions(nodeId, query, limit).sessions.map {
                SearchHit(
                    id = it.id,
                    title = it.longTitle?.takeIf { t -> t.isNotBlank() }
                        ?: it.shortTitle?.takeIf { t -> t.isNotBlank() }
                        ?: ("Conversation " + it.id),
                    snippet = it.snippet,
                    number = it.number,
                )
            }
        }
    }

    suspend fun sessions(connectionId: String, nodeId: String?, limit: Int = 30, cursor: String? = null): SessionsLoad =
        withContext(Dispatchers.IO) {
            val connection = connection(connectionId)
                ?: return@withContext SessionsLoad(emptyList(), "This source is no longer saved.")
            try {
                val page: AgentSessionPage = api(connection).sessions(nodeId, limit, cursor)
                SessionsLoad(page.sessions.map { entry ->
                    SessionSummary(
                        id = entry.id,
                        number = entry.number,
                        title = entry.longTitle?.takeIf { it.isNotBlank() }
                            ?: entry.shortTitle?.takeIf { it.isNotBlank() }
                            ?: entry.name?.takeIf { it.isNotBlank() }
                            ?: ("Conversation " + entry.id),
                        projectName = entry.projectName,
                        resumePath = chatPath(connection, nodeId, entry.number, entry.id),
                        activeRun = false,
                        inputRequired = false,
                        unseen = entry.attentionUnseen ?: false,
                        pinned = entry.pinned ?: false,
                        lastMessageAt = entry.lastMessageAt,
                        messageCount = entry.messageCount,
                        pinOrder = entry.pinOrder,
                    )
                }, nextCursor = page.nextCursor)
            } catch (e: Exception) {
                SessionsLoad(emptyList(), e.message ?: "Could not load this agent's conversations.")
            }
        }

    private fun chatPath(connection: Connection, nodeId: String?, number: Long?, sessionId: String): String? {
        val api = api(connection)
        return if (connection.kind == ServerKind.HUB) {
            val mount = ServerAddress.mountPath(api.server)
            val root = (if (mount.isEmpty()) "" else mount) + "/node/" + (nodeId ?: return null) + "/chat/"
            root + (number?.toString() ?: sessionId)
        } else {
            api.resumePath(sessionId, number)
        }
    }

    fun chatUrl(connectionId: String, path: String): HttpUrl? {
        val connection = connection(connectionId) ?: return null
        return api(connection).chatUrl(path)
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        com.termllm.app.push.PushCenter.noteConsent(enabled)
        notificationsState.value = enabled
        getApplication<Application>()
            .getSharedPreferences("termllm_settings", Application.MODE_PRIVATE)
            .edit().putBoolean("notifications_enabled", enabled).apply()
    }

    /**
     * Aggregates are derived from the *observed* state map, never read straight
     * from the flow: reading `statesState.value` produced a stale screen because
     * such a read is invisible to Compose and did not invalidate the caller.
     */
    /** Hub nodes as currently known from the last successful refresh, in server order. */
    fun nodes(connectionId: String): List<com.termllm.app.model.HubNode> =
        states.value[connectionId]?.hubNodes ?: emptyList()

    fun agents(states: Map<String, SourceState>): List<AgentSummary> =
        connections.flatMap { states[it.id]?.agents ?: emptyList() }

    /** Server-reported totals (running, input-required, unseen) across sources. */
    fun attentionTotals(states: Map<String, SourceState>): Triple<Int, Int, Int> {
        var running = 0
        var input = 0
        var unseen = 0
        connections.forEach { connection ->
            val snapshot = states[connection.id]?.attention ?: return@forEach
            running += snapshot.running
            input += snapshot.inputRequired
            unseen += snapshot.unseen
        }
        return Triple(running, input, unseen)
    }

    fun attentionItems(states: Map<String, SourceState>): List<AttentionItem> =
        connections.flatMap { states[it.id]?.attention?.items ?: emptyList() }

    private fun onUnauthorized(connectionId: String) {
        credentials.write(connectionId, null)
        credentials.writeSession(connectionId, null)
        clearChatFor(connectionId)
        update(connectionId) {
            SourceState(needsAuth = true, error = "This source no longer accepts the saved token. Sign in again.")
        }
    }

    private fun update(connectionId: String, transform: (SourceState) -> SourceState) {
        statesState.value = statesState.value.toMutableMap().also { map ->
            map[connectionId] = transform(map[connectionId] ?: SourceState())
        }
    }

    private fun persist(catalog: ConnectionCatalog) {
        catalogState.value = catalog
        persistence.trySend(catalog)
    }
}
