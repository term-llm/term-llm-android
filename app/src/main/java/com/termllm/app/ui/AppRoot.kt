package com.termllm.app.ui

import okhttp3.HttpUrl.Companion.toHttpUrl

import kotlinx.coroutines.launch

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.first
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

private const val DIRECT_SENTINEL = "-"

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("agents", "Agents", Icons.Filled.Groups),
    Tab("chat", "Chat", Icons.Filled.Chat),
    Tab("listen", "Listen", Icons.Filled.Headphones),
    Tab("music", "Music", Icons.Filled.MusicNote),
)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun AppRoot(
    vm: AppViewModel,
    deepLink: String? = null,
    deepLinkSourceId: String? = null,
    onDeepLinkHandled: (String?) -> Unit = {},
    authCallback: String? = null,
    onAuthCallbackHandled: () -> Unit = {},
    appLink: String? = null,
    onAppLinkHandled: () -> Unit = {},
) {
    val navController = rememberNavController()
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val states by vm.states.collectAsStateWithLifecycle()
    val settingsReady by vm.settingsReady.collectAsStateWithLifecycle()
    val notifications by vm.notificationsEnabled.collectAsStateWithLifecycle()
    val backStackEntry by navController.currentBackStackEntryAsState()
    // A push tap carries an absolute URL inside a source's mount; open that source
    // and its web UI, then forget the link so a rotation does not reopen it.
    androidx.compose.runtime.LaunchedEffect(deepLink, deepLinkSourceId, settingsReady) {
        val link = deepLink ?: return@LaunchedEffect
        if (!settingsReady) return@LaunchedEffect
        val target = runCatching { link.toHttpUrl() }.getOrNull()
        val connection = catalog.connections.singleOrNull { candidate ->
            candidate.id == deepLinkSourceId && target != null &&
                com.termllm.app.data.ServerAddress.isWithinMount(target, candidate.url.toHttpUrl())
        }
        if (connection != null && target != null) {
            val path = target.encodedPath + (target.encodedQuery?.let { "?" + it } ?: "")
            vm.openChat(connection.id, path)
            navController.navigate("chat") { popUpTo(navController.graph.findStartDestination().id) { saveState = true }; launchSingleTop = true; restoreState = true }
        }
        onDeepLinkHandled(link) // Unknown/mismatched route is dropped, never guessed.
    }
    val route = backStackEntry?.destination?.route

    val appContext = LocalContext.current.applicationContext
    val chatHolder = remember(appContext) { ChatDocumentHolder(appContext) }
    DisposableEffect(chatHolder) {
        onDispose { chatHolder.destroy() }
    }
    // Passkey sign-in: the browser hands back a one-use code; the redeemed session
    // cookie goes into this source's own WebView profile and the native cookie jar.
    remember(chatHolder) {
        chatHolder.sessionCookie = { connectionId -> vm.sessionCookie(connectionId) }
        vm.sessionCookieSink = { connection, cookie -> chatHolder.importSessionCookie(connection, cookie) }
        true
    }
    LaunchedEffect(authCallback) {
        val callback = authCallback ?: return@LaunchedEffect
        vm.completeNativeLogin(callback)
        onAuthCallbackHandled()
    }
    // Pairing link → pre-filled connection editor (never auto-saved); voice shortcut →
    // the last reachable agent's page, where the page's own Start button is pressed.
    LaunchedEffect(appLink) {
        val link = appLink ?: return@LaunchedEffect
        if (com.termllm.app.data.PairingLink.isVoiceLaunch(link)) {
            // A cold launch from the shortcut arrives before the first refresh has
            // answered: wait (bounded) for a reachable agent instead of failing at once.
            val target = vm.lastAgentForChat() ?: kotlinx.coroutines.withTimeoutOrNull(10_000) {
                vm.states.first { vm.agents(it).any { agent -> agent.reachable } }
                vm.lastAgentForChat()
            }
            if (target == null) {
                vm.postLoginMessage("Live voice shortcut: no reachable agent yet. Add or sign in to a source first.")
                navController.navigate("agents") { launchSingleTop = true }
            } else {
                chatHolder.requestVoiceLaunch()
                openChatTab(navController, vm, target.first, target.second)
            }
        } else {
            val server = com.termllm.app.data.PairingLink.server(link)
            if (server == null) {
                vm.postLoginMessage("That pairing link is not valid. Expected termllm://connect?server=https://…")
                navController.navigate("agents") { launchSingleTop = true }
            } else {
                navController.navigate("source?prefill=" + android.net.Uri.encode(server.toString()))
            }
        }
        onAppLinkHandled()
    }
    remember(chatHolder) {
        chatHolder.onVoiceLaunchFinished = { error ->
            android.util.Log.i("TermLLM", "voice launch: " + (error ?: "started"))
            vm.postLoginMessage(error ?: "Live voice started on the agent's page.")
        }
        true
    }
    // Capture permission plumbing: the WebView asks the holder, the holder asks here for
    // the Android permission and (for cameras / other origins) for the user's consent.
    val pendingPermission = remember { arrayOfNulls<(Boolean) -> Unit>(1) }
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        pendingPermission[0]?.invoke(granted)
        pendingPermission[0] = null
    }
    val capturePromptState = remember { androidx.compose.runtime.mutableStateOf<Triple<String, List<String>, (Boolean) -> Unit>?>(null) }
    remember(chatHolder, permissionLauncher) {
        chatHolder.permissionRequester = { permission, decide ->
            if (androidx.core.content.ContextCompat.checkSelfPermission(appContext, permission) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                decide(true)
            } else {
                pendingPermission[0] = decide
                permissionLauncher.launch(permission)
            }
        }
        chatHolder.capturePrompt = { origin, resources, decide -> capturePromptState.value = Triple(origin, resources, decide) }
        true
    }
    capturePromptState.value?.let { (origin, resources, decide) ->
        val what = buildList {
            if (com.termllm.app.chat.MediaCapturePolicy.CAMERA in resources) add("camera")
            if (com.termllm.app.chat.MediaCapturePolicy.MICROPHONE in resources) add("microphone")
        }.joinToString(" and ")
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { capturePromptState.value = null; decide(false) },
            title = { Text("Allow " + origin + " to use the " + what + "?") },
            text = { Text("The page asked for the " + what + ". Android will also ask for the app permission. Nothing is captured natively.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { capturePromptState.value = null; decide(true) }) { Text("Allow") }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { capturePromptState.value = null; decide(false) }) { Text("Don't allow") }
            },
        )
    }
    // Live voice hang-up: this app's audio ends the page's call (user playback at once,
    // tool-started playback once that tool's reply reached the page).
    remember(chatHolder) {
        com.termllm.app.listen.PlayerHub.onWillStartPlayback = { fromTool -> chatHolder.hangUp.onPlaybackStarting(fromTool) }
        true
    }
    // Sign-out, revocation or source removal clears the target; the retained
    // protected document must disappear immediately, not on next navigation.
    val chatTarget by vm.chatTarget.collectAsStateWithLifecycle()
    LaunchedEffect(chatTarget) {
        if (chatTarget == null) {
            val source = chatHolder.loadedSource
            if (source != null) chatHolder.purge(vm.connection(source))
        }
    }

    val listenStore = remember(appContext) { (appContext as com.termllm.app.TermLlmApplication).listenStore }
    val mediaAuth = remember(listenStore) {
        com.termllm.app.listen.MediaAuth({ vm.connections }, { id -> vm.token(id) })
    }
    val listenDownloader = remember(listenStore, mediaAuth) {
        com.termllm.app.listen.ListenDownloader(appContext, listenStore, mediaAuth, connections = { vm.connections })
    }
    val mediaTools = remember(listenStore, listenDownloader, chatHolder) {
        com.termllm.app.tools.MediaTools(
            listenStore,
            listenDownloader,
            sourceId = { chatHolder.loadedConnectionId },
        )
    }
    val pushEnabled by vm.notificationsEnabled.collectAsStateWithLifecycle()
    val pushState by com.termllm.app.push.PushCenter.state.collectAsStateWithLifecycle()
    val notificationResumeScope = androidx.compose.runtime.rememberCoroutineScope()
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        if (pushEnabled && settingsReady) notificationResumeScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            com.termllm.app.push.PushCenter.awaitReady(appContext)
            com.termllm.app.push.PushCenter.startStreams(appContext)
        }
    }
    val enrolledRoutes = pushState.routes.mapValues { (_, value) -> value.endpoint to value.enabled }
    androidx.compose.runtime.LaunchedEffect(pushEnabled, enrolledRoutes, settingsReady) {
        if (!settingsReady) return@LaunchedEffect
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            com.termllm.app.push.PushCenter.ensureInit(appContext)
            if (pushEnabled) com.termllm.app.push.PushCenter.startStreams(appContext)
            else {
                com.termllm.app.push.PushStreamService.stop(appContext)
                com.termllm.app.push.PushCenter.disable()
            }
        }
    }
    val deviceToolSharing = remember(appContext) {
        com.termllm.app.tools.DeviceToolSharing(java.io.File(appContext.filesDir, "device-tools-sharing.json"))
    }
    // Consent is observable state, so the switches reflect the store immediately and
    // the revocation effect re-runs when it changes.
    val sharingState = remember { androidx.compose.runtime.mutableStateOf(deviceToolSharing.state()) }
    remember(chatHolder) {
        // The holder asks before it ever installs the bridge, so a source without a
        // grant has no `document.modelContext` to call through.
        chatHolder.sharingAllowed = { connectionId -> deviceToolSharing.isShared(connectionId) }
        true
    }
    androidx.compose.runtime.LaunchedEffect(sharingState.value) {
        // Revoking consent takes effect at once: the source's page state is dropped
        // (cookies, storage, DOM) and its chat target is cleared so no stale bridge
        // or registry survives the change.
        catalog.connections.forEach { connection ->
            if (!deviceToolSharing.isShared(connection.id)) {
                // Revoked: blank the document, drop the source's page state, close the tab.
                chatHolder.purge(connection)
                vm.clearChatFor(connection.id)
            } else if (chatHolder.loadedConnectionId == connection.id) {
                // Granted: the open document was built without the bridge, so reopen it
                // (cookies survive; only the chat target is dropped) to install the tools.
                chatHolder.purge(connection)
                vm.clearChatFor(connection.id)
            }
        }
    }
    val musicLibrary = remember(appContext) { com.termllm.app.music.MusicLibrary(appContext) }
    val playlistStore = remember(appContext) {
        com.termllm.app.music.PlaylistStore(java.io.File(appContext.filesDir, "playlists"))
    }
    val listenArtwork = remember(listenStore, mediaAuth) {
        com.termllm.app.listen.ListenArtwork(
            dir = java.io.File(appContext.filesDir, "listen-artwork"),
            decorate = { builder, url, ownerId ->
                val credential = mediaAuth.credentialFor(url, ownerId)
                if (credential != null) builder.header(credential.header, credential.value) else builder
            },
        )
    }
    LaunchedEffect(listenStore) {
        // Keep the artwork cache in step with the queue: drop images nobody uses.
        listenArtwork.prune(listenStore.items())
    }
    val deviceTools = remember(appContext) {
        val version = runCatching {
            appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
        }.getOrNull() ?: "debug"
        com.termllm.app.tools.DeviceTools(
            context = appContext,
            appVersion = version,
            foreground = { com.termllm.app.tools.DeviceTools.isForeground(appContext) },
        )
    }
    val pushScope = androidx.compose.runtime.rememberCoroutineScope()
    remember(chatHolder, pushEnabled, catalog.connections) {
        chatHolder.pushAllowed = { _ -> pushEnabled }
        chatHolder.pushHandlerFactory = { connectionId, origin, mount ->
            if (!pushEnabled) null else com.termllm.app.push.PushHandler(
                routeKey = connectionId, origin = origin, mountPrefix = mount,
                capability = { pushEnabled },
                markRead = { source, sessionId, storeInstanceId, seq ->
                    val permittedSources = setOf("", origin, mount, mount.trimEnd('/'), origin + mount, origin + mount.trimEnd('/'))
                    require(source in permittedSources) { "Read acknowledgement belongs to another source" }
                    if (sessionId.isNotEmpty()) pushScope.launch { vm.markAttentionSeen(connectionId, sessionId, storeInstanceId, seq) }
                },
            )
        }
        true
    }
    remember(chatHolder, mediaTools, deviceTools) {
        chatHolder.toolHandler = { name, args ->
            com.termllm.app.listen.PlayerHub.toolInvocationDepth += 1
            val result = try {
                if (name == "ping" || name == "device_info") deviceTools.handle(name, args)
                else mediaTools.handle(name, args)
            } finally {
                com.termllm.app.listen.PlayerHub.toolInvocationDepth -= 1
            }
            // The reply is about to reach the page: if this call started audio, the
            // live call is ended only after the page uploads the tool result.
            chatHolder.hangUp.onToolReplied()
            result
        }
        true
    }
    val shelfVisible by com.termllm.app.listen.PlayerHub.shelfVisible.collectAsStateWithLifecycle()
    LaunchedEffect(shelfVisible) { vm.saveShelfVisibility(shelfVisible) }
    val playback by com.termllm.app.listen.PlayerHub.state.collectAsStateWithLifecycle()
    LaunchedEffect(listenStore) {
        com.termllm.app.listen.PlayerHub.storeRef = listenStore
        com.termllm.app.listen.PlayerHub.connect(appContext, listenStore)
        while (true) {
            com.termllm.app.listen.PlayerHub.publishTick()
            kotlinx.coroutines.delay(1000)
        }
    }

    val agents = vm.agents(states)
    val attention = vm.attentionItems(states)
    val attentionTotals = vm.attentionTotals(states)
    val badge = attentionTotals.second + attentionTotals.third

    val showTabs = route == null || route in tabs.map { it.route }
    val expanded = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 600
    androidx.compose.foundation.layout.Row(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
        if (expanded && showTabs) androidx.compose.material3.NavigationRail {
            tabs.forEach { tab -> androidx.compose.material3.NavigationRailItem(
                modifier = Modifier.testTag("tab-" + tab.route),
                selected = route == tab.route,
                onClick = { navController.navigate(tab.route) {
                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true; restoreState = true
                } },
                icon = { Icon(tab.icon, contentDescription = tab.label) },
                label = { Text(tab.label) },
            ) }
        }
    Scaffold(
        // Compose test tags surface as resource-ids to UIAutomator, so the capture
        // harness can address switches and buttons by name instead of coordinates.
        modifier = Modifier.weight(1f).semantics { testTagsAsResourceId = true },
        bottomBar = {
            if (showTabs) {
                Column {
                    // Hide is not stop: hiding the shelf keeps audio playing.
                    if (shelfVisible) PlaybackShelf(
                        playback = playback,
                        onOpenListen = { navController.navigate("listen") { launchSingleTop = true } },
                        onHide = { com.termllm.app.listen.PlayerHub.hideShelf() },
                    )
                    if (!expanded) NavigationBar {
                        tabs.forEach { tab ->
                            val selected = route == tab.route
                            NavigationBarItem(
                                modifier = Modifier.testTag("tab-" + tab.route),
                                selected = selected,
                                onClick = {
                                    navController.navigate(tab.route) {
                                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    if (tab.route == "agents" && badge > 0) {
                                        BadgedBox(badge = { Badge { Text(badge.toString()) } }) {
                                            Icon(tab.icon, contentDescription = tab.label)
                                        }
                                    } else {
                                        Icon(tab.icon, contentDescription = tab.label)
                                    }
                                },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "agents",
            // The outer scaffold already applies these insets. Nested screen
            // scaffolds must not reserve the same status/navigation bars again.
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
        ) {
            composable("agents") {
                AgentsScreen(
                    vm = vm,
                    connections = catalog.connections,
                    states = states,
                    onOpenAgent = { connectionId, nodeId ->
                        navController.navigate("agent/" + connectionId + "/" + (nodeId ?: DIRECT_SENTINEL))
                    },
                    onOpenChat = { connectionId, path -> openChatTab(navController, vm, connectionId, path) },
                    onAddSource = { navController.navigate("source") },
                    onOpenSettings = { navController.navigate("settings") },
                    onOpenAttention = { navController.navigate("attention") },
                    attentionBadge = badge,
                    onOpenAdmin = { connectionId -> navController.navigate("admin/" + connectionId) },
                )
            }
            composable("attention") {
                AttentionScreen(
                    vm = vm,
                    items = attention,
                    totals = attentionTotals,
                    connections = catalog.connections,
                    onOpenChat = { connectionId, path -> openChatTab(navController, vm, connectionId, path) },
                )
            }
            composable("listen") {
                ListenScreen(
                    store = listenStore,
                    downloader = listenDownloader,
                    artwork = listenArtwork,
                    playback = playback,
                    onOpenChat = { navController.navigate("chat") { launchSingleTop = true } },
                )
            }
            composable("music") { MusicScreen(library = musicLibrary, playlists = playlistStore) }
            composable("chat") {
                ChatTabScreen(
                    vm = vm,
                    states = states,
                    holder = chatHolder,
                    onBack = { navController.navigate("agents") {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                    } },
                )
            }
            composable("settings") {
                SettingsScreen(
                    vm = vm,
                    connections = catalog.connections,
                    states = states,
                    notificationsEnabled = notifications,
                    sharing = deviceToolSharing,
                    sharingState = sharingState.value,
                    onSharingChanged = { sharingState.value = deviceToolSharing.state() },
                    pushState = pushState,
                    pushPermission = remember(pushState) { com.termllm.app.push.PushCenter.channelPermissions() },
                    onAddSource = { navController.navigate("source") },
                    onEditSource = { navController.navigate("source?connectionId=" + it) },
                )
            }
            composable(
                route = "source?connectionId={connectionId}&prefill={prefill}",
                arguments = listOf(
                    navArgument("connectionId") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                    navArgument("prefill") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    },
                ),
            ) { entry ->
                val existing = entry.arguments?.getString("connectionId")?.let { vm.connection(it) }
                AddSourceScreen(
                    vm = vm,
                    existing = existing,
                    prefillUrl = entry.arguments?.getString("prefill"),
                    onDone = { navController.popBackStack() },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = "admin/{connectionId}",
                arguments = listOf(navArgument("connectionId") { type = NavType.StringType }),
            ) { entry ->
                HubAdminScreen(
                    vm = vm,
                    states = states,
                    connectionId = entry.arguments?.getString("connectionId").orEmpty(),
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = "agent/{connectionId}/{nodeId}",
                arguments = listOf(
                    navArgument("connectionId") { type = NavType.StringType },
                    navArgument("nodeId") { type = NavType.StringType },
                ),
            ) { entry ->
                val connectionId = entry.arguments?.getString("connectionId").orEmpty()
                val nodeId = entry.arguments?.getString("nodeId")?.takeIf { it != DIRECT_SENTINEL }
                AgentDetailScreen(
                    vm = vm,
                    states = states,
                    connectionId = connectionId,
                    nodeId = nodeId,
                    onBack = { navController.popBackStack() },
                    onOpenChat = { id, path -> openChatTab(navController, vm, id, path) },
                )
            }
            composable(
                route = "conversation/{connectionId}?path={path}",
                arguments = listOf(
                    navArgument("connectionId") { type = NavType.StringType },
                    navArgument("path") { type = NavType.StringType; defaultValue = "" },
                ),
            ) { entry ->
                val connectionId = entry.arguments?.getString("connectionId").orEmpty()
                val path = entry.arguments?.getString("path").orEmpty()
                ChatScreen(
                    vm = vm,
                    holder = chatHolder,
                    connectionId = connectionId,
                    path = path,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
    }
}

private fun openChatTab(navController: NavHostController, vm: AppViewModel, connectionId: String, path: String) {
    vm.openChat(connectionId, path)
    navController.navigate("chat") {
        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** The Chat tab: a chooser until a conversation is opened, then the web view. */
@Composable
private fun ChatTabScreen(
    vm: AppViewModel,
    states: Map<String, com.termllm.app.model.SourceState>,
    holder: ChatDocumentHolder,
    onBack: () -> Unit,
) {
    val target by vm.chatTarget.collectAsStateWithLifecycle()
    val current = target
    if (current == null) {
        ChatChooser(vm, states, onOpen = { id, path -> vm.openChat(id, path) })
    } else {
        ChatScreen(vm = vm, holder = holder, connectionId = current.first, path = current.second, onBack = onBack)
    }
}
