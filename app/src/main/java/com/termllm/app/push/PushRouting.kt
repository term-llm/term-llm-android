package com.termllm.app.push

/**
 * Pure routing helpers shared by the delivery pipeline, the notification layer and
 * the settings UI. Everything here is string arithmetic so the rules can be tested
 * on the JVM: which route a notification belongs to, and the identifiers derived
 * from it.
 *
 * Two sources can legitimately carry the same session id, the same event id and the
 * same url path; only the pair (route, event) identifies a delivery. Collapsing any
 * of that to a single global value is what made the old single-route build cross
 * wires, so identifiers are always built from the route key.
 */
object PushRouting {
    /** A stale notification must not clear a new store's colliding session/sequence. */
    fun matchesReadIdentity(session: String, store: String, sequence: Long,
                            currentSession: String, currentStore: String?, currentSequence: Int): Boolean =
        session.isNotBlank() && store.isNotBlank() && sequence > 0 &&
            session == currentSession && store == currentStore && sequence == currentSequence.toLong()


    /**
     * The pre-2.x route id ("source"). It is still the key of a legacy enrollment
     * that has not been adopted by a connection yet, and the default for the
     * single-route entry points the instrumentation tests use.
     */
    const val LEGACY_ROUTE: String = "source"

    /** Agent-alert group: one group per route and session, so sources never merge. */
    fun groupKey(routeKey: String, sessionId: String?): String =
        "push:" + routeKey + ":" + (sessionId ?: "general")

    /** Stable per-route notification id; never collides across sources by accident. */
    fun notificationId(routeKey: String, eventId: String): Int {
        val hash = (routeKey + "|" + eventId).hashCode()
        val id = hash and 0x0fffffff
        // 0 is a legal notification id but a useless one to read back in a log or a
        // test, so fold it onto a small non-zero value.
        return if (id == 0) 0x0ffffffe else id
    }

    /**
     * A mount prefix always starts and ends with "/" so `startsWith` cannot match a
     * sibling mount ("/ui" must not accept "/uix/..."). Root mounts stay "/".
     */
    fun mountPrefix(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty() || trimmed == "/") return "/"
        val leading = if (trimmed.startsWith("/")) trimmed else "/" + trimmed
        return if (leading.endsWith("/")) leading else leading + "/"
    }

    /** The mount without its trailing slash ("" for a root mount), for display. */
    fun mountPath(raw: String?): String {
        val prefix = mountPrefix(raw)
        return if (prefix == "/") "" else prefix.trimEnd('/')
    }

    /**
     * A url is inside a mount when it is the mount itself or a descendant of it.
     * `startsWith` alone would accept "/uix/1" for mount "/ui".
     */
    fun pathInMount(path: String, rawMount: String): Boolean {
        val mount = mountPrefix(rawMount)
        if (mount == "/") return path.startsWith("/")
        return path == mount || path == mount.trimEnd('/') || path.startsWith(mount)
    }

    /** Event ids are remembered per route; the separator cannot appear in either half. */
    internal const val EVENT_SEPARATOR: Char = '\u0001'

    fun ledgerKey(routeKey: String, eventId: String): String =
        routeKey + EVENT_SEPARATOR + eventId
}
