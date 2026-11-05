package com.termllm.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.termllm.app.model.HubDelegation
import com.termllm.app.model.HubNode
import com.termllm.app.model.NodeFormData
import kotlinx.coroutines.launch

/**
 * Hub administration: the nodes this Hub proxies, and the delegations it has run.
 * Only reachable for a Hub source (`/api/nodes*`, `/api/delegations`); the entry
 * point is hidden for Direct Nodes, whose server has no such routes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HubAdminScreen(
    vm: AppViewModel,
    states: Map<String, com.termllm.app.model.SourceState>,
    connectionId: String,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val state = states[connectionId]
    var nodes by remember { mutableStateOf<List<HubNode>>(emptyList()) }
    var delegations by remember { mutableStateOf<List<HubDelegation>>(emptyList()) }
    var security by remember { mutableStateOf<com.termllm.app.model.HubSecurityState?>(null) }
    var credentials by remember { mutableStateOf<List<com.termllm.app.model.HubCredential>>(emptyList()) }
    var tab by remember { mutableStateOf("nodes") }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var testMessage by remember { mutableStateOf<String?>(null) }
    var removeTarget by remember { mutableStateOf<HubNode?>(null) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(connectionId, reload) {
        loading = true
        delegations = vm.delegations(connectionId).getOrElse {
            message = "Could not load delegations: " + (it.message ?: "unknown")
            emptyList()
        }
        security = vm.hubSecurity(connectionId).getOrElse {
            message = "Could not load security: " + (it.message ?: "unknown")
            null
        }
        credentials = vm.hubCredentials(connectionId).getOrElse { emptyList() }
        loading = false
    }
    LaunchedEffect(state?.agents) {
        nodes = vm.nodes(connectionId)
    }

    fun reorder(node: HubNode, delta: Int) {
        val ids = nodes.map { it.id }
        val index = ids.indexOf(node.id)
        val target = index + delta
        if (index < 0 || target !in ids.indices) return
        val ordered = ids.toMutableList().also { it.add(target, it.removeAt(index)) }
        scope.launch {
            vm.reorderHubNodes(connectionId, ordered).fold(
                onSuccess = { saved -> nodes = nodes.sortedBy { saved.indexOf(it.id).takeIf { i -> i >= 0 } ?: Int.MAX_VALUE }; message = "Node order saved" },
                onFailure = { message = "Reorder failed: " + (it.message ?: "unknown") },
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hub admin") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = { reload += 1; vm.refresh(connectionId) }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                },
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = tab == "nodes", onClick = { tab = "nodes" }, label = { Text("Nodes · " + nodes.size) })
                    FilterChip(selected = tab == "delegations", onClick = { tab = "delegations" }, label = { Text("Delegations · " + delegations.size) })
                    FilterChip(selected = tab == "security", onClick = { tab = "security" }, label = { Text("Security") })
                }
            }
            message?.let { text ->
                item {
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                        Text(text, Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (tab == "nodes") {
                item {
                    TextButton(onClick = { adding = true; testMessage = null }) { Text("Add node") }
                }
                if (nodes.isEmpty() && loading) {
                    item {
                        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }
                itemsIndexed(nodes) { index, node ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(node.name.ifBlank { node.id }, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text(
                            buildString {
                                append(if (node.status?.reachable == true) "Online" else "Offline")
                                node.status?.version?.let { append(" · ").append(it) }
                                node.url.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(enabled = index > 0, onClick = { reorder(node, -1) }) { Text("Move up") }
                            TextButton(enabled = index < nodes.lastIndex, onClick = { reorder(node, 1) }) { Text("Move down") }
                            TextButton(onClick = { removeTarget = node }) { Text("Remove") }
                        }
                    }
                    HorizontalDivider()
                }
            } else if (tab == "security") {
                items(securityRows(security, credentials)) { row ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(row.first, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(row.second, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                item {
                    // The WebAuthn ceremony itself runs in the system browser (as iOS's
                    // ASWebAuthenticationSession does); this app only redeems the one-use
                    // code and imports the session cookie. Registering more passkeys and
                    // re-authentication stay on the server's own pages.
                    val context = androidx.compose.ui.platform.LocalContext.current
                    Text(
                        "Passkey sign-in opens the system browser for the ceremony and hands only a session cookie back " +
                            "to this app. Additional passkeys and re-authentication are managed on the Hub's own pages.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    vm.connection(connectionId)?.let { connection ->
                        TextButton(onClick = { vm.signInWithPasskey(connection, context) }) { Text("Sign in with passkey") }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = {
                            scope.launch {
                                vm.hubRevokeOthers(connectionId).fold(
                                    onSuccess = { count -> message = "Revoked " + count + " other session" + (if (count == 1) "" else "s") },
                                    onFailure = { message = "Revoke failed: " + (it.message ?: "unknown") },
                                )
                                reload += 1
                            }
                        }) { Text("Revoke other sessions") }
                        TextButton(onClick = {
                            scope.launch {
                                vm.hubLogout(connectionId).fold(
                                    onSuccess = { message = "Signed out of this Hub"; onBack() },
                                    onFailure = { message = "Sign out failed: " + (it.message ?: "unknown") },
                                )
                            }
                        }) { Text("Sign out") }
                    }
                }
            } else {
                val primary = delegations
                if (primary.isEmpty() && !loading) {
                    item { EmptyState(title = "No delegations", body = "This Hub has not delegated any runs.") }
                }
                items(primary, key = { it.id }) { delegation ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            (delegation.agentName ?: delegation.targetNode.ifBlank { "delegation" }),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            delegation.status + " · " + delegation.originNode + " → " + delegation.targetNode +
                                if (delegation.depth > 0) " · depth " + delegation.depth else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        (delegation.prompt ?: delegation.error)?.takeIf { it.isNotBlank() }?.let { text ->
                            Text(text.take(160), style = MaterialTheme.typography.bodySmall)
                        }
                        Text(delegation.createdAt, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    if (adding) {
        AddNodeDialog(
            testMessage = testMessage,
            onDismiss = { adding = false; testMessage = null },
            onTest = { form ->
                scope.launch {
                    testMessage = vm.testHubNode(connectionId, form).fold(
                        onSuccess = { status ->
                            status?.let {
                                "Test: " + (if (it.reachable) "reachable" else "unreachable") +
                                    (it.version?.let { v -> " · " + v } ?: "") +
                                    (if (it.latencyMs > 0) " · " + it.latencyMs + " ms" else "")
                            } ?: "Test: no status returned"
                        },
                        onFailure = { "Test failed: " + (it.message ?: "unknown") },
                    )
                }
            },
            onSave = { form ->
                scope.launch {
                    vm.addHubNode(connectionId, form).fold(
                        onSuccess = { response ->
                            message = "Added node " + (response.id ?: "") + (response.warning?.let { " · " + it } ?: "")
                            nodes = vm.nodes(connectionId)
                            adding = false
                        },
                        onFailure = { message = "Add failed: " + (it.message ?: "unknown") },
                    )
                }
            },
        )
    }

    removeTarget?.let { node ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text("Remove " + node.name.ifBlank { node.id } + "?") },
            text = { Text("The Hub stops proxying this node. Its own server keeps running.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        vm.removeHubNode(connectionId, node.id).fold(
                            onSuccess = { message = "Removed " + node.id; nodes = vm.nodes(connectionId) },
                            onFailure = { message = "Remove failed: " + (it.message ?: "unknown") },
                        )
                    }
                    removeTarget = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removeTarget = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AddNodeDialog(
    testMessage: String? = null,
    onDismiss: () -> Unit,
    onTest: (NodeFormData) -> Unit,
    onSave: (NodeFormData) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add node") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("Node address") }, singleLine = true)
                OutlinedTextField(value = token, onValueChange = { token = it }, label = { Text("Node token") }, singleLine = true)
                Text(
                    "The token is sent to the Hub once; this app never stores it.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                testMessage?.let { text ->
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                        Text(text, Modifier.fillMaxWidth().padding(8.dp), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(NodeFormData(name.trim(), url.trim(), token.trim())) }) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onTest(NodeFormData(name.trim(), url.trim(), token.trim())) }) { Text("Test") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** Human-readable security facts, in the order the Hub reports them. */
private fun securityRows(
    security: com.termllm.app.model.HubSecurityState?,
    credentials: List<com.termllm.app.model.HubCredential>,
): List<Pair<String, String>> {
    if (security == null) return listOf("Status" to "Security details unavailable")
    val rows = mutableListOf(
        "Administrator" to security.administrator.ifBlank { "unknown" },
        "Active sessions" to security.activeSessions.toString(),
        "This session" to if (security.recentlyAuthenticated) "passkey checked recently" else "passkey not checked recently",
    )
    security.session?.let { session ->
        if (session.sessionId.isNotBlank()) rows.add("Session id" to session.sessionId)
        if (session.lastSeenAt.isNotBlank()) rows.add("Last seen" to session.lastSeenAt)
        if (session.peer.isNotBlank()) rows.add("Peer" to session.peer)
    }
    if (credentials.isEmpty()) {
        rows.add("Passkeys" to "none registered")
    } else {
        credentials.forEach { credential ->
            rows.add(
                "Passkey" to credential.displayName.ifBlank { credential.recordId } +
                    if (credential.lastUsedAt.isNotBlank()) " · last used " + credential.lastUsedAt else "",
            )
        }
    }
    return rows
}
