package app.overdrive.companion.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.health.connect.client.HealthConnectClient
import java.net.URI
import java.net.URISyntaxException

/** The host of the service address, for the words of a failure ("The phone could not find overdrive.example.net"). */
fun hostOf(url: String): String =
    try {
        URI(url).host.orEmpty()
    } catch (e: URISyntaxException) {
        ""
    }

/** Opens the phone's own settings page for this app (the system blocks its network access). */
fun openAppSettings(context: Context) {
    start(context, appDetails(context))
}

private fun appDetails(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** Starts [intent]; a phone without the page has nothing to open, and the words next to the button still say what to do. */
internal fun start(
    context: Context,
    intent: Intent,
): Boolean =
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

/** Health Connect's own screen for this app's permissions (**Manage**). */
fun openHealthConnect(context: Context) {
    start(context, HealthConnectClient.getHealthConnectManageDataIntent(context))
}

/**
 * The phone's battery saving for this app (design/companion.md §4.10, step 3): the system's one-tap request to leave the app out of
 * it, else the list of apps, else the app's own page. Xiaomi's autostart screen has no public intent, so it is tried by name and the
 * app page is the fallback; the screen says the path in words ("Apps · Autostart · Overdrive") either way.
 */
fun openBatterySettings(context: Context) {
    val request =
        Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.fromParts("package", context.packageName, null),
        )
    if (start(context, request)) return
    if (start(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) return
    openAppSettings(context)
}

/** Xiaomi's autostart list (MIUI and HyperOS), which only the owner's phone can say works; the app's page otherwise. */
fun openAutostart(context: Context) {
    val miui =
        Intent().setComponent(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        )
    if (!start(context, miui)) openAppSettings(context)
}

/** Whether the phone leaves this app out of its battery saving, so the daily read runs on time. */
fun batteryUnrestricted(context: Context): Boolean {
    val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
    return power.isIgnoringBatteryOptimizations(context.packageName)
}

/** Puts [text] on the clipboard, for the opening question of a topic (nothing else is ever copied). */
fun copyToClipboard(
    context: Context,
    label: String,
    text: String,
) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}
