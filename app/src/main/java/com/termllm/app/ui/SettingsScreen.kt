package com.termllm.app.ui

import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.termllm.app.BuildConfig
import com.termllm.app.data.Connection
import com.termllm.app.data.ServerKind
import com.termllm.app.model.SourceState
import com.termllm.app.push.PushCenter
import com.termllm.app.push.PushNotifications
import com.termllm.app.push.PushPermissionPolicy
import com.termllm.app.push.PushRelay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: AppViewModel,
    connections: List<Connection>,
    states: Map<String, SourceState>,
    notificationsEnabled: Boolean,
    onAddSource: () -> Unit,
    onEditSource: (String) -> Unit,
    sharing: com.termllm.app.tools.DeviceToolSharing? = null,
    sharingState: com.termllm.app.tools.DeviceToolSharing.State =
        com.termllm.app.tools.DeviceToolSharing.State(),
    pushState: PushCenter.State = PushCenter.State(),
    pushPermission: Pair<Boolean, Boolean> = true to true,
    onSharingChanged: () -> Unit = {},
    onNotificationsPreferenceChange: (Boolean) -> Unit = { vm.setNotificationsEnabled(it) },
) {
    val scope = rememberCoroutineScope()
    var signInTarget by remember { mutableStateOf<Connection?>(null) }
    var removeTarget by remember { mutableStateOf<Connection?>(null) }
    var signingIn by remember { mutableStateOf(false) }
    var relayTarget by remember { mutableStateOf<Connection?>(null) }
    var permissionNote by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    // The OS permission and the alerts channel are owned by the system, so they can
    // change while Settings is open (the user comes back from system settings, or a
    // channel is switched off elsewhere). Re-read them on every resume instead of
    // showing a state captured at first composition.
    var permissionRevision by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) permissionRevision += 1
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val applyPreference by rememberUpdatedState(onNotificationsPreferenceChange)
    val permission = remember(permissionRevision, notificationsEnabled, pushState.routes.size) {
        PushPermissionPolicy.classify(
            sdk = Build.VERSION.SDK_INT,
            granted = PushNotifications.permissionGranted(context),
            canAskAgain = PushNotifications.canAskAgain(context),
            alertsChannelOn = PushNotifications(context).alertsChannelEnabled(),
            preference = notificationsEnabled,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            permissionNote = null
            // Only now does the preference mean anything: the OS will show the alert.
            applyPreference(true)
            permissionRevision += 1
        } else {
            permissionNote =
                "Android did not grant the notification permission, so alerts cannot appear. " +
                    "You can allow them later in system settings."
            permissionRevision += 1
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item { SectionHeader("Sources") }
            if (connections.isEmpty()) {
                item {
                    Text(
                        "No saved connections. Add a Hub or a Direct Node to get started.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(connections, key = { it.id }) { connection ->
                val state = states[connection.id] ?: SourceState()
                val hasToken = vm.token(connection.id) != null
                val hasSession = vm.sessionCookie(connection.id) != null
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(connection.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(
                                    connection.url,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { onEditSource(connection.id) }) {
                                Icon(Icons.Filled.Edit, contentDescription = "Edit source")
                            }
                            IconButton(onClick = { removeTarget = connection }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Remove source")
                            }
                        }
                        Text(
                            (when (connection.kind) {
                                ServerKind.HUB -> "Hub"
                                ServerKind.DIRECT_NODE -> "Direct Node"
                                ServerKind.UNKNOWN -> "Not yet identified"
                            }) + " · " + (if (hasToken) "signed in" else if (hasSession) "passkey session" else "no token"),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (hasToken || hasSession) OkGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (state.loginUrl != null || hasSession) {
                                TextButton(
                                    onClick = { vm.signInWithPasskey(connection, context) },
                                    modifier = Modifier.testTag("settings-passkey-" + connection.id),
                                ) { Text(if (hasSession) "Passkey again" else "Sign in with passkey") }
                            }
                            TextButton(onClick = { signInTarget = connection }) { Text(if (hasToken) "Replace token" else if (hasSession) "Use a token" else "Sign in") }
                            if (hasToken || hasSession) {
                                TextButton(onClick = { vm.signOut(connection.id) }) { Text("Sign out") }
                            }
                            TextButton(onClick = { vm.refresh(connection.id) }) { Text("Test") }
                        }
                        state.error?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            item {
                TextButton(onClick = onAddSource, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text("  Add connection")
                }
            }

            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { SectionHeader("Device tools") }
            if (sharing != null) {
                // Global switch first, per-source grants second: iOS ships one master
                // switch ("Share device tools"); Astra asks Android to name the sources
                // that get it, so nothing is shared with a server by default.
                item {
                    val enabled = sharingState.enabled
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Share device tools", style = MaterialTheme.typography.bodyLarge, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                            Text(
                                "A chat page may ask this phone to play audio, add episodes and report its status. " +
                                    "Off by default; nothing is shared unless you also choose a source below.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = enabled,
                            onCheckedChange = { on ->
                                sharing.setEnabled(on)
                                onSharingChanged()
                            },
                            modifier = Modifier.testTag("consent-master"),
                        )
                    }
                }
                item {
                    Text("What is shared", style = MaterialTheme.typography.labelMedium)
                    Text(
                        "Status (model, battery, app state) · Podcasts & audio (add, list, play, control, manage). " +
                            "Location, microphone, camera and files are not advertised by this build.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val globallyEnabled = sharingState.enabled
                items(connections, key = { "share-" + it.id }) { connection ->
                    val granted = sharingState.enabled && connection.id in sharingState.sources
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(connection.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (granted) "Device tools shared with this source"
                                else "No device tools for this source",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = granted,
                            enabled = globallyEnabled,
                            onCheckedChange = { on ->
                                if (on) sharing.grant(connection.id) else sharing.revoke(connection.id)
                                onSharingChanged()
                            },
                            modifier = Modifier.testTag("consent-source-" + connection.id),
                        )
                    }
                }
                if (connections.isEmpty()) {
                    item {
                        Text(
                            "Add a source first; each source is granted separately.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            item { SectionHeader("Notifications") }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Agent notifications", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Delivered over each source's own term-llm push relay while its stream is running. " +
                                "Android may stop the stream (Doze, battery saver); the relay keeps a small backlog " +
                                "and the app catches up when it reconnects, so this is not a guarantee of instant delivery.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = notificationsEnabled,
                        onCheckedChange = { want ->
                            if (!want) {
                                permissionNote = null
                                applyPreference(false)
                            } else {
                                when (permission.toggleAction) {
                                    PushPermissionPolicy.ToggleAction.ENABLE -> {
                                        permissionNote = null
                                        applyPreference(true)
                                    }
                                    PushPermissionPolicy.ToggleAction.REQUEST_PERMISSION -> {
                                        // Android 13+: the toggle itself asks, with context, and
                                        // the preference only turns on once the OS agrees.
                                        if (Build.VERSION.SDK_INT >= PushPermissionPolicy.RUNTIME_PERMISSION_SDK) {
                                            PushNotifications.markPromptRequested(context)
                                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        } else {
                                            applyPreference(true)
                                        }
                                    }
                                    PushPermissionPolicy.ToggleAction.OPEN_APP_SETTINGS -> {
                                        // Asking again would be a no-op: send the user where
                                        // the permission can actually be changed.
                                        permissionNote =
                                            "Android is blocking notifications for this app. Allow them in system settings."
                                        PushNotifications.openAppNotificationSettings(context)
                                    }
                                }
                            }
                        },
                        modifier = Modifier.testTag("notifications-switch"),
                    )
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    permissionNote?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                    Text(
                        permission.summary,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (permission.canDeliver) OkGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Four separate facts on purpose: "on" in the app, allowed by the OS,
                    // a live subscription, and a stream that is actually connected are
                    // different states and used to be reported as one.
                    Text(
                        "Preference: " + (if (notificationsEnabled) "on" else "off") +
                            " · OS permission: " + permission.permissionLabel +
                            " · alerts channel: " + (if (permission.alertsChannel) "on" else "off"),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(
                        "Enrolled: " + enrollmentSummary(pushState) +
                            " · stream: " + streamSummary(pushState.stream),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Delivered: " + pushState.delivered +
                            " · last error: " + (pushState.lastError ?: "none") +
                            (pushState.stream.lastError?.let { " · stream error: " + it } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (permission.needsChannelSettings) {
                        TextButton(
                            onClick = { PushNotifications.openAlertsChannelSettings(context) },
                            modifier = Modifier.testTag("notifications-channel-settings"),
                        ) { Text("Open alerts channel settings") }
                    }
                    if (permission.needsAppSettings) {
                        TextButton(
                            onClick = { PushNotifications.openAppNotificationSettings(context) },
                            modifier = Modifier.testTag("notifications-permission-settings"),
                        ) { Text("Open notification settings") }
                    }
                }
            }
            item {
                Text(
                    "Each source uses its own relay and subscription, so two sources never share a route.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(connections, key = { "push-" + it.id }) { connection ->
                val route = pushState.routes[connection.id]
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(connection.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Relay: " + (route?.relay?.let { relayLabel(it) } ?: "not configured") +
                                " · " + routeStateLabel(route),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = { relayTarget = connection },
                        modifier = Modifier.testTag("push-relay-" + connection.id),
                    ) { Text("Relay") }
                }
            }
            if (pushState.routes.values.any { it.adoptedLegacy && it.origin.isBlank() }) {
                item {
                    Text(
                        "A notification subscription from an earlier version is still registered. Open that " +
                            "source's chat once to attach it to its exact origin, or re-enroll it from the relay dialog.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item { SectionHeader("About") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("term-llm for Android", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Native port of the SwiftUI iOS app. Bundle " + BuildConfig.APPLICATION_ID +
                            ", version " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ").",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Hub protocol core " + BuildConfig.PROTOCOL_CORE_COMMIT + ". Chat remains the server's web UI inside a native shell; Agents, Attention and Sessions are native HTTP clients.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Implemented: sources and discovery, Agents, Attention with exact acknowledgement, chat in the "
                            + "server's web UI with native device tools (consent-gated), Listen with offline playback, a "
                            + "device music library and local playlists, Hub admin (nodes, delegations, security), "
                            + "push over the term-llm relay, and passkey sign-in through the system browser "
                            + "(the ceremony runs there; only the session cookie is handed back).\n"
                            + "Not ported yet: Live voice, linked devices (WebRTC) and Apple Music's catalogue.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    signInTarget?.let { connection ->
        TokenDialog(
            title = "Sign in to " + connection.name,
            subtitle = "The token is validated against this source, then stored AES/GCM encrypted with an AndroidKeyStore key.",
            busy = signingIn,
            onDismiss = { signInTarget = null },
            onSubmit = { token ->
                signingIn = true
                scope.launch {
                    vm.signIn(connection, token)
                    signingIn = false
                    signInTarget = null
                }
            }
        )
    }

    relayTarget?.let { connection ->
        val initial = pushState.routes[connection.id]?.relay ?: PushRelay.defaultRelay().orEmpty()
        RelayDialog(
            connectionName = connection.name,
            initial = initial,
            debugHint = BuildConfig.DEBUG,
            onDismiss = { relayTarget = null },
            onSubmit = { input ->
                val normalized = PushRelay.normalize(input)
                if (normalized == null) {
                    PushRelay.refusal(input)
                } else {
                    // Changing a relay retires the old registration; keep the write and
                    // the network call off the UI thread.
                    scope.launch(Dispatchers.IO) { PushCenter.setRelayBase(connection.id, normalized) }
                    relayTarget = null
                    null
                }
            },
            onReenroll = if (pushState.routes[connection.id]?.canReenroll == true) {
                { input: String ->
                    val normalized = PushRelay.normalize(input)
                    if (normalized == null) PushRelay.refusal(input) else {
                        scope.launch(Dispatchers.IO) {
                            PushCenter.setRelayBase(connection.id, normalized)
                            PushCenter.reenroll(connection.id, normalized)?.let { endpoint ->
                                com.termllm.app.push.PushStreamService.start(context, endpoint, connection.id)
                            }
                        }
                        relayTarget = null
                        null
                    }
                }
            } else null,
            onForget = {
                scope.launch(Dispatchers.IO) { PushCenter.disableAndForget(connection.id) }
                relayTarget = null
            },
        )
    }

    removeTarget?.let { connection ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text("Remove " + connection.name + "?") },
            text = { Text("The saved address and its stored token are deleted from this device.") },
            confirmButton = {
                TextButton(onClick = {
                    // A removed source must not keep a relay stream open or a
                    // subscription alive under its old origin.
                    scope.launch(Dispatchers.IO) { PushCenter.disableAndForget(connection.id) }
                    vm.removeConnection(connection.id)
                    removeTarget = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removeTarget = null }) { Text("Cancel") } },
        )
    }
}

@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
private fun RelayDialog(
    connectionName: String,
    initial: String,
    debugHint: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (String) -> String?,
    onForget: () -> Unit,
    onReenroll: ((String) -> String?)? = null,
) {
    var value by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        modifier = Modifier.semantics { testTagsAsResourceId = true },
        onDismissRequest = onDismiss,
        title = { Text("Push relay for " + connectionName) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "The relay forwards Web Push records for this source only. Use the HTTPS relay that " +
                        "belongs to this server; a plain-HTTP address is accepted only for a local relay " +
                        "in a debug build.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = {
                        value = it
                        error = null
                    },
                    label = { Text("Relay URL") },
                    singleLine = true,
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth().testTag("push-relay-field"),
                )
                error?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
                if (debugHint && PushRelay.defaultRelay() != null) {
                    Text(
                        "Debug default: " + PushRelay.defaultRelay(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val failure = onSubmit(value)
                    if (failure != null) error = failure
                },
                modifier = Modifier.testTag("push-relay-save"),
            ) { Text("Save") }
        },
        dismissButton = {
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                onReenroll?.let { action ->
                    TextButton(onClick = { error = action(value) }, modifier = Modifier.testTag("push-relay-reenroll")) { Text("Re-enroll") }
                }
                TextButton(onClick = onForget, modifier = Modifier.testTag("push-relay-forget")) {
                    Text("Turn off")
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** Host-and-port only: a relay URL may carry a path but the host is what identifies it. */
private fun relayLabel(relay: String): String =
    relay.removePrefix("https://").removePrefix("http://").trimEnd('/')

private fun enrollmentSummary(state: PushCenter.State): String {
    val enrolled = state.routes.values.count { it.enrolled }
    if (enrolled == 0) return "none yet (open a source's chat with notifications on)"
    return enrolled.toString() + if (enrolled == 1) " source" else " sources"
}

private fun streamSummary(stream: PushCenter.StreamState): String = when (stream.phase) {
    PushCenter.StreamPhase.IDLE -> "stopped"
    PushCenter.StreamPhase.CONNECTING -> "connecting"
    PushCenter.StreamPhase.ACTIVE -> "connected"
    PushCenter.StreamPhase.RETRYING -> "reconnecting (attempt " + stream.attempts + ")"
    PushCenter.StreamPhase.ERROR -> if (stream.permanent) "stopped, re-enroll this source" else "stopped"
}

private fun routeStateLabel(route: PushCenter.RouteView?): String = when {
    route == null -> "off"
    route.enrolled -> "enrolled · " + route.stream.phase.name.lowercase()
    route.enabled -> "on, waiting for the page to subscribe"
    else -> "off"
}
