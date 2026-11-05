package com.termllm.app.chat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.termllm.app.MainActivity

/**
 * Keeps a live voice call alive while the app is not in front. Android 14+
 * stops microphone access for a backgrounded app unless a foreground service of
 * type `microphone` is running; iOS gets the same effect from its background
 * audio entitlement. Started when the page begins capturing, stopped when the
 * capture ends. It owns no audio itself: the WebView does the capture.
 */
class LiveCallService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Live voice"
        val notification = build(this, title)
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    private fun build(context: Context, title: String): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Live voice call", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while a live voice conversation uses the microphone."
                }
            )
        }
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title)
            .setContentText("Microphone in use by a live voice conversation")
            .setOngoing(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
    }

    companion object {
        const val CHANNEL = "live_call"
        const val NOTIFICATION_ID = 7301
        const val ACTION_STOP = "com.termllm.app.LIVE_CALL_STOP"
        const val EXTRA_TITLE = "title"

        fun start(context: Context, title: String) {
            val intent = Intent(context, LiveCallService::class.java).putExtra(EXTRA_TITLE, title)
            runCatching { androidx.core.content.ContextCompat.startForegroundService(context, intent) }
                .onFailure { android.util.Log.w("TermLLM", "live call service: " + it.message) }
        }

        fun stop(context: Context) {
            runCatching { context.startService(Intent(context, LiveCallService::class.java).setAction(ACTION_STOP)) }
        }
    }
}
