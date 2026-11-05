package com.termllm.app.push

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * What this device remembers about push: the consent journal (which route the user
 * enabled), the replay ledger (route + event ids already shown) and per-route
 * watermarks.
 *
 * The ledger is keyed by route as well as event id. Two sources can emit the same
 * `event_id` (in practice a per-node counter or a timestamp-derived value), and a
 * single global set used to drop the second source's alert as a "replay".
 *
 * Bounded on purpose: the ledger keeps the newest [LEDGER_CAP] entries so a hostile
 * or buggy sender cannot grow app storage without limit.
 */
class PushDeliveryStore(private val file: File) {

    @Serializable
    data class RouteState(
        /** Consent: the user turned delivery on for this route. */
        val enabled: Boolean = false,
        /** Highest created_at seen, for catch-up ordering. */
        val watermark: String? = null,
        val retired: Boolean = false,
    )

    @Serializable
    data class Ledger(
        val routes: Map<String, RouteState> = emptyMap(),
        /**
         * "route\u0001event" entries. Entries written by a pre-2.x build hold the
         * bare event id and are read as legacy-route entries.
         */
        val events: List<String> = emptyList(),
        /** Pre-2.x global registration; migrated into [registrations] on first read. */
        val registration: String? = null,
        val registrations: Map<String, String> = emptyMap(),
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Synchronized
    fun load(): Ledger {
        if (!file.exists()) return Ledger()
        return runCatching { json.decodeFromString(Ledger.serializer(), file.readText()) }.getOrElse { Ledger() }
    }

    @Synchronized
    fun route(key: String): RouteState = load().routes[key] ?: RouteState()

    @Synchronized
    fun setEnabled(key: String, enabled: Boolean) {
        write(load().let { ledger ->
            ledger.copy(routes = ledger.routes + (key to (ledger.routes[key] ?: RouteState()).copy(enabled = enabled, retired = false)))
        })
    }

    @Synchronized
    fun retire(key: String) {
        write(load().let { ledger ->
            ledger.copy(routes = ledger.routes + (key to (ledger.routes[key] ?: RouteState()).copy(enabled = false, retired = true)))
        })
    }

    @Synchronized
    fun forget(key: String) {
        val ledger = load()
        write(ledger.copy(routes = ledger.routes - key, registrations = ledger.registrations - key))
    }

    /** Legacy global registration (pre-2.x builds, and the legacy route). */
    @Synchronized
    fun registration(): String? {
        val ledger = load()
        return ledger.registrations[PushRouting.LEGACY_ROUTE] ?: ledger.registration
    }

    @Synchronized
    fun registration(routeKey: String): String? {
        val ledger = load()
        return ledger.registrations[routeKey]
            ?: if (routeKey == PushRouting.LEGACY_ROUTE) ledger.registration else null
    }

    /** Legacy setter: records the legacy route, and mirrors the old global field. */
    @Synchronized
    fun setRegistration(endpoint: String?) {
        setRegistration(PushRouting.LEGACY_ROUTE, endpoint, mirrorLegacyField = true)
    }

    @Synchronized
    fun setRegistration(routeKey: String, endpoint: String?, mirrorLegacyField: Boolean = false) {
        val ledger = load()
        val registrations = if (endpoint == null) {
            ledger.registrations - routeKey
        } else {
            ledger.registrations + (routeKey to endpoint)
        }
        write(
            ledger.copy(
                registrations = registrations,
                registration = if (mirrorLegacyField) endpoint else ledger.registration,
            )
        )
    }

    /** True when this event was already delivered for that route (a replay). */
    @Synchronized
    fun seen(routeKey: String, eventId: String?): Boolean {
        if (eventId.isNullOrBlank()) return false
        val events = load().events
        if (events.contains(PushRouting.ledgerKey(routeKey, eventId))) return true
        // Pre-2.x entries were recorded for the single route that existed then.
        return routeKey == PushRouting.LEGACY_ROUTE && events.contains(eventId)
    }

    /** Legacy check: was this event delivered for any route? */
    @Synchronized
    fun seen(eventId: String?): Boolean {
        if (eventId.isNullOrBlank()) return false
        val suffix = PushRouting.EVENT_SEPARATOR + eventId
        return load().events.any { it == eventId || it.endsWith(suffix) }
    }

    /** Record a delivered event for [routeKey], advancing that route's watermark. */
    @Synchronized
    fun record(routeKey: String, eventId: String?, createdAt: String?) {
        write(load().let { ledger ->
            val key = if (eventId.isNullOrBlank()) null else PushRouting.ledgerKey(routeKey, eventId)
            val events = when {
                key == null -> ledger.events
                ledger.events.contains(key) -> ledger.events
                else -> (ledger.events + key).takeLast(LEDGER_CAP)
            }
            val state = ledger.routes[routeKey] ?: RouteState(enabled = true)
            val watermark = when {
                createdAt.isNullOrBlank() -> state.watermark
                state.watermark == null -> createdAt
                createdAt > state.watermark -> createdAt
                else -> state.watermark
            }
            ledger.copy(
                events = events,
                routes = ledger.routes + (routeKey to state.copy(watermark = watermark)),
            )
        })
    }

    private fun write(next: Ledger) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        val text = json.encodeToString(Ledger.serializer(), next)
        temp.writeText(text)
        if (!temp.renameTo(file)) {
            file.writeText(text)
            temp.delete()
        }
    }

    companion object {
        const val LEDGER_CAP = 200
    }
}
