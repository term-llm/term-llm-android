package com.termllm.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.termllm.app.model.AgentSummary
import com.termllm.app.model.SourceState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatChooser(
    vm: AppViewModel,
    states: Map<String, SourceState>,
    onOpen: (String, String) -> Unit,
) {
    val agents = vm.agents(states).filter { it.reachable }
    Scaffold(topBar = { TopAppBar(title = { Text("Chat") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Text(
                    "Choose an agent",
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium
                )
                Text(
                    "Chat opens the server's own web UI with this app's native session. The agent's page identifies the conversation.",
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (agents.isEmpty()) {
                item {
                    EmptyState(
                        title = "No reachable agents",
                        body = "Add a connection on the Agents tab first; a token is only needed if the server asks for one."
                    )
                }
            }
            items(agents, key = { it.connectionId + (it.nodeId ?: "direct") }) { agent ->
                AgentChoice(agent) {
                    val path = agent.newSessionPath ?: agent.proxyPath
                    if (path != null) onOpen(agent.connectionId, path)
                }
            }
        }
    }
}

@Composable
private fun AgentChoice(agent: AgentSummary, onOpen: () -> Unit) {
    TextButton(onClick = onOpen) {
        Column {
            Text(agent.name)
            Text(
                agent.connectionName + " · " + (if (agent.reachable) "online" else "offline"),
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall
            )
        }
    }
}
