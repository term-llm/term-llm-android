package com.termllm.app.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire models ported from the iOS app (TermLLM/HubClient.swift,
 * TermLLM/DirectNodeClient.swift, TermLLM/HubSessionBrowsing.swift).
 * The remote Hub protocol is pinned to core 2496b5fd.
 *
 * Every field except identity/status is optional with a default so older
 * servers keep decoding, exactly like the Swift Codable models.
 */

// MARK: - Health / discovery

@Serializable
data class HealthResponse(
    val status: String = "",
    val role: String? = null,
    val error: String? = null,
    val agent: String? = null,
    val version: String? = null,
    val capabilities: List<String>? = null,
)

enum class ServerRole { UNKNOWN, DIRECT_NODE, HUB }

// MARK: - Hub /api/nodes

@Serializable
data class NodesResponse(
    val nodes: List<HubNode> = emptyList(),
    @SerialName("resolver_error") val resolverError: String? = null,
)

@Serializable
data class HubNode(
    val id: String,
    val name: String = "",
    val source: String? = null,
    val connection: String? = null,
    val url: String = "",
    @SerialName("base_path") val basePath: String = "",
    @SerialName("proxy_path") val proxyPath: String = "",
    @SerialName("new_session_path") val newSessionPath: String = "",
    @SerialName("has_token") val hasToken: Boolean = false,
    val status: HubNodeStatus? = null,
    val sessions: HubNodeSessions? = null,
    val diagnostics: List<HubNodeDiagnostic> = emptyList(),
)

@Serializable
data class HubNodeStatus(
    val reachable: Boolean = false,
    val state: String = "",
    @SerialName("latency_ms") val latencyMs: Int = 0,
    val version: String? = null,
    val agent: String? = null,
    val capabilities: List<String>? = null,
    val error: String? = null,
)

@Serializable
data class HubNodeDiagnostic(
    val severity: String = "",
    val code: String = "",
    val message: String = "",
)

@Serializable
data class HubNodeSessions(
    @SerialName("count_label") val countLabel: String = "",
    @SerialName("has_more") val hasMore: Boolean? = null,
    @SerialName("active_count") val activeCount: Int? = null,
    @SerialName("input_required_count") val inputRequiredCount: Int? = null,
    @SerialName("unseen_count") val unseenCount: Int? = null,
    @SerialName("attention_capability") val attentionCapability: String? = null,
    @SerialName("attention_last_success_at") val attentionLastSuccessAt: Long? = null,
    val active: List<HubNodeSession> = emptyList(),
    val recent: List<HubNodeSession> = emptyList(),
    @SerialName("resume_path") val resumePath: String? = null,
)

@Serializable
data class HubNodeSession(
    val id: String,
    val number: Int? = null,
    @SerialName("short_title") val shortTitle: String = "",
    @SerialName("long_title") val longTitle: String? = null,
    val pinned: Boolean? = null,
    @SerialName("active_run") val activeRun: Boolean? = null,
    @SerialName("interaction_required") val interactionRequired: Boolean? = null,
    @SerialName("pending_interaction_count") val pendingInteractionCount: Int? = null,
    @SerialName("pending_interaction_kinds") val pendingInteractionKinds: List<String>? = null,
    @SerialName("last_message_at") val lastMessageAt: Long? = null,
    @SerialName("message_count") val messageCount: Int? = null,
    @SerialName("resume_path") val resumePath: String = "",
)

// MARK: - Hub /api/attention

@Serializable
data class AttentionResponse(
    @SerialName("total_running") val totalRunning: Int = 0,
    @SerialName("total_input_required") val totalInputRequired: Int = 0,
    @SerialName("total_unseen") val totalUnseen: Int = 0,
    val nodes: List<HubAttentionNode> = emptyList(),
    @SerialName("input_required") val inputRequired: List<HubInputRequiredItem> = emptyList(),
    val inbox: List<HubAttentionInboxItem> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
data class HubAttentionNode(
    @SerialName("node_id") val nodeId: String = "",
    @SerialName("node_name") val nodeName: String = "",
    @SerialName("capability_state") val capabilityState: String = "",
    val stale: Boolean = false,
    @SerialName("last_success_at") val lastSuccessAt: String? = null,
    @SerialName("last_error") val lastError: String? = null,
    @SerialName("running_count") val runningCount: Int = 0,
    @SerialName("input_required_count") val inputRequiredCount: Int = 0,
    @SerialName("unseen_count") val unseenCount: Int = 0,
    @SerialName("has_green_indicator") val hasGreenIndicator: Boolean = false,
)

@Serializable
data class HubInputRequiredItem(
    @SerialName("node_id") val nodeId: String = "",
    @SerialName("node_name") val nodeName: String = "",
    @SerialName("session_id") val sessionId: String = "",
    @SerialName("session_number") val sessionNumber: Int? = null,
    val title: String = "",
    @SerialName("pending_interaction_count") val pendingInteractionCount: Int = 0,
    @SerialName("pending_interaction_kinds") val pendingInteractionKinds: List<String>? = null,
    @SerialName("required_since") val requiredSince: String? = null,
    @SerialName("resume_path") val resumePath: String = "",
    val stale: Boolean? = null,
)

@Serializable
data class HubAttentionInboxItem(
    @SerialName("node_id") val nodeId: String = "",
    @SerialName("node_name") val nodeName: String = "",
    @SerialName("session_id") val sessionId: String = "",
    @SerialName("store_instance_id") val storeInstanceId: String? = null,
    @SerialName("session_number") val sessionNumber: Int? = null,
    val title: String = "",
    val outcome: String = "",
    @SerialName("terminal_at") val terminalAt: String? = null,
    @SerialName("attention_seq") val attentionSeq: Int = 0,
    @SerialName("resume_path") val resumePath: String = "",
)

@Serializable
data class ClearAttentionResponse(val cleared: Int = 0, val failed: Int = 0)

@Serializable
data class SeenAttentionResponse(val cleared: Int = 0)

@Serializable
data class AttentionSeenRequest(
    @SerialName("store_instance_id") val storeInstanceId: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("attention_seq") val attentionSeq: Int,
)

// MARK: - Hub /api/delegations

@Serializable
data class DelegationsResponse(val delegations: List<HubDelegation> = emptyList())

@Serializable
data class HubDelegation(
    val id: String,
    @SerialName("origin_node") val originNode: String = "",
    @SerialName("target_node") val targetNode: String = "",
    @SerialName("agent_name") val agentName: String? = null,
    val prompt: String? = null,
    val model: String? = null,
    val cwd: String? = null,
    @SerialName("job_id") val jobId: String? = null,
    @SerialName("run_id") val runId: String? = null,
    val status: String = "",
    val depth: Int = 0,
    val chain: List<String>? = null,
    @SerialName("parent_delegation_id") val parentDelegationId: String? = null,
    val response: String? = null,
    val error: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
)

// MARK: - Direct node /v1/attention

@Serializable
data class DirectAttentionItem(
    @SerialName("session_id") val sessionId: String,
    @SerialName("session_number") val sessionNumber: Long? = null,
    @SerialName("response_id") val responseId: String? = null,
    val kind: String? = null,
    @SerialName("lifecycle_state") val lifecycleState: String? = null,
    @SerialName("attention_seq") val attentionSeq: Long? = null,
    @SerialName("short_title") val shortTitle: String? = null,
    @SerialName("long_title") val longTitle: String? = null,
    val outcome: String? = null,
    @SerialName("terminal_at") val terminalAt: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("interaction_required") val interactionRequired: Boolean? = null,
    @SerialName("pending_interaction_count") val pendingInteractionCount: Int? = null,
    @SerialName("pending_interaction_kinds") val pendingInteractionKinds: List<String>? = null,
    @SerialName("interaction_required_since") val interactionRequiredSince: String? = null,
    @SerialName("seen_through_seq") val seenThroughSeq: Long? = null,
    @SerialName("seen_rev") val seenRev: Long? = null,
)

@Serializable
data class DirectAttentionPage(
    @SerialName("protocol_version") val protocolVersion: Int? = null,
    @SerialName("store_instance_id") val storeInstanceId: String = "",
    @SerialName("snapshot_version") val snapshotVersion: Long? = null,
    val items: List<DirectAttentionItem> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
    @SerialName("has_more") val hasMore: Boolean = false,
)

// MARK: - Direct node /v1/sessions/status

@Serializable
data class DirectNodeSessionEntry(
    val id: String,
    val number: Long? = null,
    @SerialName("project_id") val projectId: String? = null,
    @SerialName("project_name") val projectName: String? = null,
    @SerialName("short_title") val shortTitle: String = "",
    @SerialName("long_title") val longTitle: String? = null,
    val pinned: Boolean? = null,
    @SerialName("active_run") val activeRun: Boolean? = null,
    @SerialName("pin_order") val pinOrder: Long? = null,
    @SerialName("message_count") val messageCount: Int? = null,
    @SerialName("last_message_at") val lastMessageAt: Long? = null,
    @SerialName("attention_store_instance_id") val attentionStoreInstanceId: String? = null,
    @SerialName("attention_seq") val attentionSeq: Long? = null,
    @SerialName("seen_through_seq") val seenThroughSeq: Long? = null,
    @SerialName("attention_unseen") val attentionUnseen: Boolean? = null,
    @SerialName("attention_outcome") val attentionOutcome: String? = null,
    @SerialName("interaction_required") val interactionRequired: Boolean? = null,
    @SerialName("pending_interaction_count") val pendingInteractionCount: Int? = null,
    @SerialName("pending_interaction_kinds") val pendingInteractionKinds: List<String>? = null,
)

@Serializable
data class DirectNodeSessionsStatus(val sessions: List<DirectNodeSessionEntry> = emptyList())

// MARK: - GET /v1/sessions (web sidebar listing) + PATCH

@Serializable
data class AgentSessionEntry(
    val id: String,
    val number: Long? = null,
    val name: String? = null,
    @SerialName("short_title") val shortTitle: String? = null,
    @SerialName("long_title") val longTitle: String? = null,
    @SerialName("project_name") val projectName: String? = null,
    val archived: Boolean? = null,
    val pinned: Boolean? = null,
    @SerialName("pin_order") val pinOrder: Long? = null,
    @SerialName("created_at") val createdAt: Long? = null,
    @SerialName("last_message_at") val lastMessageAt: Long? = null,
    @SerialName("message_count") val messageCount: Int? = null,
    @SerialName("attention_store_instance_id") val attentionStoreInstanceId: String? = null,
    @SerialName("attention_seq") val attentionSeq: Long? = null,
    @SerialName("attention_unseen") val attentionUnseen: Boolean? = null,
    @SerialName("parent_session_id") val parentSessionId: String? = null,
)

@Serializable
data class AgentSessionPage(
    val sessions: List<AgentSessionEntry> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
)

/** Request body for PATCH /v1/sessions/pinned-order. */
// MARK: - Hub security (passkey session + credentials)

@Serializable
data class HubSecurityState(
    val administrator: String = "",
    val session: HubSessionInfo? = null,
    @SerialName("recently_authenticated") val recentlyAuthenticated: Boolean = false,
    @SerialName("active_sessions") val activeSessions: Int = 0,
)

@Serializable
data class HubSessionInfo(
    @SerialName("session_id") val sessionId: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("last_seen_at") val lastSeenAt: String = "",
    @SerialName("user_agent") val userAgent: String = "",
    @SerialName("peer") val peer: String = "",
)

@Serializable
data class HubCredential(
    @SerialName("record_id") val recordId: String = "",
    @SerialName("display_name") val displayName: String = "",
    val transports: List<String>? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("last_used_at") val lastUsedAt: String = "",
)

@Serializable
data class HubCredentialsResponse(val credentials: List<HubCredential> = emptyList())

@Serializable
data class HubRevokeResponse(val revoked: Int = 0)

// MARK: - Hub administration (nodes, delegations)

@Serializable
data class NodeFormData(
    val name: String,
    val url: String,
    val token: String,
)

@Serializable
data class TestNodeResponse(val status: HubNodeStatus? = null)

@Serializable
data class AddNodeResponse(val id: String? = null, val warning: String? = null)

@Serializable
data class RemovedNodeResponse(val removed: String? = null)

@Serializable
data class NodeOrderRequest(@SerialName("node_ids") val nodeIds: List<String> = emptyList())

@Serializable
data class NodeOrderResponse(@SerialName("node_ids") val nodeIds: List<String> = emptyList())

@Serializable
data class PinnedOrderRequest(@SerialName("session_ids") val sessionIds: List<String> = emptyList())

/** Answer to PATCH /v1/sessions/pinned-order: every pinned session in rank order. */
@Serializable
data class PinnedSessionOrder(
    val pinned: List<Position> = emptyList(),
) {
    @Serializable
    data class Position(val id: String, @SerialName("pin_order") val pinOrder: Long? = null)
}

@Serializable
data class AgentSessionPatch(
    val name: String? = null,
    val archived: Boolean? = null,
    val pinned: Boolean? = null,
)

@Serializable
data class AgentSessionPatchResult(
    val id: String,
    val name: String? = null,
    @SerialName("short_title") val shortTitle: String? = null,
    @SerialName("long_title") val longTitle: String? = null,
    val archived: Boolean? = null,
    val pinned: Boolean? = null,
    @SerialName("pin_order") val pinOrder: Long? = null,
)

// MARK: - Session search

@Serializable
data class SessionSearchEntry(
    val id: String,
    val number: Long? = null,
    @SerialName("short_title") val shortTitle: String? = null,
    @SerialName("long_title") val longTitle: String? = null,
    val pinned: Boolean? = null,
    @SerialName("last_message_at") val lastMessageAt: Long? = null,
    @SerialName("message_count") val messageCount: Int? = null,
    val snippet: String? = null,
    @SerialName("attention_unseen") val attentionUnseen: Boolean? = null,
)

@Serializable
data class SessionSearchResponse(val sessions: List<SessionSearchEntry> = emptyList())

// MARK: - Errors

@Serializable
data class ApiErrorBody(
    val error: String? = null,
    val message: String? = null,
    val type: String? = null,
    val status: Int? = null,
)
