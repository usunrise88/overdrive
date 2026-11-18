package app.overdrive.companion.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
    val intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: android.content.ActivityNotFoundException) {
        // A phone without that page has nothing to open; the line above the button still says what to do.
    }
}
