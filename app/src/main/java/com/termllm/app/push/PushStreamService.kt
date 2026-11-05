package com.termllm.app.push

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.termllm.app.BuildConfig
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

/**
 * Holds the relay's device streams open (text/event-stream) so pushes arrive while
 * the app is open in the background.
 *
 * Lifecycle hardening from the audit:
 *  * **Switching endpoints**: a start request for a route whose endpoint changed
 *    cancels the old call and starts a new worker; it is no longer ignored because
 *    "the service is already running".
 *  * **No uncancelled reads**: every worker owns its [Call]; stop, switch and process
 *    teardown cancel it, so a silent socket cannot keep the service (or its
 *    foreground notification) alive.
 *  * **Stall timeout**: the read timeout is a keepalive watchdog rather than
 *    `0` (infinite), so a dead-but-open socket reconnects.
 *  * **Android 15 foreground-service timeout**: `onTimeout` stops cleanly and records
 *    why, instead of letting the OS kill the process mid-notification.
 *  * **Backoff**: bounded exponential with jitter; credential/subscription failures
 *    (401/403/404/410) are permanent and stop the retry loop.
 *
 * Android can stop a foreground service (Doze, battery saver, OEM killers, process
 * death). When that happens delivery pauses — the relay keeps a bounded backlog and
 * the next start replays it, which is why the Settings copy promises "while the
 * stream is running", never "always".
 */
class PushStreamService : Service() {

    private class Worker(val routeKey: String, val endpoint: String, val secret: String?) {
        val call = AtomicReference<Call?>(null)
        @Volatile var thread: Thread? = null
    }

    private val workers = ConcurrentHashMap<String, Worker>()

    @Volatile
    private var running = false

    private val random = Random(System.nanoTime())

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // A keepalive watchdog, not an infinite read: see PushStreamPolicy.
        .readTimeout(PushStreamPolicy.STALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    override fun onCreate() { super.onCreate(); activeInstance.set(this) }

    override fun onBind(intent: Intent?): IBinder? = null

    private val control = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r,"push-control").apply { isDaemon=true } }
    private val controlGeneration = java.util.concurrent.atomic.AtomicLong()
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopEverything(null)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_TIMEOUT) {
            // Deterministic stand-in for the Android 15 callback, so the timeout path
            // can be exercised on any emulator image. Post the foreground notice first:
            // a startForegroundService() call that never calls startForeground() is an
            // ANR on Android 12+.
            ensureForeground()
            onTimeout(startId)
            return START_NOT_STICKY
        }
        running = true
        ensureForeground()
        val generation = controlGeneration.get()
        control.execute {
            PushCenter.ensureInit(applicationContext)
            if (running && generation == controlGeneration.get()) {
                if (!PushCenter.transportEnabled()) stopEverything(null)
                else {
                    val targets = resolveTargets(intent).filter { (endpoint, key) ->
                        PushCenter.state.value.routes[key]?.let { it.enabled && it.enrolled && it.endpoint == endpoint } == true
                    }
                    if (targets.isEmpty()) { if (workers.isEmpty()) stopEverything(null) }
                    else targets.forEach { (endpoint, routeKey) -> startWorker(routeKey, endpoint) }
                }
            }
        }
        // Not sticky: a system restart with a null intent would only re-run a stream
        // that a credential failure (or the user) already ended.
        return START_NOT_STICKY
    }

    /**
     * Which routes this start request is for: the explicit one, or every enrolled
     * route on a restart. Kept out of [onStartCommand] so the start path stays a
     * straight sequence of statements.
     */
    private fun resolveTargets(intent: Intent?): List<Pair<String, String>> {
        val requested = intent?.getStringExtra(EXTRA_ENDPOINT)
        if (requested.isNullOrBlank()) return PushCenter.enrolledEndpoints()
        val routeKey = intent?.getStringExtra(EXTRA_ROUTE)
            ?: PushCenter.routeKeyForEndpoint(requested)
            ?: PushCenter.ROUTE
        return listOf(requested to routeKey)
    }

    private fun ensureForeground() {
        val notification = PushNotifications(this).serviceNotification("Connected to the notification relay")
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    PushNotifications.SERVICE_NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(PushNotifications.SERVICE_NOTIFICATION_ID, notification)
            }
        }.onFailure { failure ->
            PushCenter.noteStartFailure(failure.message ?: "Android refused the foreground notification")
        }
    }

    /** Start or switch one route's worker. Same endpoint + live thread is a no-op. */
    private fun noteActive(worker: Worker) {
        PushCenter.noteStream(
            routeKey = worker.routeKey,
            phase = PushCenter.StreamPhase.ACTIVE,
            endpoint = worker.endpoint,
            attempts = 0,
            error = null,
        )
    }

    private fun startWorker(routeKey: String, endpoint: String) {
        val existing = workers[routeKey]
        val secret = PushCenter.deviceSecret(routeKey)
        if (existing != null && existing.secret == secret &&
            PushStreamPolicy.sameEndpoint(existing.endpoint, endpoint) &&
            existing.thread?.isAlive == true
        ) {
            return
        }
        existing?.let { stopWorker(it) }
        val worker = Worker(routeKey = routeKey, endpoint = endpoint, secret = secret)
        workers[routeKey] = worker
        val thread = Thread({ stream(worker) }, "push-stream-" + routeKey.take(8))
        worker.thread = thread
        thread.start()
    }

    private fun stopWorker(worker: Worker) {
        worker.call.getAndSet(null)?.cancel()
        worker.thread?.interrupt()
        workers.remove(worker.routeKey, worker)
    }

    private fun isCurrent(worker: Worker): Boolean =
        running && workers[worker.routeKey] === worker

    private fun stream(worker: Worker) {
        var attempt = 0
        var lastError: String? = null
        while (isCurrent(worker)) {
            val secret = worker.secret
            if (secret.isNullOrBlank()) {
                PushCenter.markNeedsReenroll(worker.routeKey, "this source has no relay credentials yet")
                break
            }
            val call = client.newCall(
                Request.Builder()
                    .url(worker.endpoint)
                    .header("Accept", "text/event-stream")
                    .header("Authorization", "Device " + secret)
                    .build()
            )
            worker.call.set(call)
            var verdict: PushStreamPolicy.Verdict = PushStreamPolicy.Verdict.Retry
            PushCenter.noteStream(
                routeKey = worker.routeKey,
                phase = PushCenter.StreamPhase.CONNECTING,
                endpoint = worker.endpoint,
                attempts = attempt,
                error = lastError,
            )
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        verdict = PushStreamPolicy.classify(response.code)
                        lastError = (verdict as? PushStreamPolicy.Verdict.Permanent)?.reason
                            ?: "the relay ended the stream (HTTP " + response.code + ")"
                    } else {
                        attempt = 0
                        noteActive(worker)
                        val body = response.body?.source()
                        if (body == null) {
                            lastError = "the relay returned an empty stream"
                        } else {
                            var messageId = ""
                            while (isCurrent(worker)) {
                                val line = body.readUtf8Line() ?: break
                                when {
                                    line.startsWith("data:") -> {
                                        val payload = line.removePrefix("data:").trim()
                                        val json = runCatching { org.json.JSONObject(payload) }.getOrNull()
                                        val message = json?.optString("body")?.takeIf { it.isNotBlank() }
                                        messageId = json?.optString("id").orEmpty()
                                        if (message != null) {
                                            PushCenter.handleBody(messageId, message, worker.routeKey)
                                        }
                                    }
                                    line.startsWith(":") -> Unit // keepalive
                                }
                            }
                            lastError = "the relay closed the stream"
                        }
                    }
                }
            } catch (e: Exception) {
                // Cancellation from stop/switch is expected; anything else is transient.
                if (!isCurrent(worker)) break
                verdict = PushStreamPolicy.classify(null)
                lastError = e.message ?: "the stream failed"
            } finally {
                worker.call.compareAndSet(call, null)
            }
            if (!isCurrent(worker)) break
            val permanent = verdict as? PushStreamPolicy.Verdict.Permanent
            if (permanent != null) {
                // Retrying a credential failure forever would hide the only fix there is.
                PushCenter.markNeedsReenroll(worker.routeKey, permanent.reason)
                debugLog("push stream stopped: " + permanent.reason)
                break
            }
            attempt += 1
            val delay = PushStreamPolicy.backoffMs(attempt, random)
            PushCenter.noteStream(
                routeKey = worker.routeKey,
                phase = PushCenter.StreamPhase.RETRYING,
                endpoint = worker.endpoint,
                attempts = attempt,
                error = lastError,
            )
            try {
                Thread.sleep(delay)
            } catch (e: InterruptedException) {
                break
            }
        }
        val removed = workers.remove(worker.routeKey, worker)
        if (removed && workers.isEmpty() && running) {
            stopEverything(null)
        }
    }

    /**
     * Android 15+: a `dataSync` foreground service may only run for a bounded time.
     * Stop honestly (and say why) instead of being killed with no record.
     *
     * The one-argument callback is the API 34 shortService form; the two-argument
     * callback handles API 35 dataSync/mediaProcessing budgets. Both stop cleanly;
     * the platform-generated dataSync timeout is exercised on the API 35 emulator.
     */
    override fun onTimeout(startId: Int) {
        if (Build.VERSION.SDK_INT >= 34) super.onTimeout(startId)
        stopEverything(
            "Android stopped the notification stream (foreground service timeout); it will resume next launch"
        )
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        if (Build.VERSION.SDK_INT >= 35) super.onTimeout(startId, fgsType)
        stopEverything(
            "Android stopped the notification stream (foreground service timeout); it will resume next launch"
        )
    }

    private fun stopEverything(reason: String?) {
        running = false
        controlGeneration.incrementAndGet()
        workers.values.toList().forEach { worker ->
            worker.call.getAndSet(null)?.cancel()
            worker.thread?.interrupt()
        }
        workers.clear()
        runCatching { control.execute { if (activeInstance.get() == null || activeInstance.get() === this) PushCenter.noteStreamStopped(reason) } }
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    override fun onDestroy() {
        activeInstance.compareAndSet(this, null)
        running = false
        controlGeneration.incrementAndGet()
        workers.values.toList().forEach { worker ->
            worker.call.getAndSet(null)?.cancel()
            worker.thread?.interrupt()
        }
        workers.clear()
        control.execute { if (activeInstance.get() == null) PushCenter.noteStreamStopped(null) }
        control.shutdown()
        super.onDestroy()
    }

    private inline fun debugLog(message: String) {
        if (BuildConfig.DEBUG) Log.i("TermLLM", message)
    }

    companion object {
        private val activeInstance = AtomicReference<PushStreamService?>(null)

        /** Cancel only this source, without starting a background service. */
        fun cancelRoute(routeKey: String) {
            val service = activeInstance.get() ?: return
            runCatching { service.control.execute {
                if (activeInstance.get() !== service || !service.running) return@execute
                service.workers[routeKey]?.let { service.stopWorker(it) }
                PushCenter.noteStream(routeKey, PushCenter.StreamPhase.IDLE)
                if (service.workers.isEmpty()) service.stopEverything(null)
            } }
        }

        const val ACTION_STOP = "com.termllm.app.push.STOP"
        const val ACTION_TIMEOUT = "com.termllm.app.push.TIMEOUT"
        const val EXTRA_ENDPOINT = "endpoint"
        const val EXTRA_ROUTE = "route"

        /**
         * Start or switch this route's stream. [routeKey] identifies the source whose
         * credentials and origin/mount the messages belong to; when omitted the route
         * is inferred from the endpoint that is already persisted.
         */
        fun start(context: Context, endpoint: String, routeKey: String? = null) {
            val intent = Intent(context, PushStreamService::class.java).putExtra(EXTRA_ENDPOINT, endpoint)
            if (routeKey != null) intent.putExtra(EXTRA_ROUTE, routeKey)
            runCatching { context.startForegroundService(intent) }.onFailure { failure ->
                // Background start restrictions (API 31+) are a real outcome, not a crash.
                PushCenter.noteStartFailure(failure.message)
            }
        }

        /**
         * Stop every stream. `stopService` is allowed from the background and runs
         * `onDestroy`, which cancels the in-flight calls.
         */
        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, PushStreamService::class.java)) }
        }

        /**
         * Test seam for the Android 15 foreground-service timeout: runs the same
         * `onTimeout` path the platform would, so a device test does not have to keep
         * a `dataSync` service alive for six hours.
         */
        fun simulateTimeoutForTest(context: Context) {
            val intent = Intent(context, PushStreamService::class.java).setAction(ACTION_TIMEOUT)
            runCatching { context.startForegroundService(intent) }
                .onFailure { failure -> PushCenter.noteStartFailure(failure.message) }
        }
    }
}
