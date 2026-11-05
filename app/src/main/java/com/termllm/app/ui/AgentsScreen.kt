package com.termllm.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.termllm.app.data.Connection
import com.termllm.app.data.ServerKind
import com.termllm.app.model.AgentSummary
import com.termllm.app.model.SourceState
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentsScreen(
    vm: AppViewModel,
    connections: List<Connection>,
    states: Map<String, SourceState>,
    onOpenAgent: (connectionId: String, nodeId: String?) -> Unit,
    onOpenChat: (connectionId: String, path: String) -> Unit,
    onAddSource: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAttention: () -> Unit = {},
    attentionBadge: Int = 0,
    onOpenAdmin: (connectionId: String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var signInTarget by remember { mutableStateOf<Connection?>(null) }
    var signingIn by remember { mutableStateOf(false) }
    val totalStates = states.values
    val loginMessage by vm.loginMessage.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Agents") },
                actions = {
                    IconButton(onClick = { vm.refreshAll() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh sources")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Agents", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = onOpenAttention) {
                        Text(if (attentionBadge > 0) "Attention · " + attentionBadge else "Attention")
                    }
                }
            }
            loginMessage?.let { text ->
                item(key = "login-message") {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth().testTag("login-message"),
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            TextButton(onClick = { vm.clearLoginMessage() }) { Text("Dismiss") }
                        }
                    }
                }
            }
            if (connections.isEmpty()) {
                item {
                    EmptyState(
                        title = "No sources yet",
                        body = "Add a term-llm Hub or Direct Node by HTTPS address. The server type is detected automatically.",
                        action = {
                            TextButton(onClick = onAddSource) { Text("Add a connection") }
                        }
                    )
                }
            }

            connections.forEach { connection ->
                val state = states[connection.id] ?: SourceState()
                item(key = "header-" + connection.id) {
                    ConnectionHeader(
                        connection = connection,
                        state = state,
                        onSignIn = { signInTarget = connection },
                        onPasskey = if (state.loginUrl != null) {
                            { vm.signInWithPasskey(connection, context) }
                        } else {
                            null
                        },
                        onRetry = { vm.refresh(connection.id) },
                        onAdmin = if (connection.kind == ServerKind.HUB) {
                            { onOpenAdmin(connection.id) }
                        } else {
                            null
                        },
                    )
                }
                if (state.agents.isEmpty() && !state.loading && state.error != null) {
                    item(key = "error-" + connection.id) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Could not load this source", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                                Text(state.error ?: "Unknown error", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                                TextButton(onClick = { vm.refresh(connection.id) }) { Text("Retry") }
                            }
                        }
                    }
                }
                if (state.agents.isEmpty() && !state.loading && state.error == null && state.needsAuth) {
                    item(key = "auth-" + connection.id) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = MaterialTheme.shapes.medium,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Sign in to this source", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    if (state.loginUrl != null)
                                        "This server uses passkeys. Sign-in opens the system browser for the passkey ceremony " +
                                            "and hands only a session cookie back to this app, never the passkey."
                                    else
                                        "Native requests need a bearer token. It is stored encrypted on this device and never placed in URLs.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (state.loginUrl != null) {
                                        TextButton(
                                            onClick = { vm.signInWithPasskey(connection, context) },
                                            modifier = Modifier.testTag("passkey-signin"),
                                        ) { Text("Sign in with passkey") }
                                    }
                                    TextButton(onClick = { signInTarget = connection }) {
                                        Text(if (state.loginUrl != null) "Use a token" else "Sign in")
                                    }
                                }
                            }
                        }
                    }
                }
                items(state.agents, key = { it.connectionId + ":" + (it.nodeId ?: "direct") }) { agent ->
                    AgentRow(
                        agent = agent,
                        onOpen = { onOpenAgent(agent.connectionId, agent.nodeId) },
                        onNewChat = {
                            val path = agent.newSessionPath
                            if (path != null) onOpenChat(agent.connectionId, path) else onOpenAgent(agent.connectionId, agent.nodeId)
                        }
                    )
                }
            }

            if (connections.isNotEmpty() && totalStates.any { it.loading }) {
                item {
                    Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
            }

            if (connections.isNotEmpty()) {
                item {
                    TextButton(onClick = onAddSource, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Text("  Add connection")
                    }
                }
            }
        }
    }

    signInTarget?.let { connection ->
        TokenDialog(
            title = "Sign in to " + connection.name,
            subtitle = "Paste the token the server issued for this connection. It is validated before it is kept.",
            busy = signingIn,
            onDismiss = { signInTarget = null },
            onSubmit = { token ->
                signingIn = true
                scope.launch {
                    val result = vm.signIn(connection, token)
                    signingIn = false
                    if (result.isSuccess) signInTarget = null
                }
            }
        )
    }
}

@Composable
private fun ConnectionHeader(
    connection: Connection,
    state: SourceState,
    onSignIn: () -> Unit,
    onRetry: () -> Unit,
    onAdmin: (() -> Unit)? = null,
    onPasskey: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(connection.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                when (connection.kind) {
                    ServerKind.HUB -> "Hub · " + connection.url
                    ServerKind.DIRECT_NODE -> "Direct Node · " + connection.url
                    ServerKind.UNKNOWN -> "Unidentified · " + connection.url
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            state.loading -> CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            state.needsAuth && onPasskey != null -> Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                // The server advertised its passkey login page: the ceremony runs in the
                // system browser and only the session comes back (iOS NativeLogin).
                TextButton(onClick = onPasskey, modifier = Modifier.testTag("passkey-signin")) { Text("Passkey") }
                TextButton(onClick = onSignIn) { Text("Token") }
            }
            state.needsAuth -> TextButton(onClick = onSignIn) { Text("Sign in") }
            state.error != null -> TextButton(onClick = onRetry) { Text("Retry") }
        }
        if (connection.kind == ServerKind.HUB && onAdmin != null && !state.needsAuth) {
            TextButton(onClick = onAdmin) { Text("Hub admin") }
        }
    }
}

@Composable
private fun AgentRow(agent: AgentSummary, onOpen: () -> Unit, onNewChat: () -> Unit) {
    Surface(
        onClick = onOpen,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier.size(36.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    agent.name.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusDot(if (agent.reachable) OkGreen else ErrorRed)
                    Text(agent.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                }
                Text(
                    buildString {
                        append(if (agent.reachable) "Online" else "Offline")
                        if (agent.version != null) append(" · ").append(agent.version)
                        val sessions = agent.sessions.size
                        if (sessions > 0) append(" · ").append(sessions).append(if (sessions == 1) " session" else " sessions")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (agent.inputRequiredCount > 0 || agent.unseenCount > 0) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (agent.inputRequiredCount > 0) {
                            CountChip(
                                agent.inputRequiredCount.toString() + " needs input",
                                MaterialTheme.colorScheme.onSecondaryContainer,
                                MaterialTheme.colorScheme.secondaryContainer,
                            )
                        }
                        if (agent.unseenCount > 0) {
                            CountChip(
                                agent.unseenCount.toString() + " unseen",
                                MaterialTheme.colorScheme.onTertiaryContainer,
                                MaterialTheme.colorScheme.tertiaryContainer,
                            )
                        }
                    }
                }
            }
            IconButton(onClick = onNewChat) {
                Icon(Icons.Filled.Edit, contentDescription = "New conversation")
            }
        }
    }
}
