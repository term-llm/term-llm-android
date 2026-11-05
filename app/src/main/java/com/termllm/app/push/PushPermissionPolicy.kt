package com.termllm.app.push

/**
 * The four notification states the app must never conflate: the user's preference,
 * the OS runtime permission (Android 13+), the alerts channel, and whether this
 * device is actually enrolled with a relay.
 *
 * Keeping the decision table here (and pure) means Settings can behave the same way
 * on Android 12, 13 and 15 without the Compose layer re-deriving the rules, and the
 * "toggle does nothing" bug from the audit cannot come back unnoticed.
 */
object PushPermissionPolicy {

    /** POST_NOTIFICATIONS became a runtime permission in Android 13. */
    const val RUNTIME_PERMISSION_SDK: Int = 33

    enum class Permission {
        /** Android 12 and older: notifications are a user setting, not a prompt. */
        NOT_REQUIRED,
        GRANTED,
        /** Not granted, and the OS would still show the prompt. */
        DENIED_CAN_ASK,
        /** Denied with "don't ask again" (or two denials): only system settings fix it. */
        DENIED_PERMANENT,
    }

    /** What flipping the preference on should do next. */
    enum class ToggleAction {
        /** Just store the preference; delivery can happen. */
        ENABLE,
        /** Ask the OS for POST_NOTIFICATIONS, then enable if granted. */
        REQUEST_PERMISSION,
        /** The OS will not prompt again: send the user to the app's notification settings. */
        OPEN_APP_SETTINGS,
    }

    data class View(
        val preference: Boolean,
        val permission: Permission,
        val alertsChannel: Boolean,
    ) {
        /** Whether an alert could actually be shown right now. */
        val canDeliver: Boolean
            get() = preference &&
                alertsChannel &&
                (permission == Permission.GRANTED || permission == Permission.NOT_REQUIRED)

        /** The OS would still show a prompt. */
        val canAsk: Boolean get() = permission == Permission.DENIED_CAN_ASK

        /** Only the system settings screen can unblock delivery. */
        val needsAppSettings: Boolean get() = permission == Permission.DENIED_PERMANENT

        /** Permission is fine but the alerts channel was turned off. */
        val needsChannelSettings: Boolean
            get() = !alertsChannel && (permission == Permission.GRANTED || permission == Permission.NOT_REQUIRED)

        val permissionLabel: String
            get() = when (permission) {
                Permission.NOT_REQUIRED -> "not needed (Android 12 or older)"
                Permission.GRANTED -> "allowed"
                Permission.DENIED_CAN_ASK -> "not granted"
                Permission.DENIED_PERMANENT -> "blocked in system settings"
            }

        val toggleAction: ToggleAction
            get() = when (permission) {
                Permission.DENIED_PERMANENT -> ToggleAction.OPEN_APP_SETTINGS
                Permission.DENIED_CAN_ASK -> ToggleAction.REQUEST_PERMISSION
                Permission.GRANTED, Permission.NOT_REQUIRED -> ToggleAction.ENABLE
            }

        /** One honest sentence for the settings row. */
        val summary: String
            get() = when {
                !preference -> "Off for this device."
                permission == Permission.DENIED_PERMANENT ->
                    "Android is blocking notifications for this app, so alerts cannot appear. Allow them in system settings."
                permission == Permission.DENIED_CAN_ASK ->
                    "Android has not granted the notification permission yet."
                !alertsChannel ->
                    "The Agent alerts channel is off, so alerts are silenced. Turn it on in the channel settings."
                else -> "On: alerts arrive while the relay stream is running."
            }
    }

    fun classify(
        sdk: Int,
        granted: Boolean,
        canAskAgain: Boolean,
        alertsChannelOn: Boolean,
        preference: Boolean,
    ): View {
        val permission = when {
            sdk < RUNTIME_PERMISSION_SDK -> Permission.NOT_REQUIRED
            granted -> Permission.GRANTED
            canAskAgain -> Permission.DENIED_CAN_ASK
            else -> Permission.DENIED_PERMANENT
        }
        return View(preference = preference, permission = permission, alertsChannel = alertsChannelOn)
    }
}
