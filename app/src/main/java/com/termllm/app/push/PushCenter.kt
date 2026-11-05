package com.termllm.app.push

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import com.termllm.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The push pipeline: enroll this device with a relay, route decoded messages into
 * notifications, dedupe replays, acknowledge exactly, and keep the consent journal.
 *
 * **Per source, not per app.** Every saved connection gets its own route key, its
 * own relay, its own device stream endpoint and its own auth secret, all persisted
 * in [PushRouteStore]. Two sources with colliding session/event ids or different
 * mounts therefore cannot overwrite or cross-deliver each other. The pre-2.x
 * single-route enrollment is imported once and adopted only when the catalog has a
 * single source (see [PushRouteStore.adoptLegacy]).
 *
 * Delivery is honest about its transport: while the stream service runs, messages
 * arrive promptly; when Android stops it, the next start replays the relay's bounded
 * backlog. Nothing here claims guaranteed delivery (see STATE §15).
 */
object PushCenter {

    /** What the relay stream is doing, per route, for Settings and for the parent's tests. */
    enum class StreamPhase { IDLE, CONNECTING, ACTIVE, RETRYING, ERROR }

    data class StreamState(
        val phase: StreamPhase = StreamPhase.IDLE,
        val routeKey: String? = null,
        val endpoint: String? = null,
        val attempts: Int = 0,
        val lastError: String? = null,
        val permanent: Boolean = false,
    ) {
        val running: Boolean get() = phase == StreamPhase.ACTIVE || phase == StreamPhase.CONNECTING || phase == StreamPhase.RETRYING
        val active: Boolean get() = phase == StreamPhase.ACTIVE
    }

    /** One source's push wiring as the UI needs to see it. */
    data class RouteView(
        val routeKey: String,
        val origin: String = "",
        val mountPrefix: String = "/",
        val relay: String? = null,
        val endpoint: String? = null,
        val enabled: Boolean = false,
        val enrolled: Boolean = false,
        val adoptedLegacy: Boolean = false,
        val canReenroll: Boolean = false,
        val stream: StreamState = StreamState(),
    )

    data class State(
        val enabled: Boolean = false,
        val endpoint: String? = null,
        val relay: String? = null,
        val lastEventId: String? = null,
        val lastError: String? = null,
        val delivered: Int = 0,
        /** The route the last document/stream worked on, when there is one. */
        val route: String? = null,
        val routes: Map<String, RouteView> = emptyMap(),
        val stream: StreamState = StreamState(),
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private lateinit var appContext: Context
    private lateinit var identity: PushIdentity
    private lateinit var store: PushDeliveryStore
    private lateinit var routeStore: PushRouteStore
    private lateinit var notifications: PushNotifications

    /**
     * Readiness is explicit rather than derived from `lateinit` flags: the pipeline
     * touches files, so other threads may ask "are you ready?" while initialization is
     * still in flight, and callers must get an honest answer instead of a half-built
     * object. Permission answers are fail-closed until then.
     */
    @Volatile
    private var initialized = false

    private val initLock = Any()

    private val initExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "push-init").apply { isDaemon = true }
    }

    private val _ready = MutableStateFlow(false)

    /** False until [init]/[initAsync]/[awaitReady] completed; UI should wait on this. */
    val ready: StateFlow<Boolean> = _ready

    /** The route of the document currently trusted by the bridge (never a guess from an origin). */
    @Volatile
    private var activeRouteKey: String? = null

    /** The only short-lived HTTP client: enrollment, ACK and unregister. */
    private val apiClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val streams = ConcurrentHashMap<String, StreamState>()
    private enum class Consent { UNKNOWN, ENABLED, DISABLED }
    private val masterConsent = java.util.concurrent.atomic.AtomicReference(Consent.UNKNOWN)
    fun noteConsent(enabled: Boolean) { masterConsent.set(if (enabled) Consent.ENABLED else Consent.DISABLED) }
    fun transportEnabled(): Boolean = masterConsent.get() == Consent.ENABLED

    /**
     * Presence leases the page granted: scope+session of a document that is on
     * screen. A push for that exact source/session is suppressed while its lease is
     * valid; other sessions of the same source still alert.
     */
    private val leases = ConcurrentHashMap<String, Long>()

    /**
     * Installed by MainActivity: the one place that can show the Android 13 runtime
     * prompt. The bridge answers `requestPermission` through it without blocking the
     * UI thread (the bridge runs handlers on a worker).
     */
    @Volatile
    var permissionRequester: (suspend () -> Boolean)? = null

    // ---- lifecycle -----------------------------------------------------------------

    /**
     * Initialize once. Idempotent and thread-safe: the app, the bridge workers and the
     * stream service may all call it, and exactly one wins.
     */
    fun init(context: Context) {
        if (initialized) return
        synchronized(initLock) {
            if (initialized) return
            val application = context.applicationContext
            identity = PushIdentity(application)
            store = PushDeliveryStore(File(application.filesDir, "push/delivery.json"))
            routeStore = PushRouteStore(File(application.filesDir, "push/routes.json"))
            notifications = PushNotifications(application).also { it.ensureChannels() }
            appContext = application
            masterConsent.compareAndSet(Consent.UNKNOWN, if (application.getSharedPreferences("termllm_settings", Context.MODE_PRIVATE).getBoolean("notifications_enabled", false)) Consent.ENABLED else Consent.DISABLED)
            migrateLegacy()
            initialized = true
            _ready.value = true
            refreshState()
        }
    }

    /**
     * Initialize off the calling thread. This is what the Activity and the bridge use:
     * reading/writing the route table must never happen on the UI thread.
     */
    fun initAsync(context: Context) {
        if (initialized) {
            if (!_ready.value) _ready.value = true
            return
        }
        runCatching {
            initExecutor.execute {
                runCatching { init(context) }.onFailure { failure ->
                    _state.value = _state.value.copy(lastError = failure.message ?: "push could not start")
                }
            }
        }
    }

    /**
     * Suspend until the pipeline is ready, doing the initialization on IO. A gated UI
     * root can wait on this instead of reading a half-initialized state (which would,
     * for example, briefly claim notifications were impossible).
     */
    suspend fun awaitReady(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!initialized) init(context)
        initialized
    }

    /** Safe from the service/bridge: initializes exactly once. */
    fun ensureInit(context: Context) {
        if (!initialized) init(context)
    }

    fun isInitialized(): Boolean = initialized

    private fun isReady(): Boolean = initialized

    fun context(): Context = appContext

    private fun migrateLegacy() {
        val record = identity.load()
        routeStore.migrateLegacy(
            enabled = store.route(PushRouting.LEGACY_ROUTE).enabled,
            endpoint = record.endpoint,
            pushEndpoint = record.pushEndpoint,
            relay = record.relay,
            deviceSecret = record.deviceSecret,
        )
        val route = routeStore.route(PushRouting.LEGACY_ROUTE)
        if (route?.endpoint != null && store.registration(PushRouting.LEGACY_ROUTE) == null) {
            store.setRegistration(PushRouting.LEGACY_ROUTE, route.endpoint)
        }
        if (route != null) debug { "push: migrated the legacy single-route enrollment" }
    }

    private fun refreshState() {
        if (!isReady()) return
        val views = routeStore.routes().mapValues { (key, route) ->
            val consent = store.route(key)
            RouteView(
                routeKey = key,
                origin = route.origin,
                mountPrefix = route.mountPrefix,
                relay = route.relay,
                endpoint = route.endpoint,
                enabled = consent.enabled,
                enrolled = route.enrolled && route.endpoint != null,
                adoptedLegacy = route.adoptedLegacy,
                canReenroll = !route.vapidKey.isNullOrBlank(),
                stream = streams[key] ?: StreamState(routeKey = key, endpoint = route.endpoint),
            )
        }
        val primary = views[activeRouteKey]
            ?: views.values.firstOrNull { it.enabled && it.enrolled }
            ?: views.values.firstOrNull { it.enabled }
            ?: views.values.firstOrNull()
        val stream = primary?.stream?.takeIf { it.phase != StreamPhase.IDLE }
            ?: views.values.map { it.stream }.firstOrNull { it.running }
            ?: views.values.map { it.stream }.firstOrNull { it.phase != StreamPhase.IDLE }
            ?: StreamState()
        _state.value = _state.value.copy(
            enabled = views.values.any { it.enabled },
            endpoint = primary?.endpoint,
            relay = primary?.relay,
            route = primary?.routeKey ?: activeRouteKey,
            routes = views,
            stream = stream,
        )
    }

    // ---- routing -------------------------------------------------------------------

    /**
     * Bind a document's exact origin/mount to its source route. Called by the bridge
     * (which the holder wires to the connection it just loaded), never inferred from
     * "whatever page talked to us last".
     *
     * Returns true when the origin changed and the previous subscription was retired
     * because it had been minted for a different origin.
     */
    fun attachRoute(routeKey: String, origin: String, mountPrefix: String): Boolean {
        if (!isReady() || routeKey.isBlank() || origin.isBlank()) return false
        val attached = routeStore.attach(routeKey, origin, mountPrefix)
        activeRouteKey = routeKey
        var retired = false
        if (attached.originChanged) {
            retireRoute(routeKey)
            retired = true
        }
        adoptLegacyIfUnambiguous(routeKey, origin, mountPrefix)
        refreshState()
        return retired
    }

    /**
     * Move a pre-2.x subscription onto the connection that is now loading, but only
     * when the catalog holds exactly one source: with two or more there is no honest
     * way to know which one the old global subscription belonged to.
     */
    private fun adoptLegacyIfUnambiguous(routeKey: String, origin: String, mountPrefix: String) {
        val connectionCount = runCatching {
            com.termllm.app.data.ConnectionStore(appContext).load().connections.size
        }.getOrDefault(0)
        val adopted = routeStore.adoptLegacy(connectionCount, routeKey, origin, mountPrefix) ?: return
        identity.adoptSecret(PushRouting.LEGACY_ROUTE, routeKey)
        store.setEnabled(routeKey, true)
        store.setRegistration(routeKey, adopted.endpoint)
        store.retire(PushRouting.LEGACY_ROUTE)
        store.setRegistration(PushRouting.LEGACY_ROUTE, null, mirrorLegacyField = true)
        debug { "push: adopted the legacy subscription" }
    }

    /** Route key for a page's `scope` string, with the active route as the fallback. */
    fun routeKeyForScope(scope: String, origin: String = ""): String {
        if (!isReady()) return PushRouting.LEGACY_ROUTE
        if (origin.isNotBlank()) routeStore.routeForOrigin(origin)?.let { return it.id }
        if (scope.isNotBlank()) routeStore.route(scope)?.let { return it.id }
        return activeRoute()
    }

    fun activeRoute(): String = activeRouteKey?.takeIf { isReady() && routeStore.route(it) != null }
        ?: if (isReady()) deliveryRoute() else PushRouting.LEGACY_ROUTE

    /** Used by a notification tap: the alert names the route that produced it. */
    fun setActiveRoute(routeKey: String?) {
        if (routeKey.isNullOrBlank()) return
        activeRouteKey = routeKey
        if (isReady()) refreshState()
    }

    fun routeKeyForOrigin(origin: String): String? =
        if (isReady()) routeStore.routeForOrigin(origin)?.id else null

    fun routeKeyForEndpoint(endpoint: String): String? =
        if (isReady()) routeStore.routeForEndpoint(endpoint)?.id else null

    private fun deliveryRoute(): String {
        val routes = routeStore.routes()
        activeRouteKey?.let { key -> if (routes.containsKey(key)) return key }
        return routes.entries.firstOrNull { store.route(it.key).enabled && it.value.enrolled }?.key
            ?: routes.entries.firstOrNull { store.route(it.key).enabled }?.key
            ?: PushRouting.LEGACY_ROUTE
    }

    // ---- enrollment ----------------------------------------------------------------

    /**
     * Enroll [routeKey] with its relay. The relay is, in order: [relayBase] (page or
     * settings), the route's stored relay, then the debug-only local default. A
     * release build with no configured relay returns null and says why instead of
     * falling back to a hardcoded host.
     */
    suspend fun enroll(
        routeKey: String = PushRouting.LEGACY_ROUTE,
        vapidKey: String,
        relayBase: String? = null,
    ): String? = withContext(Dispatchers.IO) {
        if (!isReady()) return@withContext null
        val existing = routeStore.ensure(routeKey)
        val configured = relayBase ?: existing.relay ?: PushRelay.defaultRelay()
        val relay = PushRelay.normalize(configured)
        if (relay == null) {
            _state.value = _state.value.copy(
                lastError = PushRelay.refusal(configured),
            )
            refreshState()
            return@withContext null
        }
        if (vapidKey.isBlank()) {
            _state.value = _state.value.copy(lastError = "This page did not provide a subscription key.")
            return@withContext null
        }
        val record = identity.ensure(routeKey)
        val body = JSONObject()
            .put("device_token", java.security.MessageDigest.getInstance("SHA-256")
                .digest((record.deviceToken + "|" + routeKey).toByteArray()).joinToString("") { "%02x".format(it) })
            .put("environment", "android")
            .put("application_server_key", vapidKey)
            .toString()
        val request = Request.Builder()
            .url(relay.trimEnd('/') + "/v1/register")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        var outcome: String? = null
        try {
            apiClient.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    _state.value = _state.value.copy(lastError = "relay refused registration (HTTP " + response.code + ")")
                    return@use
                }
                val endpointJson = JSONObject(text)
                val pushEndpoint = endpointJson.optString("endpoint")
                if (pushEndpoint.isBlank()) {
                    _state.value = _state.value.copy(lastError = "relay returned no subscription endpoint")
                    return@use
                }
                val deviceSecret = endpointJson.optString("device_secret")
                // `stream` is this device's view of the same relay; fall back to the
                // push endpoint's origin when the relay is single-host.
                val absolute = endpointJson.optString("stream").ifBlank {
                    relay.trimEnd('/') + "/v1/stream/" + pushEndpoint.substringAfterLast('/')
                }
                val saved = routeStore.recordEnrollment(
                    id = routeKey,
                    endpoint = absolute,
                    pushEndpoint = pushEndpoint,
                    deviceSecret = deviceSecret,
                    relay = relay,
                    vapidKey = vapidKey,
                )
                store.setEnabled(routeKey, true)
                store.setRegistration(routeKey, absolute)
                streams.remove(routeKey) // Successful enrollment replaces any auth failure.
                refreshState()
                debug { "push: enrolled " + saved.id.take(8) + " endpoint=" + absolute.substringAfterLast('/').take(8) }
                outcome = absolute
            }
        } catch (e: Exception) {
            _state.value = _state.value.copy(lastError = e.message ?: "registration failed")
        }
        refreshState()
        outcome
    }

    /** Explicit Settings action: reuse only this source's saved public key. */
    suspend fun reenroll(routeKey: String, relayBase: String? = null): String? = withContext(Dispatchers.IO) {
        if (!isReady()) return@withContext null
        val key = routeStore.route(routeKey)?.vapidKey ?: return@withContext null
        enroll(routeKey, key, relayBase)
    }

    /** The Web Push subscription the page hands to the node (`/v1/push/subscribe`). */
    fun subscription(routeKey: String = PushRouting.LEGACY_ROUTE): JSONObject? {
        if (!isReady()) return null
        if (!store.route(routeKey).enabled || streams[routeKey]?.permanent == true) return null
        val route = routeStore.route(routeKey) ?: return null
        val endpoint = route.pushEndpoint
        if (endpoint.isNullOrBlank()) return null
        val record = identity.load()
        val secret = identity.authSecret(routeKey, record) ?: return null
        return JSONObject()
            .put("endpoint", endpoint)
            .put("client_kind", "app")
            .put("keys", JSONObject().put("p256dh", record.publicKey).put("auth", PushCrypto.base64Url(secret)))
    }

    /** App-wide off: every route is retired and the stream is stopped. */
    fun disable() {
        if (!isReady()) return
        routeStore.routes().keys.toList().forEach { retireRoute(it) }
        refreshState()
    }

    /** One source off (the bridge's `disable`). */
    fun disable(routeKey: String) {
        if (!isReady()) return
        retireRoute(routeKey)
        refreshState()
    }

    fun disableAndForget(routeKey: String = PushRouting.LEGACY_ROUTE) = disable(routeKey)

    /** The page's `unsubscribe`: stop delivering for this route and forget its registration. */
    fun forgetRoute(routeKey: String = PushRouting.LEGACY_ROUTE) = disable(routeKey)

    /**
     * Configure a source's relay. Changing it retires the old registration (a token
     * minted by relay A means nothing to relay B) so the state never claims a
     * subscription that does not exist.
     */
    fun setRelayBase(routeKey: String, relay: String?): Boolean {
        if (!isReady()) return false
        val normalized = relay?.let { input ->
            val parsed = PushRelay.normalize(input)
            if (parsed == null) {
                _state.value = _state.value.copy(lastError = PushRelay.refusal(input))
                refreshState()
                return false
            }
            parsed
        }
        val existing = routeStore.route(routeKey) ?: routeStore.ensure(routeKey)
        if (existing.relay == normalized) return true
        if (existing.endpoint != null) retireRoute(routeKey)
        routeStore.setRelay(routeKey, normalized)
        refreshState()
        return true
    }

    private fun retireRoute(routeKey: String) {
        PushStreamService.cancelRoute(routeKey)
        val route = routeStore.route(routeKey)
        routeStore.clearEnrollment(routeKey)
        store.retire(routeKey)
        store.setRegistration(routeKey, null, mirrorLegacyField = routeKey == PushRouting.LEGACY_ROUTE)
        streams.remove(routeKey)
        val endpoint = route?.endpoint
        val relay = route?.relay
        val secret = route?.deviceSecret.orEmpty()
        if (endpoint != null && relay != null && secret.isNotBlank()) {
            val token = endpoint.substringAfterLast('/')
            runCatching {
                apiClient.newCall(
                    Request.Builder()
                        .url(relay.trimEnd('/') + "/v1/register/" + token)
                        // Retiring a subscription is a device capability.
                        .header("Authorization", "Device " + secret)
                        .delete()
                        .build()
                ).execute().close()
            }
        }
        if (routeKey == PushRouting.LEGACY_ROUTE) identity.forgetSecret(routeKey)
    }

    // ---- delivery ------------------------------------------------------------------

    /**
     * Handle one relay message body (base64 ciphertext) for [routeKey]. Returns the
     * notification id when a notification was posted, or null when it was suppressed,
     * a replay, or refused.
     */
    fun handleBody(messageId: String, bodyBase64: String, routeKey: String? = null): Int? {
        if (!isReady() || !transportEnabled()) return null
        // The stream is a route capability, not a hint. Never try another
        // source's secret, even if the ciphertext could be decrypted with it.
        val preferred = routeKey?.takeIf { it.isNotBlank() } ?: deliveryRoute()
        if (routeStore.route(preferred) == null) return null
        val record = identity.load()
        val secrets = linkedMapOf<String, ByteArray>()
        identity.authSecret(preferred, record)?.let { secrets[preferred] = it }
        if (secrets.isEmpty()) {
            debug { "push: no auth secret is enrolled" }
            ack(messageId, preferred)
            return null
        }
        val decoded = try {
            PushMessageDecoder.decodeForRoutes(
                PushCrypto.base64UrlDecode(bodyBase64),
                identity.privateKey(),
                PushCrypto.base64UrlDecode(record.publicKey),
                secrets,
            )
        } catch (e: Exception) {
            debug { "push: decode failed (" + e.javaClass.simpleName + ")" }
            ack(messageId, preferred)
            return null
        }
        val matched = decoded.routeKey?.takeIf { it.isNotBlank() } ?: preferred
        val payload = decoded.payload
        val route = routeStore.route(matched)
        if (route == null) {
            ack(messageId, matched)
            return null
        }
        if (!store.route(matched).enabled) {
            debug { "push: route is not enabled" }
            ack(messageId, matched)
            return null
        }
        val deepLink = if (route.origin.isBlank()) {
            // Migrated subscription with no recorded origin: show the alert, but do not
            // guess a navigation target.
            null
        } else {
            PushMessageDecoder.resolve(payload, route.origin, route.mountPrefix)
        }
        if (route.origin.isNotBlank() && deepLink == null) {
            debug { "push: refused a url outside this route's mount" }
            ack(messageId, matched)
            return null
        }
        if (store.seen(matched, payload.eventId)) {
            ack(messageId, matched)
            return null
        }
        val notificationId = PushRouting.notificationId(matched, payload.eventId ?: messageId)
        val suppressed = leaseActive(matched, route.origin, payload.sessionId)
        val allowed = notifications.allowed()
        if (!suppressed && allowed) {
            notifications.notify(payload, deepLink, matched, notificationId)
        }
        store.record(matched, payload.eventId, payload.createdAt)
        _state.value = _state.value.copy(
            delivered = _state.value.delivered + if (!suppressed && allowed) 1 else 0,
            lastEventId = payload.eventId,
            lastError = if (!suppressed && !allowed) "Android is blocking notifications for this app" else _state.value.lastError,
        )
        ack(messageId, matched)
        return if (suppressed || !allowed) null else notificationId
    }

    private fun ack(messageId: String, routeKey: String) {
        val route = routeStore.route(routeKey) ?: return
        val relay = route.relay ?: return
        val secret = route.deviceSecret
        if (secret.isBlank() || messageId.isBlank()) return
        runCatching {
            apiClient.newCall(
                Request.Builder()
                    .url(relay.trimEnd('/') + "/v1/ack/" + messageId)
                    .post("{}".toRequestBody("application/json".toMediaType()))
                    // ACK is a device capability: the endpoint alone must not be able to
                    // clear someone else's inbox.
                    .header("Authorization", "Device " + secret)
                    .build()
            ).execute().close()
        }
    }

    // ---- presence ------------------------------------------------------------------

    @Volatile private var appVisible = false

    /** A page cannot keep suppressing alerts after its native window stopped. */
    fun noteAppVisibility(visible: Boolean) {
        appVisible = visible
        if (!visible) leases.clear()
    }

    /** Legacy five-argument form: the page's `source` string is its origin. */
    fun recordPresence(source: String, sessionId: String, documentId: String, active: Boolean, expiresAt: String) {
        recordPresence(
            routeKey = routeKeyForOrigin(source) ?: return,
            source = source,
            sessionId = sessionId,
            documentId = documentId,
            active = active,
            expiresAt = expiresAt,
        )
    }

    fun recordPresence(
        routeKey: String,
        source: String,
        sessionId: String,
        documentId: String,
        active: Boolean,
        expiresAt: String,
    ) {
        if (!isReady()) return
        if (routeStore.route(routeKey) == null) return
        val key = leaseKey(routeKey, sessionId)
        if (!active || !appVisible) {
            leases.remove(key)
            return
        }
        val until = runCatching { java.time.Instant.parse(expiresAt).toEpochMilli() }
            .getOrElse { System.currentTimeMillis() + 30_000 }
            .coerceAtMost(System.currentTimeMillis() + 60_000)
        leases[key] = until
    }

    private fun leaseKey(source: String, sessionId: String) = source + "|" + sessionId

    private fun leaseActive(routeKey: String, origin: String, sessionId: String?): Boolean {
        if (!appVisible) return false
        val now = System.currentTimeMillis()
        leases.entries.removeIf { it.value < now }
        // Sibling mounts can share an origin and session id; only the bound
        // source may suppress its own alert.
        return leases.containsKey(leaseKey(routeKey, sessionId.orEmpty())) ||
            leases.containsKey(leaseKey(routeKey, ""))
    }

    fun windowId(): String = if (::identity.isInitialized) identity.windowId else "native_unknown"

    fun documentId(): String = documentIdValue

    private var documentIdValue: String = ""

    fun setDocumentId(value: String) {
        documentIdValue = value
    }

    // ---- stream bookkeeping (called by PushStreamService) ---------------------------

    fun deviceSecret(routeKey: String): String? =
        if (isReady()) routeStore.route(routeKey)?.deviceSecret?.takeIf { it.isNotBlank() } else null

    /** Routes a stream should run for, in deterministic order. */
    fun enrolledEndpoints(): List<Pair<String, String>> {
        if (!isReady()) return emptyList()
        return runCatching {
            routeStore.enrolledRoutes { key -> store.route(key).enabled }
                .mapNotNull { route -> route.endpoint?.let { it to route.id } }
        }.getOrDefault(emptyList())
    }

    fun startStreams(context: Context) {
        val endpoints = enrolledEndpoints()
        if (endpoints.isEmpty()) return
        endpoints.forEach { (endpoint, routeKey) -> PushStreamService.start(context, endpoint, routeKey) }
    }

    fun stopStreams(context: Context) = PushStreamService.stop(context)

    fun streamState(routeKey: String? = null): StreamState =
        if (routeKey == null) _state.value.stream else streams[routeKey] ?: StreamState(routeKey = routeKey)

    fun noteStream(
        routeKey: String,
        phase: StreamPhase,
        endpoint: String? = null,
        attempts: Int = 0,
        error: String? = null,
        permanent: Boolean = false,
    ) {
        val previous = streams[routeKey]
        streams[routeKey] = StreamState(
            phase = phase,
            routeKey = routeKey,
            endpoint = endpoint ?: previous?.endpoint,
            attempts = attempts,
            lastError = error,
            permanent = permanent,
        )
        refreshState()
    }

    /** Every stream stopped (a permanent failure, the API 35 timeout, or user action). */
    fun noteStreamStopped(reason: String?) {
        streams.keys.toList().forEach { key ->
            val previous = streams[key]
            // A permanent failure is already the honest state; a clean stop must not
            // erase "re-enroll this source" before the user has seen it.
            if (reason == null && previous?.phase == StreamPhase.ERROR) return@forEach
            streams[key] = StreamState(
                phase = if (reason == null) StreamPhase.IDLE else StreamPhase.ERROR,
                routeKey = key,
                endpoint = previous?.endpoint,
                attempts = previous?.attempts ?: 0,
                lastError = reason ?: previous?.lastError,
                permanent = previous?.permanent == true,
            )
        }
        refreshState()
    }

    /** Consent for one source (not "some source is enabled"). */
    fun enabledForRoute(routeKey: String): Boolean = isReady() && store.route(routeKey).enabled

    /** The route needs a fresh enrollment (the relay rejected or forgot it). */
    fun markNeedsReenroll(routeKey: String, reason: String) {
        val previous = streams[routeKey]
        streams[routeKey] = StreamState(
            phase = StreamPhase.ERROR,
            routeKey = routeKey,
            endpoint = previous?.endpoint,
            attempts = previous?.attempts ?: 0,
            lastError = reason,
            permanent = true,
        )
        refreshState()
    }

    /** Android refused to start the foreground service (background start restriction). */
    fun noteStartFailure(message: String?) {
        val routeKey = activeRoute()
        noteStream(routeKey, StreamPhase.ERROR, error = message ?: "Android refused to start the notification stream")
    }

    // ---- permission / channel state -------------------------------------------------

    // All four are fail-closed before initialization: a UI that renders before the
    // pipeline is ready must not claim that alerts can be delivered.
    fun notificationsAllowed(): Boolean = isReady() && notifications.allowed()

    fun permissionGranted(): Boolean = isReady() && PushNotifications.permissionGranted(appContext)

    fun alertsChannelEnabled(): Boolean = isReady() && notifications.alertsChannelEnabled()

    fun channelPermissions(): Pair<Boolean, Boolean> {
        if (!isReady()) return false to false
        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val allowed = manager.areNotificationsEnabled()
        return allowed to alertsChannelEnabled()
    }

    /** Ask for POST_NOTIFICATIONS through MainActivity; false when nobody can ask. */
    suspend fun requestNotificationsPermission(): Boolean {
        if (permissionGranted()) return true
        val requester = permissionRequester ?: return false
        return runCatching { requester() }.getOrDefault(false)
    }

    fun setRelayForSource(routeKey: String, relay: String?): Boolean = setRelayBase(routeKey, relay)

    /** Legacy single-route entry points (instrumentation tests, pre-bridge paths). */
    const val ROUTE = PushRouting.LEGACY_ROUTE

    /** Debug-only local relay; never consulted unless this is a debug build. */
    const val RELAY = PushRelay.DEBUG_DEFAULT

    /** Bind the legacy route's origin/mount (the pre-2.x in-memory API). */
    fun setRoute(origin: String, mountPrefix: String) {
        if (!isReady()) return
        routeStore.attach(PushRouting.LEGACY_ROUTE, origin, mountPrefix)
        activeRouteKey = PushRouting.LEGACY_ROUTE
        refreshState()
    }

    fun originFor(routeKey: String): String =
        if (isReady()) routeStore.route(routeKey)?.origin.orEmpty() else ""

    fun mountPrefixFor(routeKey: String): String =
        if (isReady()) (routeStore.route(routeKey)?.mountPrefix ?: "/") else "/"

    private inline fun debug(message: () -> String) {
        if (BuildConfig.DEBUG) Log.i("TermLLM", message())
    }
}
