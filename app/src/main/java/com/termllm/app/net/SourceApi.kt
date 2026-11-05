package com.termllm.app.net

import com.termllm.app.data.Connection
import com.termllm.app.data.ServerAddress
import com.termllm.app.data.ServerKind
import com.termllm.app.model.AgentSessionPage
import com.termllm.app.model.AgentSessionPatch
import com.termllm.app.model.AgentSummary
import com.termllm.app.model.AttentionItem
import com.termllm.app.model.AttentionResponse
import com.termllm.app.model.AttentionSeenRequest
import com.termllm.app.model.AttentionSnapshot
import com.termllm.app.model.ClearAttentionResponse
import com.termllm.app.model.DirectAttentionPage
import com.termllm.app.model.DirectNodeSessionsStatus
import com.termllm.app.model.HubNode
import com.termllm.app.model.NodesResponse
import com.termllm.app.model.AddNodeResponse
import com.termllm.app.model.DelegationsResponse
import com.termllm.app.model.HubCredential
import com.termllm.app.model.HubCredentialsResponse
import com.termllm.app.model.HubRevokeResponse
import com.termllm.app.model.HubSecurityState
import com.termllm.app.model.NodeFormData
import com.termllm.app.model.NodeOrderRequest
import com.termllm.app.model.NodeOrderResponse
import com.termllm.app.model.PinnedOrderRequest
import com.termllm.app.model.RemovedNodeResponse
import com.termllm.app.model.TestNodeResponse
import com.termllm.app.model.PinnedSessionOrder
import com.termllm.app.model.SeenAttentionResponse
import com.termllm.app.model.SessionSearchResponse
import com.termllm.app.model.SessionSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Native protocol client for one saved source.
 *
 * A Hub answers /api endpoints (dashboard, attention, delegations) and proxies a
 * node's /v1 routes under /node/<id>/. A Direct Node answers /v1 itself. Both
 * require `Authorization: Bearer` on every native request; mutations also send
 * the real Origin. Everything here is ported from HubClient.swift and
 * DirectNodeClient.swift (core 2496b5fd wire contract).
 */
class SourceApi(
    val connection: Connection,
    private val token: String?,
    private val onUnauthorized: () -> Unit = {},
) {
    val server: HttpUrl = connection.url.toHttpUrl()
    private val http = SourceHttp(server, token, onUnauthorized)
    private val mountPath: String = ServerAddress.mountPath(server)

    val isHub: Boolean get() = connection.kind == ServerKind.HUB

    /** Attach the session cookie a passkey sign-in redeemed; expired sessions are ignored. */
    fun withSession(cookie: com.termllm.app.auth.NativeLogin.SessionCookie?): SourceApi {
        if (cookie != null && !cookie.expired()) {
            http.seedCookie(com.termllm.app.auth.NativeLogin.okHttpCookie(server, cookie))
        }
        return this
    }

    // MARK: - Agents

    suspend fun agents(): List<AgentSummary> = withContext(Dispatchers.IO) {
        if (isHub) hubAgents() else directAgents()
    }

    /** Raw Hub node records, for the admin screen (full status, url, diagnostics). */
    suspend fun hubNodes(): List<HubNode> = withContext(Dispatchers.IO) {
        if (!isHub) return@withContext emptyList()
        Wire.json.decodeFromString(NodesResponse.serializer(), http.get("/api/nodes")).nodes
    }

    private suspend fun hubAgents(): List<AgentSummary> {
        val response = Wire.json.decodeFromString(
            NodesResponse.serializer(),
            http.get("/api/nodes")
        )
        return response.nodes.map { node ->
            val sessions = linkedSetOf<SessionSummary>()
            node.sessions?.active?.forEach { sessions.add(it.toSummary()) }
            node.sessions?.recent?.forEach { sessions.add(it.toSummary()) }
            AgentSummary(
                connectionId = connection.id,
                connectionName = connection.name,
                kind = connection.kind,
                nodeId = node.id,
                name = node.name,
                reachable = node.status?.reachable ?: false,
                state = node.status?.state ?: "unknown",
                version = node.status?.version,
                activeCount = node.sessions?.activeCount ?: 0,
                inputRequiredCount = node.sessions?.inputRequiredCount ?: 0,
                unseenCount = node.sessions?.unseenCount ?: 0,
                sessions = sessions.toList(),
                newSessionPath = node.newSessionPath.takeIf { it.isNotBlank() },
                proxyPath = node.proxyPath.takeIf { it.isNotBlank() },
                hasToken = node.hasToken,
                diagnostics = node.diagnostics.map { it.message },
            )
        }
    }

    private suspend fun directAgents(): List<AgentSummary> {
        val health = try {
            Wire.json.decodeFromString(
                com.termllm.app.model.HealthResponse.serializer(),
                http.get("/healthz")
            )
        } catch (e: ApiException) {
            null
        }
        val status = try {
            sessionsStatus()
        } catch (e: ApiException) {
            DirectNodeSessionsStatus()
        }
        val sessions = status.sessions.map { entry ->
            SessionSummary(
                id = entry.id,
                number = entry.number,
                title = entry.longTitle?.takeIf { it.isNotBlank() }
                    ?: entry.shortTitle.takeIf { it.isNotBlank() }
                    ?: "Conversation " + entry.id,
                projectName = entry.projectName,
                resumePath = resumePath(entry.id, entry.number),
                activeRun = entry.activeRun ?: false,
                inputRequired = entry.interactionRequired ?: false,
                unseen = entry.attentionUnseen ?: false,
                pinned = entry.pinned ?: false,
                pinOrder = entry.pinOrder,
                lastMessageAt = entry.lastMessageAt,
                messageCount = entry.messageCount,
            )
        }
        return listOf(
            AgentSummary(
                connectionId = connection.id,
                connectionName = connection.name,
                kind = connection.kind,
                nodeId = null,
                name = health?.agent?.takeIf { it.isNotBlank() } ?: connection.name,
                reachable = health?.status == "ok",
                state = if (health?.status == "ok") "ok" else "unreachable",
                version = health?.version,
                activeCount = status.sessions.count { it.activeRun == true },
                inputRequiredCount = status.sessions.count { it.interactionRequired == true },
                unseenCount = status.sessions.count { it.attentionUnseen == true },
                sessions = sessions,
                newSessionPath = mountRoot(),
                proxyPath = mountRoot(),
                hasToken = !token.isNullOrEmpty(),
            )
        )
    }

    /** GET /v1/sessions/status — the node's authoritative run/attention state. */
    suspend fun sessionsStatus(): DirectNodeSessionsStatus = withContext(Dispatchers.IO) {
        Wire.json.decodeFromString(
            DirectNodeSessionsStatus.serializer(),
            http.get("/v1/sessions/status")
        )
    }

    /** GET /v1/sessions, or a Hub's proxied /node/<id>/v1/sessions route. */
    suspend fun sessions(nodeId: String?, limit: Int = 30, cursor: String? = null): AgentSessionPage =
        withContext(Dispatchers.IO) {
            // iOS parity (AgentSessionQuery.percentEncoded): scope=all so every project's
            // sessions are listed, and the node's ceiling of 100 is enforced here too.
            val query = buildString {
                append("scope=all&limit=").append(limit.coerceIn(1, 100))
                if (!cursor.isNullOrEmpty()) append("&cursor=").append(percentEncode(cursor))
            }
            val path = if (isHub) {
                if (nodeId == null || !isSafeNodeId(nodeId)) return@withContext AgentSessionPage()
                "/node/" + nodeId + "/v1/sessions?" + query
            } else {
                "/v1/sessions?" + query
            }
            val pieces = path.split("?", limit = 2)
            Wire.json.decodeFromString(
                AgentSessionPage.serializer(),
                http.get(pieces[0], pieces.getOrNull(1))
            )
        }

    suspend fun searchSessions(nodeId: String?, query: String, limit: Int = 50): SessionSearchResponse =
        withContext(Dispatchers.IO) {
            val encoded = "q=" + percentEncode(query) + "&limit=" + limit.coerceIn(1, 50)
            val path = if (isHub) {
                if (nodeId == null || !isSafeNodeId(nodeId)) return@withContext SessionSearchResponse()
                "/node/" + nodeId + "/v1/sessions/search?" + encoded
            } else {
                "/v1/sessions/search?" + encoded
            }
            val pieces = path.split("?", limit = 2)
            Wire.json.decodeFromString(
                SessionSearchResponse.serializer(),
                http.get(pieces[0], pieces.getOrNull(1))
            )
        }

    suspend fun patchSession(nodeId: String?, sessionId: String, patch: AgentSessionPatch) =
        withContext(Dispatchers.IO) {
            val body = Wire.json.encodeToString(AgentSessionPatch.serializer(), patch)
            val path = if (isHub) "/node/" + sessionPathNode(nodeId) + "/v1/sessions/" + pathEncode(sessionId)
            else "/v1/sessions/" + pathEncode(sessionId)
            http.patch(path, body)
            Unit
        }

    /**
     * PATCH /v1/sessions/pinned-order — the ids listed swap into the leading pin
     * ranks, everything else stays put. Returns the node's full pinned order.
     */
    suspend fun reorderPinnedSessions(nodeId: String?, sessionIds: List<String>): PinnedSessionOrder =
        withContext(Dispatchers.IO) {
            // Serialised through kotlinx (not org.json) so the body is identical on device
            // and in unit tests, where org.json is a stub.
            val body = Wire.json.encodeToString(PinnedOrderRequest.serializer(), PinnedOrderRequest(sessionIds))
            val path = if (isHub) "/node/" + sessionPathNode(nodeId) + "/v1/sessions/pinned-order"
            else "/v1/sessions/pinned-order"
            Wire.json.decodeFromString(PinnedSessionOrder.serializer(), http.patch(path, body))
        }

    // MARK: - Hub administration

    suspend fun testHubNode(form: NodeFormData): TestNodeResponse = withContext(Dispatchers.IO) {
        requireHub()
        Wire.json.decodeFromString(TestNodeResponse.serializer(), http.post("/api/nodes/test", Wire.json.encodeToString(NodeFormData.serializer(), form)))
    }

    suspend fun addHubNode(form: NodeFormData): AddNodeResponse = withContext(Dispatchers.IO) {
        requireHub()
        Wire.json.decodeFromString(AddNodeResponse.serializer(), http.post("/api/nodes", Wire.json.encodeToString(NodeFormData.serializer(), form)))
    }

    suspend fun removeHubNode(nodeId: String): RemovedNodeResponse = withContext(Dispatchers.IO) {
        requireHub()
        Wire.json.decodeFromString(RemovedNodeResponse.serializer(), http.delete("/api/nodes/" + pathEncode(nodeId)))
    }

    suspend fun reorderHubNodes(nodeIds: List<String>): NodeOrderResponse = withContext(Dispatchers.IO) {
        requireHub()
        Wire.json.decodeFromString(
            NodeOrderResponse.serializer(),
            http.patch("/api/nodes/order", Wire.json.encodeToString(NodeOrderRequest.serializer(), NodeOrderRequest(nodeIds)))
        )
    }

    suspend fun hubSecurity(): HubSecurityState = withContext(Dispatchers.IO) {
        requireHub()
        Wire.json.decodeFromString(HubSecurityState.serializer(), http.get("/api/auth/session"))
    }

    suspend fun hubCredentials(): List<HubCredential> = withContext(Dispatchers.IO) {
        requireHub()
        Wire.json.decodeFromString(HubCredentialsResponse.serializer(), http.get("/api/auth/credentials")).credentials
    }

    /** Sign this device out of the Hub; the server clears its session cookie. */
    suspend fun hubLogout(): Unit = withContext(Dispatchers.IO) {
        requireHub()
        http.post("/api/auth/logout")
        Unit
    }

    suspend fun hubRevokeOtherSessions(): Int = withContext(Dispatchers.IO) {
        requireHub()
        Wire.json.decodeFromString(HubRevokeResponse.serializer(), http.post("/api/auth/sessions/revoke-others")).revoked
    }

    suspend fun delegations(): DelegationsResponse = withContext(Dispatchers.IO) {
        requireHub()
        Wire.json.decodeFromString(DelegationsResponse.serializer(), http.get("/api/delegations"))
    }

    /** These routes only exist on a Hub; a Direct Node answers 404 and the UI hides them. */
    private fun requireHub() {
        check(isHub) { "Hub administration is only available for a Hub source." }
    }

    // MARK: - Attention

    suspend fun attention(limit: Int = 500): AttentionSnapshot = withContext(Dispatchers.IO) {
        if (isHub) hubAttention(limit) else directAttention(limit)
    }

    private fun hubAttention(limit: Int): AttentionSnapshot {
        val response = Wire.json.decodeFromString(
            AttentionResponse.serializer(),
            http.get("/api/attention", "limit=" + limit.coerceIn(1, 500))
        )
        val items = mutableListOf<AttentionItem>()
        response.inputRequired.forEach { item ->
            items.add(
                AttentionItem(
                    connectionId = connection.id,
                    connectionName = connection.name,
                    nodeId = item.nodeId,
                    nodeName = item.nodeName,
                    sessionId = item.sessionId,
                    sessionNumber = item.sessionNumber?.toLong(),
                    storeInstanceId = null,
                    title = item.title,
                    outcome = null,
                    kind = "input_required",
                    attentionSeq = 0,
                    resumePath = validatedPath(item.resumePath),
                    pendingInteractionCount = item.pendingInteractionCount,
                )
            )
        }
        response.inbox.forEach { item ->
            items.add(
                AttentionItem(
                    connectionId = connection.id,
                    connectionName = connection.name,
                    nodeId = item.nodeId,
                    nodeName = item.nodeName,
                    sessionId = item.sessionId,
                    sessionNumber = item.sessionNumber?.toLong(),
                    storeInstanceId = item.storeInstanceId,
                    title = item.title,
                    outcome = item.outcome,
                    kind = "unseen",
                    attentionSeq = item.attentionSeq,
                    resumePath = validatedPath(item.resumePath),
                )
            )
        }
        return AttentionSnapshot(
            items = items,
            running = response.totalRunning,
            inputRequired = response.totalInputRequired,
            unseen = response.totalUnseen,
            hasMore = response.hasMore,
        )
    }

    /**
     * A Direct Node has no aggregate attention endpoint: the iOS client composes
     * single-kind /v1/attention pages into one snapshot. Same here.
     */
    private fun directAttention(limit: Int): AttentionSnapshot {
        val perKind = (limit / 2).coerceAtLeast(1)
        val input = fetchDirectAttentionPage("input_required", perKind)
        val unseen = fetchDirectAttentionPage("unseen", perKind)
        val items = mutableListOf<AttentionItem>()
        input.items.forEach { item ->
            items.add(
                AttentionItem(
                    connectionId = connection.id,
                    connectionName = connection.name,
                    nodeId = null,
                    nodeName = connection.name,
                    sessionId = item.sessionId,
                    sessionNumber = item.sessionNumber,
                    storeInstanceId = input.storeInstanceId,
                    title = item.longTitle?.takeIf { it.isNotBlank() }
                        ?: item.shortTitle?.takeIf { it.isNotBlank() } ?: item.sessionId,
                    outcome = item.outcome,
                    kind = "input_required",
                    attentionSeq = (item.attentionSeq ?: 0L).toInt(),
                    resumePath = resumePath(item.sessionId, item.sessionNumber),
                    pendingInteractionCount = item.pendingInteractionCount ?: 0,
                )
            )
        }
        unseen.items.forEach { item ->
            items.add(
                AttentionItem(
                    connectionId = connection.id,
                    connectionName = connection.name,
                    nodeId = null,
                    nodeName = connection.name,
                    sessionId = item.sessionId,
                    sessionNumber = item.sessionNumber,
                    storeInstanceId = unseen.storeInstanceId,
                    title = item.longTitle?.takeIf { it.isNotBlank() }
                        ?: item.shortTitle?.takeIf { it.isNotBlank() } ?: item.sessionId,
                    outcome = item.outcome ?: item.lifecycleState,
                    kind = "unseen",
                    attentionSeq = (item.attentionSeq ?: 0L).toInt(),
                    resumePath = resumePath(item.sessionId, item.sessionNumber),
                )
            )
        }
        return AttentionSnapshot(
            items = items,
            running = 0,
            inputRequired = input.items.size,
            unseen = unseen.items.size,
            hasMore = input.hasMore || unseen.hasMore,
        )
    }

    private fun fetchDirectAttentionPage(kind: String, limit: Int): DirectAttentionPage =
        Wire.json.decodeFromString(
            DirectAttentionPage.serializer(),
            http.get("/v1/attention", "kind=" + kind + "&limit=" + limit.coerceIn(1, 500))
        )

    /**
     * Exact per-item acknowledgement.
     * Hub: POST /api/attention/seen {store_instance_id, session_id, attention_seq}
     * Direct Node: POST /v1/sessions/{id}/attention/seen {store_instance_id, through_seq}
     */
    suspend fun markSeen(item: AttentionItem): SeenAttentionResponse = withContext(Dispatchers.IO) {
        if (isHub) {
            val body = Wire.json.encodeToString(
                AttentionSeenRequest.serializer(),
                AttentionSeenRequest(
                    storeInstanceId = item.storeInstanceId ?: "",
                    sessionId = item.sessionId,
                    attentionSeq = item.attentionSeq,
                )
            )
            Wire.json.decodeFromString(SeenAttentionResponse.serializer(), http.post("/api/attention/seen", body))
        } else {
            // The direct endpoint names the sequence field `through_seq`.
            val directBody = "{\"store_instance_id\":" + quote(item.storeInstanceId ?: "") +
                ",\"through_seq\":" + item.attentionSeq + "}"
            require(item.sessionId.isNotEmpty()) { "Invalid session identifier for attention acknowledgement." }
            http.post(
                "/v1/sessions/" + pathEncode(item.sessionId) + "/attention/seen",
                directBody,
                mapOf("X-Term-LLM-Attention-Source" to "phone"),
            )
            SeenAttentionResponse(cleared = 1)
        }
    }

    /** Bulk clear exists on a Hub only; a Direct Node answers 501-equivalent. */
    suspend fun clearAttention(): ClearAttentionResponse = withContext(Dispatchers.IO) {
        if (!isHub) throw ApiException(501, "Bulk attention clear is unavailable on a direct connection.")
        Wire.json.decodeFromString(ClearAttentionResponse.serializer(), http.post("/api/attention/clear", "{}"))
    }

    // MARK: - Chat locations

    fun mountRoot(): String = if (mountPath.isEmpty()) "/" else mountPath + "/"

    /** Direct node chat route: <mount>/chat/<number or id> (DirectNodeClient.resume_path). */
    fun resumePath(sessionId: String, sessionNumber: Long?): String {
        val base = mountPath + "/chat/"
        val suffix = if (sessionNumber != null && sessionNumber > 0) sessionNumber.toString() else sessionId
        return base + suffix
    }

    /** Absolute chat URL for a server-provided resume/proxy path, validated within the mount. */
    fun chatUrl(pathWithMount: String): HttpUrl? {
        val url = http.urlForAbsolute(pathWithMount) ?: return null
        if (!ServerAddress.isWithinMount(url, server)) return null
        return url
    }

    private fun validatedPath(path: String): String? =
        if (path.isBlank() || chatUrl(path) == null) null else path

    // MARK: - Helpers

    private fun com.termllm.app.model.HubNodeSession.toSummary(): SessionSummary = SessionSummary(
        id = id,
        number = number?.toLong(),
        title = longTitle?.takeIf { it.isNotBlank() } ?: shortTitle.takeIf { it.isNotBlank() } ?: ("Conversation " + id),
        projectName = null,
        resumePath = resumePath.takeIf { it.isNotBlank() },
        activeRun = activeRun ?: false,
        inputRequired = interactionRequired ?: false,
        unseen = false,
        pinned = pinned ?: false,
        lastMessageAt = lastMessageAt,
        messageCount = messageCount,
    )

    private fun sessionPathNode(nodeId: String?): String =
        if (nodeId != null && isSafeNodeId(nodeId)) nodeId else throw ApiException(0, "Invalid node identifier.")

    private fun isSafeNodeId(id: String): Boolean =
        id.isNotEmpty() && id != "." && id != ".." &&
            id.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }

    private fun pathEncode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun percentEncode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""
}
