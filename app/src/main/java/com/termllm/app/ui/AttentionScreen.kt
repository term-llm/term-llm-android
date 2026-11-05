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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.termllm.app.model.AttentionItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttentionScreen(
    vm: AppViewModel,
    items: List<AttentionItem>,
    totals: Triple<Int, Int, Int>,
    connections: List<com.termllm.app.data.Connection>,
    onOpenChat: (String, String) -> Unit,
) {
    var summary by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Attention") },
                actions = {
                    IconButton(onClick = { vm.refreshAll() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                    if (items.any { it.kind == "unseen" && it.canAcknowledge } && !busy) {
                        TextButton(onClick = {
                            busy = true
                            scope.launch {
                                var cleared = 0
                                var failed = 0
                                val failures = mutableListOf<String>()
                                connections.forEach { connection ->
                                    val outcome = vm.acknowledgeUnseen(connection.id)
                                    cleared += outcome.cleared
                                    failed += outcome.failed
                                    outcome.error?.let { failures.add(connection.name + ": " + it) }
                                }
                                busy = false
                                summary = when {
                                    failures.isNotEmpty() -> "Acknowledged " + cleared + " · " + failed +
                                        " failed · " + failures.first()
                                    failed > 0 -> "Acknowledged " + cleared + " · " + failed + " failed"
                                    else -> "Acknowledged " + cleared + " unseen result" + (if (cleared == 1) "" else "s")
                                }
                            }
                        }) { Text("Acknowledge unseen") }
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
            summary?.let { text ->
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text,
                            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CountChip(totals.first.toString() + " running", MaterialTheme.colorScheme.onPrimaryContainer, MaterialTheme.colorScheme.primaryContainer)
                    CountChip(totals.second.toString() + " needs input", MaterialTheme.colorScheme.onSecondaryContainer, MaterialTheme.colorScheme.secondaryContainer)
                    CountChip(totals.third.toString() + " unseen", MaterialTheme.colorScheme.onTertiaryContainer, MaterialTheme.colorScheme.tertiaryContainer)
                }
            }
            if (items.isEmpty()) {
                item {
                    EmptyState(
                        title = "Nothing needs attention",
                        body = "Input-required items and unseen results across your verified connections appear here."
                    )
                }
            }
            items(items, key = { it.connectionId + ":" + it.sessionId + ":" + it.kind + ":" + it.attentionSeq }) { item ->
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            CountChip(
                                if (item.kind == "input_required") "needs input" else "unseen",
                                if (item.kind == "input_required") MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onTertiaryContainer,
                                if (item.kind == "input_required") MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.tertiaryContainer,
                            )
                            Text(item.nodeName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(item.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        item.outcome?.takeIf { it.isNotBlank() }?.let {
                            Text("Result: " + it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (item.resumePath != null) {
                                TextButton(onClick = { onOpenChat(item.connectionId, item.resumePath) }) { Text("Open") }
                            }
                            if (item.canAcknowledge) {
                                TextButton(onClick = { vm.markSeen(item) }) { Text("Mark seen") }
                            }
                        }
                    }
                }
            }
        }
    }
}
