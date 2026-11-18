package app.overdrive.ride.overlay

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.compose.runtime.Immutable

/**
 * What the app can currently do about the overlay (ADR 0006), re-read on every resume because the permission screens
 * return no result.
 */
@Immutable
data class OverlayAccess(
    /** "Display over other apps": without it there is no overlay window and the ride works as before. */
    val canDrawOverlays: Boolean,
    /** The notification of the foreground service can be shown. Not needed for the overlay itself. */
    val notificationsAllowed: Boolean,
    /** The system allows blurring what is behind a window right now (Android 12 and later, not in battery saver). */
    val blurAvailable: Boolean,
) {
    companion object {
        /** Nothing to ask for: what screens and tests use when they do not care. */
        val Granted = OverlayAccess(canDrawOverlays = true, notificationsAllowed = true, blurAvailable = true)
    }
}

/**
 * The activity's view of the overlay service: what is granted, how to ask for it, and the call that makes sure the
 * service runs. An interface so the screens and tests use a fake; [AndroidOverlayController] is the real one.
 */
interface OverlayController {
    /** The current permissions. Cheap: call it on every resume. */
    fun access(): OverlayAccess

    /** Opens the system screen where the rider allows "Display over other apps" (no result comes back). */
    fun openOverlaySettings()

    /** Opens the app's notification settings. */
    fun openNotificationSettings()

    /**
     * Makes sure the overlay service runs, for a ride that is live. Called from the visible ride screen (so Android's
     * background-start limits for foreground services do not apply). Does nothing without the overlay permission and
     * does nothing if the service already runs.
     */
    fun ensureRunning()
}

class AndroidOverlayController(
    context: Context,
) : OverlayController {
    private val context = context.applicationContext

    override fun access(): OverlayAccess =
        OverlayAccess(
            canDrawOverlays = Settings.canDrawOverlays(context),
            notificationsAllowed =
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED,
            blurAvailable = context.getSystemService(WindowManager::class.java)?.isCrossWindowBlurEnabled == true,
        )

    override fun openOverlaySettings() {
        // Since Android 11 the system ignores the package URI and shows the whole list; it is passed anyway, for
        // devices that still honour it.
        launch(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
        )
    }

    override fun openNotificationSettings() {
        launch(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        )
    }

    override fun ensureRunning() {
        if (!Settings.canDrawOverlays(context)) return
        try {
            context.startForegroundService(Intent(context, OverlayService::class.java))
        } catch (error: RuntimeException) {
            // A refused start (for example the app is not visible after all) costs the overlay, never the ride.
            Log.w(TAG, "The overlay service could not be started", error)
        }
    }

    private fun launch(intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (error: RuntimeException) {
            Log.w(TAG, "No screen for ${intent.action}", error)
        }
    }

    private companion object {
        const val TAG = "OverlayController"
    }
}

/** The one intent that brings the ride app to the front, shared by the long press and the notification. */
internal fun openAppIntent(context: Context): Intent =
    Intent(context, app.overdrive.ride.MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
