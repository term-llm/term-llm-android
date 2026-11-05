package com.termllm.app.push

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.termllm.app.MainActivity

/**
 * Notification channels, grouping and tap routing for push.
 *
 * Two channels on purpose: alerts (default importance, the reason the user enabled
 * push) and the stream's own required foreground notice on a low-importance channel,
 * so "push is on" can never be confused with "the service is running".
 */
class PushNotifications(private val context: Context) {

    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun ensureChannels() {
        manager.createNotificationChannel(
            NotificationChannel(ALERTS, "Agent alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "When an agent finishes, needs input, or a run changes."
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(SERVICE, "Push connection", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Keeps the notification stream open while push is enabled."
            }
        )
    }

    fun serviceNotification(text: String): Notification =
        NotificationCompat.Builder(context, SERVICE)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Agent notifications")
            .setContentText(text)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    /** App-level notifications switch (Android 13+ still needs the runtime permission). */
    fun allowed(): Boolean =
        manager.areNotificationsEnabled() && alertsChannelEnabled()

    fun alertsChannelEnabled(): Boolean {
        val channel = manager.getNotificationChannel(ALERTS) ?: return true
        return channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun cancel(id: Int) = manager.cancel(id)

    /**
     * Post one alert; returns the notification id used (also the ACK handle).
     *
     * [deepLink] is null when the route has no verified origin yet (a migrated
     * pre-2.x subscription, or a payload that named another mount). The alert is
     * still useful — tapping only opens the app rather than navigating to a guessed
     * URL.
     */
    fun notify(payload: PushPayload, deepLink: String?, routeKey: String, notificationId: Int): Int {
        ensureChannels()
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_ROUTE, routeKey)
            if (deepLink != null) putExtra(EXTRA_DEEP_LINK, deepLink)
            payload.sessionId?.let { putExtra(EXTRA_SESSION, it) }
            payload.eventId?.let { putExtra(EXTRA_EVENT, it) }
        }
        val pending = PendingIntent.getActivity(
            context,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, ALERTS)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(payload.title.ifBlank { "Agent" })
            .setContentText(payload.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(payload.body))
            .setGroup(PushRouting.groupKey(routeKey, payload.sessionId))
            // Lock-screen text stays generic by default: the body carries agent output.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        manager.notify(notificationId, notification)
        return notificationId
    }

    companion object {
        const val ALERTS = "push_alerts"
        const val SERVICE = "push_service"
        const val SERVICE_NOTIFICATION_ID = 4101
        const val EXTRA_DEEP_LINK = "push.deep_link"
        const val EXTRA_ROUTE = "push.route"
        const val EXTRA_SESSION = "push.session"
        const val EXTRA_EVENT = "push.event"

        fun groupKey(routeKey: String, sessionId: String?): String =
            PushRouting.groupKey(routeKey, sessionId)

        /** Whether POST_NOTIFICATIONS is granted (always true below Android 13). */
        fun permissionGranted(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < PushPermissionPolicy.RUNTIME_PERMISSION_SDK) return true
            return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        }

        /**
         * Whether asking would still show the system prompt. A null/!Activity context
         * cannot answer, so it reports true and the caller may launch a prompt that
         * the OS turns into a no-op rather than sending the user to settings wrongly.
         */
        fun markPromptRequested(context: Context) {
            context.getSharedPreferences("termllm_settings", Context.MODE_PRIVATE).edit().putBoolean("notification_prompt_asked",true).apply()
        }
        fun canAskAgain(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < PushPermissionPolicy.RUNTIME_PERMISSION_SDK) return false
            // shouldShowRationale is false BOTH before the first request and after
            // permanent denial. Persist whether we actually asked to distinguish them.
            if (!context.getSharedPreferences("termllm_settings", Context.MODE_PRIVATE).getBoolean("notification_prompt_asked",false)) return true
            var owner = context
            while (owner is android.content.ContextWrapper && owner !is Activity) owner = owner.baseContext
            val activity = owner as? Activity ?: return false
            return ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)
        }

        /** App notification settings, where a permanently denied permission is fixed. */
        fun openAppNotificationSettings(context: Context) {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
                .recoverCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            .setData(Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
        }

        /** The Agent alerts channel, when only that channel was turned off. */
        fun openAlertsChannelSettings(context: Context) {
            val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, ALERTS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
                .recoverCatching { openAppNotificationSettings(context) }
        }
    }
}
