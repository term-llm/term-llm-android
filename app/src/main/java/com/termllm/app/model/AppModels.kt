package com.termllm.app.model

import com.termllm.app.data.ServerKind

/** One agent row: a Hub node, or the Direct Node itself. */
data class AgentSummary(
    val connectionId: String,
    val connectionName: String,
    val kind: ServerKind,
    val nodeId: String?,
    val name: String,
    val reachable: Boolean,
    val state: String,
    val version: String?,
    val activeCount: Int,
    val inputRequiredCount: Int,
    val unseenCount: Int,
    val sessions: List<SessionSummary>,
    val newSessionPath: String?,
    val proxyPath: String?,
    val hasToken: Boolean,
    val diagnostics: List<String> = emptyList(),
) {
    val hasAttention: Boolean get() = inputRequiredCount > 0 || unseenCount > 0
}

data class SessionSummary(
    val id: String,
    val number: Long?,
    val title: String,
    val projectName: String?,
    val resumePath: String?,
    val activeRun: Boolean,
    val inputRequired: Boolean,
    val unseen: Boolean,
    val pinned: Boolean,
    val pinOrder: Long? = null,
    val lastMessageAt: Long?,
    val messageCount: Int?,
)

/** One actionable Attention row, from a Hub or a Direct Node. */
data class AttentionItem(
    val connectionId: String,
    val connectionName: String,
    val nodeId: String?,
    val nodeName: String,
    val sessionId: String,
    val sessionNumber: Long?,
    val storeInstanceId: String?,
    val title: String,
    val outcome: String?,
    val kind: String,
    val attentionSeq: Int,
    val resumePath: String?,
    val pendingInteractionCount: Int = 0,
) {
    /** Hub input-required rows carry no store instance or sequence, so they are not
     *  individually acknowledgeable; inbox and Direct Node rows are. */
    val canAcknowledge: Boolean get() = !storeInstanceId.isNullOrEmpty() && attentionSeq > 0
}

data class AttentionSnapshot(
    val items: List<AttentionItem> = emptyList(),
    val running: Int = 0,
    val inputRequired: Int = 0,
    val unseen: Int = 0,
    val hasMore: Boolean = false,
) {
    val badge: Int get() = inputRequired + unseen
}

/** Per-source UI state: loading, authorization and last error are independent. */
data class SourceState(
    val loading: Boolean = false,
    val error: String? = null,
    val needsAuth: Boolean = false,
    /** Login page the server advertised on 401 (`X-Term-LLM-Login-URL`): passkey sign-in is possible. */
    val loginUrl: String? = null,
    val agents: List<AgentSummary> = emptyList(),
    val attention: AttentionSnapshot = AttentionSnapshot(),
    /** Hub nodes from the last successful refresh (empty for Direct Nodes). */
    val hubNodes: List<HubNode> = emptyList(),
    val lastSyncedAt: Long? = null,
)
