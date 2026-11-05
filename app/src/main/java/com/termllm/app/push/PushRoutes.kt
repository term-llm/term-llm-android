package com.termllm.app.push

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * One source's push wiring. The audit's finding was that every source shared a
 * single in-memory origin/mount and one global relay, so a second source either
 * overwrote the first (colliding ids) or delivered deep links built for the wrong
 * mount. Everything needed to route a message is therefore persisted per route:
 * the exact origin and mount the page was loaded from, the relay it enrolled with
 * and the credentials that relay returned.
 *
 * Consent stays in [PushDeliveryStore] (it already journals enable/retire and the
 * replay ledger); this store only holds transport/routing metadata, so the two
 * cannot disagree about what "enabled" means.
 */
@Serializable
data class PushRoute(
    val id: String,
    /** Scheme + host + port the document was served from; "" until a page attaches. */
    val origin: String = "",
    /** Mount prefix with a trailing slash ("/" for a root install). */
    val mountPrefix: String = "/",
    /** Relay base URL this route enrolled with, if any. */
    val relay: String? = null,
    /** This route's device stream endpoint (SSE). */
    val endpoint: String? = null,
    /** The Web Push URL the node posts to (handed to `/v1/push/subscribe`). */
    val pushEndpoint: String? = null,
    /** Device capability for ACK/delete on this route's relay registration. */
    val deviceSecret: String = "",
    /** VAPID key the page enrolled with, kept so a re-enroll can reuse it. */
    val vapidKey: String? = null,
    /** True when this route inherited a pre-2.x single-route subscription. */
    val adoptedLegacy: Boolean = false,
    val updatedAt: Long = 0L,
) {
    /** Endpoint token, i.e. the part after the last "/" (safe to show, never a secret). */
    val token: String? get() = endpoint?.substringAfterLast('/')?.takeIf { it.isNotBlank() }

    val enrolled: Boolean get() = !endpoint.isNullOrBlank() && !pushEndpoint.isNullOrBlank()
}

@Serializable
data class PushRouteTable(
    val version: Int = PushRouteStore.VERSION,
    val routes: Map<String, PushRoute> = emptyMap(),
    /** The pre-2.x global enrollment has been examined (whether or not it existed). */
    val legacyMigrated: Boolean = false,
    /** The legacy subscription was adopted by this connection id. */
    val legacyAdoptedBy: String? = null,
)

/** Outcome of attaching a document's origin/mount to a route. */
data class PushRouteAttach(
    val route: PushRoute,
    val created: Boolean,
    /**
     * The route was previously attached to a different origin. The old subscription
     * was minted for that origin, so it must be retired rather than reused.
     */
    val originChanged: Boolean,
)

/**
 * File-backed route table. Writes go through a temp file + rename so a process death
 * mid-write cannot leave a half-written table that loses every subscription, and all
 * read-modify-write cycles are synchronized because the bridge, the stream service
 * and Settings all touch it.
 */
class PushRouteStore(private val file: File) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Synchronized
    fun load(): PushRouteTable {
        if (!file.exists()) return PushRouteTable()
        return runCatching { json.decodeFromString(PushRouteTable.serializer(), file.readText()) }
            .getOrElse { PushRouteTable() }
    }

    @Synchronized
    fun routes(): Map<String, PushRoute> = load().routes

    @Synchronized
    fun route(id: String): PushRoute? = load().routes[id]

    /** Route that was attached from [origin], if any (bridge fallback). */
    @Synchronized
    fun routeForOrigin(origin: String): PushRoute? {
        val normalized = origin.trimEnd('/')
        return load().routes.values.singleOrNull { it.origin.isNotEmpty() && it.origin.trimEnd('/') == normalized }
    }

    @Synchronized
    fun routeForEndpoint(endpoint: String): PushRoute? {
        val normalized = endpoint.trimEnd('/')
        return load().routes.values.firstOrNull { it.endpoint?.trimEnd('/') == normalized }
    }

    /**
     * Bind [id] to the document's exact origin and mount. Existing enrollment fields
     * are preserved; only routing metadata changes.
     */
    @Synchronized
    fun attach(id: String, origin: String, mountPrefix: String): PushRouteAttach {
        require(id.isNotBlank()) { "a push route needs an id" }
        val table = load()
        val existing = table.routes[id]
        val normalizedOrigin = origin.trimEnd('/')
        val route = (existing ?: PushRoute(id = id)).copy(
            origin = normalizedOrigin,
            mountPrefix = PushRouting.mountPrefix(mountPrefix),
            updatedAt = System.currentTimeMillis(),
        )
        write(table.copy(routes = table.routes + (id to route)))
        return PushRouteAttach(
            route = route,
            created = existing == null,
            originChanged = existing != null && existing.origin.isNotEmpty() && existing.origin != normalizedOrigin,
        )
    }

    /** Create [id] with no routing metadata yet (first enrollment, before a page loads). */
    @Synchronized
    fun ensure(id: String): PushRoute {
        val table = load()
        table.routes[id]?.let { return it }
        val route = PushRoute(id = id, updatedAt = System.currentTimeMillis())
        write(table.copy(routes = table.routes + (id to route)))
        return route
    }

    @Synchronized
    fun setRelay(id: String, relay: String?): PushRoute? {
        val table = load()
        val existing = table.routes[id] ?: PushRoute(id = id)
        val route = existing.copy(relay = relay, updatedAt = System.currentTimeMillis())
        write(table.copy(routes = table.routes + (id to route)))
        return route
    }

    /** Persist a successful relay registration for one route. */
    @Synchronized
    fun recordEnrollment(
        id: String,
        endpoint: String,
        pushEndpoint: String,
        deviceSecret: String,
        relay: String,
        vapidKey: String?,
    ): PushRoute {
        val table = load()
        val existing = table.routes[id] ?: PushRoute(id = id)
        val route = existing.copy(
            endpoint = endpoint,
            pushEndpoint = pushEndpoint,
            deviceSecret = deviceSecret,
            relay = relay,
            vapidKey = vapidKey ?: existing.vapidKey,
            updatedAt = System.currentTimeMillis(),
        )
        write(table.copy(routes = table.routes + (id to route)))
        return route
    }

    @Synchronized
    fun setVapidKey(id: String, vapidKey: String?): PushRoute? {
        val table = load()
        val existing = table.routes[id] ?: return null
        val route = existing.copy(vapidKey = vapidKey, updatedAt = System.currentTimeMillis())
        write(table.copy(routes = table.routes + (id to route)))
        return route
    }

    /** Drop the relay registration but keep origin/mount/relay configuration. */
    @Synchronized
    fun clearEnrollment(id: String): PushRoute? {
        val table = load()
        val existing = table.routes[id] ?: return null
        val route = existing.copy(
            endpoint = null,
            pushEndpoint = null,
            deviceSecret = "",
            updatedAt = System.currentTimeMillis(),
        )
        write(table.copy(routes = table.routes + (id to route)))
        return route
    }

    @Synchronized
    fun remove(id: String): PushRouteTable {
        val table = load()
        val next = table.copy(routes = table.routes - id)
        write(next)
        return next
    }

    /**
     * Import the pre-2.x single-route enrollment (global endpoint/relay/secret in
     * `identity.json`) exactly once. The old origin lived only in memory, so the
     * imported route deliberately has no origin: until a page attaches, alerts are
     * shown without a tap target rather than navigating somewhere guessed.
     *
     * Returns true when a legacy route was created.
     */
    @Synchronized
    fun migrateLegacy(
        enabled: Boolean,
        endpoint: String?,
        pushEndpoint: String?,
        relay: String?,
        deviceSecret: String?,
    ): Boolean {
        val table = load()
        if (table.legacyMigrated) return false
        if (!enabled || endpoint.isNullOrBlank() || table.routes.containsKey(PushRouting.LEGACY_ROUTE)) {
            write(table.copy(legacyMigrated = true))
            return false
        }
        val route = PushRoute(
            id = PushRouting.LEGACY_ROUTE,
            origin = "",
            mountPrefix = "/",
            relay = relay,
            endpoint = endpoint,
            pushEndpoint = pushEndpoint,
            deviceSecret = deviceSecret.orEmpty(),
            adoptedLegacy = true,
            updatedAt = System.currentTimeMillis(),
        )
        write(table.copy(routes = table.routes + (PushRouting.LEGACY_ROUTE to route), legacyMigrated = true))
        return true
    }

    /**
     * Hand the unclaimed legacy subscription to [id] — but only when the catalog has
     * exactly one source, because nothing in the old build recorded which source it
     * belonged to. With two or more sources the legacy route is left alone (and stays
     * visible in Settings) instead of pushing one source's alerts into another's
     * mount.
     */
    @Synchronized
    fun adoptLegacy(connectionCount: Int, id: String, origin: String, mountPrefix: String): PushRoute? {
        if (connectionCount != 1) return null
        if (id == PushRouting.LEGACY_ROUTE) return null
        val table = load()
        if (table.legacyAdoptedBy != null) return null
        val legacy = table.routes[PushRouting.LEGACY_ROUTE] ?: return null
        if (legacy.origin.isNotEmpty()) return null
        val existing = table.routes[id]
        val adopted = (existing ?: PushRoute(id = id)).copy(
            origin = origin.trimEnd('/'),
            mountPrefix = PushRouting.mountPrefix(mountPrefix),
            relay = existing?.relay ?: legacy.relay,
            endpoint = existing?.endpoint ?: legacy.endpoint,
            pushEndpoint = existing?.pushEndpoint ?: legacy.pushEndpoint,
            deviceSecret = existing?.deviceSecret?.takeIf { it.isNotBlank() } ?: legacy.deviceSecret,
            vapidKey = existing?.vapidKey ?: legacy.vapidKey,
            adoptedLegacy = true,
            updatedAt = System.currentTimeMillis(),
        )
        write(
            table.copy(
                routes = (table.routes - PushRouting.LEGACY_ROUTE) + (id to adopted),
                legacyAdoptedBy = id,
            )
        )
        return adopted
    }

    /**
     * Routes a stream should run for: enrolled and consented. Ordered by id so the
     * service starts deterministically.
     */
    @Synchronized
    fun enrolledRoutes(enabled: (String) -> Boolean): List<PushRoute> =
        load().routes.values
            .filter { it.enrolled && enabled(it.id) }
            .sortedBy { it.id }

    private fun write(next: PushRouteTable) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        val text = json.encodeToString(PushRouteTable.serializer(), next.copy(version = VERSION))
        temp.writeText(text)
        if (!temp.renameTo(file)) {
            // Some filesystems refuse rename over an existing file; fall back to a
            // direct write rather than losing the update.
            file.writeText(text)
            temp.delete()
        }
    }

    companion object {
        const val VERSION = 2
    }
}
