package com.termllm.app.tools

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Built-in device tools, ported from the iOS `BuiltInDeviceTools`:
 *
 *  - `ping` answers "pong from term-llm Android app at <ISO-8601>" and echoes an
 *    optional message;
 *  - `device_info` reports model, Android version, app version, battery level and
 *    charging state, battery saver, and whether this app is in the foreground.
 *
 * Values are read from the real device each call (never cached), so a snapshot is
 * truthful at the moment the agent asks for it.
 */
class DeviceTools(
    private val context: Context,
    private val appVersion: String,
    private val foreground: () -> Boolean,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    fun handle(name: String, argumentsJson: String): String {
        val args = runCatching { if (argumentsJson.isBlank()) JSONObject() else JSONObject(argumentsJson) }
            .getOrElse { JSONObject() }
        return when (name) {
            "ping" -> ping(args).toString()
            "device_info" -> deviceInfo().toString()
            else -> throw IllegalArgumentException("Unknown tool: " + name)
        }
    }

    private fun ping(args: JSONObject): JSONObject {
        val raw = args.opt("message")
        if (raw != null && raw != JSONObject.NULL && raw !is String) {
            throw IllegalArgumentException("message must be a string")
        }
        val text = StringBuilder("pong from term-llm Android app at " + iso8601(now()))
        val message = raw as? String
        if (!message.isNullOrEmpty()) text.append("\necho: ").append(message)
        return JSONObject().put("text", text.toString())
    }

    private fun deviceInfo(): JSONObject {
        val info = JSONObject()
            .put("model", listOfNotNull(Build.MANUFACTURER?.takeIf { it.isNotBlank() }, Build.MODEL).joinToString(" "))
            .put("system", "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")")
            .put("app_version", appVersion)
            .put("battery_state", batteryState())
            .put("low_power_mode", isPowerSaveMode())
            .put("app_state", if (foreground()) "active" else "background")
        batteryPercent()?.let { info.put("battery_percent", it) }
        return info
    }

    private fun batteryPercent(): Int? {
        val manager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return null
        val level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return if (level in 0..100) level else null
    }

    /** Mirrors the iOS vocabulary: charging / full / unplugged / unknown. */
    private fun batteryState(): String {
        val intent: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            BatteryManager.BATTERY_STATUS_DISCHARGING, BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "unplugged"
            else -> "unknown"
        }
    }

    private fun isPowerSaveMode(): Boolean {
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return power.isPowerSaveMode
    }

    companion object {
        /** True when the app currently owns the top resumed activity and has focus. */
        fun isForeground(context: Context): Boolean {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return true
            val processes = manager.runningAppProcesses ?: return true
            val own = processes.firstOrNull { it.processName == context.packageName } ?: return true
            return own.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }

        fun iso8601(epochMillis: Long): String {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            format.timeZone = TimeZone.getTimeZone("UTC")
            return format.format(Date(epochMillis))
        }
    }
}
