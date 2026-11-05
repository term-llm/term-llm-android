package com.termllm.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.termllm.app.model.AgentSessionPatch
import com.termllm.app.model.AgentSummary
import com.termllm.app.model.SessionSummary
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentDetailScreen(
    vm: AppViewModel,
    states: Map<String, com.termllm.app.model.SourceState>,
    connectionId: String,
    nodeId: String?,
    onBack: () -> Unit,
    onOpenChat: (String, String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val agent: AgentSummary? = states[connectionId]?.agents?.firstOrNull { it.nodeId == nodeId }
    var history by remember(connectionId, nodeId) { mutableStateOf<AppViewModel.SessionsLoad?>(null) }
    var paged by remember(connectionId, nodeId) { mutableStateOf<List<SessionSummary>>(emptyList()) }
    var cursor by remember(connectionId, nodeId) { mutableStateOf<String?>(null) }
    var loadingMore by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    var query by androidx.compose.runtime.saveable.rememberSaveable(connectionId, nodeId) { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<AppViewModel.SearchHit>?>(null) }
    var actionTarget by remember { mutableStateOf<SessionSummary?>(null) }
    var renameTarget by remember { mutableStateOf<SessionSummary?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(connectionId, nodeId, reload) {
        val load = vm.sessions(connectionId, nodeId)
        history = load
        paged = load.sessions
        cursor = load.nextCursor
    }

    val dashboardSessions = agent?.sessions ?: emptyList()
    val merged = (dashboardSessions + paged)
        .distinctBy { it.id }
        .sortedWith(
            compareByDescending<SessionSummary> { it.pinned }
                .thenBy { it.pinOrder ?: Long.MAX_VALUE }
                .thenByDescending { it.lastMessageAt ?: 0L }
        )

    fun movePinned(session: SessionSummary, delta: Int) {
        val pinned = merged.filter { it.pinned }
        val index = pinned.indexOfFirst { it.id == session.id }
        val target = index + delta
        if (index < 0 || target !in pinned.indices) return
        val ordered = pinned.map { it.id }.toMutableList().also { it.add(target, it.removeAt(index)) }
        scope.launch {
            val result = vm.reorderPinned(connectionId, nodeId, ordered)
            message = if (result.isSuccess) "Pinned order saved" else "Reorder failed: " + (result.exceptionOrNull()?.message ?: "")
            if (result.isSuccess) reload += 1
        }
    }

    fun applyPatch(session: SessionSummary, patch: AgentSessionPatch, label: String) {
        scope.launch {
            val result = vm.patchSession(connectionId, nodeId, session.id, patch)
            message = if (result.isSuccess) label else "Failed: " + (result.exceptionOrNull()?.message ?: "unknown")
            if (result.isSuccess) reload += 1
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(agent?.name ?: "Agent") },
                actions = {
                    IconButton(onClick = { reload += 1; vm.refresh(connectionId) }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Button(
                    onClick = {
                        val path = agent?.newSessionPath ?: agent?.proxyPath
                        if (path != null) onOpenChat(connectionId, path)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text("  New conversation")
                }
            }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search this agent's conversations") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotBlank()) {
                            TextButton(onClick = {
                                scope.launch {
                                    vm.search(connectionId, nodeId, query).fold(
                                        onSuccess = { hits = it },
                                        onFailure = { message = "Search failed: " + (it.message ?: "") },
                                    )
                                }
                            }) { Text("Go") }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            message?.let { text ->
                item {
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                        Text(
                            text,
                            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
            history?.error?.let { error ->
                item {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
                        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                "Could not load conversations: " + error,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { reload += 1 }) { Text("Retry") }
                        }
                    }
                }
            }
            val displayed = hits
            if (displayed != null) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(displayed.size.toString() + " matches", style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = { hits = null; query = "" }) { Text("Clear") }
                    }
                }
                items(displayed, key = { "hit-" + it.id }) { hit ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(hit.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        hit.snippet?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = {
                            val path = agent?.proxyPath
                            if (path != null) onOpenChat(connectionId, path)
                        }) { Text("Open agent chat") }
                    }
                }
            } else {
                if (agent != null) {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                            LabeledValue("Status", if (agent.reachable) "Online" else "Offline")
                            LabeledValue("Version", agent.version ?: "—")
                            LabeledValue("Sessions", merged.size.toString())
                        }
                    }
                }
                item { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }
                if (merged.isEmpty()) {
                    item {
                        if (history == null) {
                            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
                                CircularProgressIndicator()
                            }
                        } else if (history?.error == null) {
                            EmptyState(
                                title = "No conversations yet",
                                body = "Start a conversation, or open the web chat to browse this agent's history."
                            )
                        }
                    }
                }
                items(merged, key = { it.id }) { session ->
                    SessionRow(session, onClick = {
                        val path = session.resumePath ?: agent?.proxyPath
                        if (path != null) onOpenChat(connectionId, path)
                    }, onLongClick = { actionTarget = session })
                }
                if (cursor != null) {
                    item {
                        TextButton(
                            enabled = !loadingMore,
                            onClick = {
                                loadingMore = true
                                scope.launch {
                                    val next = vm.sessions(connectionId, nodeId, 30, cursor)
                                    paged = (paged + next.sessions).distinctBy { it.id }
                                    cursor = next.nextCursor
                                    loadingMore = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (loadingMore) "Loading…" else "Load more")
                        }
                    }
                }
            }
        }
    }

    actionTarget?.let { session ->
        AlertDialog(
            onDismissRequest = { actionTarget = null },
            title = { Text(session.title) },
            text = { Text("Conversation actions. Pin order and titles belong to the server.", style = MaterialTheme.typography.bodySmall) },
            confirmButton = {
                TextButton(onClick = {
                    applyPatch(session, AgentSessionPatch(pinned = !session.pinned), if (session.pinned) "Unpinned" else "Pinned")
                    actionTarget = null
                }) { Text(if (session.pinned) "Unpin" else "Pin") }
            },
            dismissButton = {
                Row {
                    if (session.pinned) {
                        TextButton(onClick = { movePinned(session, -1); actionTarget = null }) { Text("Move up") }
                        TextButton(onClick = { movePinned(session, 1); actionTarget = null }) { Text("Move down") }
                    }
                    TextButton(onClick = { renameTarget = session; actionTarget = null }) { Text("Rename") }
                    TextButton(onClick = {
                        applyPatch(session, AgentSessionPatch(archived = true), "Archived")
                        actionTarget = null
                    }) { Text("Archive") }
                }
            },
        )
    }

    renameTarget?.let { session ->
        var name by remember(session.id) { mutableStateOf(session.title) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename conversation") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    applyPatch(session, AgentSessionPatch(name = name.trim()), "Renamed")
                    renameTarget = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(session: SessionSummary, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (session.pinned) Text("★", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(
                session.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f, fill = false)
            )
        }
        Text(
            buildString {
                session.projectName?.let { append(it).append(" · ") }
                session.number?.let { append("#").append(it).append(" · ") }
                val when_ = relativeTime(session.lastMessageAt)
                if (when_.isNotEmpty()) append(when_)
            }.trim().trimEnd('·').trim(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (session.inputRequired) CountChip("needs input", MaterialTheme.colorScheme.onSecondaryContainer, MaterialTheme.colorScheme.secondaryContainer)
            if (session.activeRun) CountChip("running", MaterialTheme.colorScheme.onPrimaryContainer, MaterialTheme.colorScheme.primaryContainer)
            if (session.unseen) CountChip("unseen", MaterialTheme.colorScheme.onTertiaryContainer, MaterialTheme.colorScheme.tertiaryContainer)
        }
    }
}
