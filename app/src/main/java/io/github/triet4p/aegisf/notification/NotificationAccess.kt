package io.github.triet4p.aegisf.notification

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService

/**
 * What the app knows about the system's notification-listener binding.
 *
 * [Disconnected] is the default: before the user grants access, after revocation, and
 * whenever the system has not bound the listener. [Connected] carries the moment the
 * system bound it; it is runtime state, never ledger data.
 */
sealed interface ListenerConnection {
    data object Disconnected : ListenerConnection

    data class Connected(val connectedAtEpochMillis: Long) : ListenerConnection
}

@Volatile
internal var accessGrantedForTest: Boolean? = null

@Volatile
internal var rebinderForTest: ((ComponentName) -> Unit)? = null

/**
 * Whether the user has granted this app notification access in the system settings.
 *
 * On API 27+ this asks the platform directly through
 * `NotificationManager.isNotificationListenerAccessGranted`, which is the supported
 * answer — it never asks the listener itself, so it reports revocation even when the
 * service process is gone. Below API 27 the platform method does not exist, so this
 * falls back to reading the system's own list of enabled listener components.
 */
fun isNotificationAccessGranted(context: Context): Boolean {
    accessGrantedForTest?.let { return it }
    val component = ComponentName(context, BankNotificationListener::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
        val manager = context.getSystemService(NotificationManager::class.java)
        return manager?.isNotificationListenerAccessGranted(component) == true
    }
    return isAccessGrantedLegacy(context, component)
}

/**
 * Pre-API-27 fallback: look for this app's listener in the system's enabled-listener
 * list. Only reachable on API 24–26 (the app's `minSdk` is 24).
 */
private fun isAccessGrantedLegacy(context: Context, component: ComponentName): Boolean {
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        ENABLED_NOTIFICATION_LISTENERS,
    ) ?: return false
    return enabled.split(':').any { entry ->
        entry == component.flattenToString() || entry == component.flattenToShortString()
    }
}

/**
 * Whether a notification from [packageName] may be processed.
 *
 * Both conditions must hold: the user granted system-wide notification access, and the
 * package is in the user's allowlist. Revoking access stops everything regardless of the
 * allowlist, and an empty allowlist stops everything regardless of access. Matching is
 * exact — a package that merely looks similar is not permitted.
 *
 * This is the decision rule the listener applies; later intake tasks reuse it rather than
 * re-deciding.
 */
fun isIntakePermitted(
    accessGranted: Boolean,
    allowedPackages: Set<String>,
    packageName: String,
): Boolean {
    if (!accessGranted) {
        return false
    }
    return allowedPackages.contains(packageName)
}

/**
 * The honest outcome of asking the system to (re)bind the bank-notification listener.
 *
 * [Requested] means the supported rebind call was made; the system reconnects
 * asynchronously, and the settings status shows the truth when [BankNotificationListener]
 * reports back — this call cannot confirm the rebind itself. [RefusedNoAccess] means no
 * call was made because the user has not granted notification access.
 */
sealed interface ListenerRebindOutcome {
    data object Requested : ListenerRebindOutcome

    data object RefusedNoAccess : ListenerRebindOutcome
}

/**
 * Asks the system to bind the bank-notification listener through the supported rebind.
 *
 * This is one explicit user-initiated request, made only when the user already granted
 * notification access — without the grant the system would ignore it, so nothing is
 * called and [ListenerRebindOutcome.RefusedNoAccess] is returned instead. There is no
 * retry, no loop, and no settings bypass: when the system declines or stays
 * disconnected, the listener status keeps saying so and the user returns to the system
 * settings screen. The rebind call itself exists since API 24, which the app's `minSdk`
 * already guarantees.
 */
fun requestListenerRebind(
    context: Context,
    accessGranted: Boolean,
    rebinder: (ComponentName) -> Unit = { component ->
        if (rebinderForTest != null) {
            rebinderForTest?.invoke(component)
        } else {
            try {
                NotificationListenerService.requestRebind(component)
            } catch (e: Exception) {
                android.util.Log.w("NotificationAccess", "requestRebind error: ${e.message}")
            }
            try {
                val pm = context.packageManager
                pm.setComponentEnabledSetting(
                    component,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
                pm.setComponentEnabledSetting(
                    component,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP,
                )
            } catch (e: Exception) {
                android.util.Log.w("NotificationAccess", "setComponentEnabledSetting error: ${e.message}")
            }
        }
    },
): ListenerRebindOutcome {
    if (!accessGranted) {
        return ListenerRebindOutcome.RefusedNoAccess
    }
    android.util.Log.i("NotificationAccess", "requestListenerRebind: requesting listener rebind")
    rebinder(ComponentName(context, BankNotificationListener::class.java))
    return ListenerRebindOutcome.Requested
}

/** The supported system screen where notification access is granted or revoked. */
fun notificationAccessSettingsIntent(): Intent =
    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

/**
 * The supported system app-info screen for this app.
 *
 * When a phone blocks the access switch for a sideloaded install (restricted settings),
 * the user resolves it here through the system's own UI — never through a bypass.
 */
fun appInfoSettingsIntent(context: Context): Intent =
    Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", context.packageName, null),
    )

private const val ENABLED_NOTIFICATION_LISTENERS = "enabled_notification_listeners"
