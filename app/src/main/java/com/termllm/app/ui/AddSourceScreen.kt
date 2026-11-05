package com.termllm.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.termllm.app.BuildConfig
import com.termllm.app.data.Connection
import com.termllm.app.data.ServerKind
import com.termllm.app.model.ServerRole
import kotlinx.coroutines.launch

/**
 * Add or edit one saved source. Mirrors the iOS connection editor: the server
 * type is detected by a bounded credential-free /healthz probe, never guessed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSourceScreen(
    vm: AppViewModel,
    existing: Connection?,
    onDone: () -> Unit,
    onBack: () -> Unit,
    /** Server from a `termllm://connect` pairing link: pre-filled and probed, never saved by itself. */
    prefillUrl: String? = null,
) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(existing?.url ?: prefillUrl ?: "") }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var token by remember { mutableStateOf("") }
    var probing by remember { mutableStateOf(false) }
    var probe by remember { mutableStateOf<ProbeResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun runProbe() {
        probing = true
        error = null
        probe = null
        scope.launch {
            vm.detect(url).fold(
                onSuccess = { probe = it },
                onFailure = { error = it.message ?: it.toString() }
            )
            probing = false
        }
    }

    LaunchedEffect(prefillUrl) {
        if (existing == null && !prefillUrl.isNullOrBlank()) runProbe()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "Add connection" else "Edit connection") },
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; probe = null },
                    label = { Text("Server address") },
                    placeholder = { Text("https://hub.example.com/work/") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Bearer token (optional)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { runProbe() }, enabled = url.isNotBlank() && !probing) {
                        Text(if (probing) "Checking…" else "Detect server type")
                    }
                    if (probing) CircularProgressIndicator(modifier = Modifier.padding(4.dp), strokeWidth = 2.dp)
                }
            }
            if (BuildConfig.DEBUG) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "DEMO FIXTURE · LOCAL — debug builds accept plain HTTP for loopback and the emulator host alias only.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { url = "http://10.0.2.2:8766/work/"; name = "Work Hub"; probe = null }) {
                                Text("Work Hub fixture")
                            }
                            OutlinedButton(onClick = { url = "http://10.0.2.2:8766/direct/"; name = "Laptop"; probe = null }) {
                                Text("Direct node fixture")
                            }
                        }
                    }
                }
            }
            probe?.let { result ->
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                when (result.role) {
                                    ServerRole.HUB -> "Detected: Hub"
                                    ServerRole.DIRECT_NODE -> "Detected: Direct Node"
                                    ServerRole.UNKNOWN -> "Could not identify this server"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                result.url.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }
            error?.let { message ->
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            message,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
            item {
                Button(
                    onClick = {
                        val result = probe
                        if (existing == null && result != null) {
                            vm.addConnection(name, result, token)
                            onDone()
                        } else if (existing != null) {
                            vm.updateConnection(existing, name, url).fold(
                                onSuccess = { onDone() },
                                onFailure = { error = it.message }
                            )
                        }
                    },
                    enabled = if (existing == null) probe != null else url.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (existing == null) "Save connection" else "Save changes")
                }
            }
            item {
                Text(
                    "Changing a saved address creates a new connection identity, so an existing token is never sent to a different server.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
