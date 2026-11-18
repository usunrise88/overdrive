package app.overdrive.ride.ui

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import app.overdrive.ride.overlay.OverlayAccess
import app.overdrive.ride.overlay.OverlayController
import app.overdrive.uikit.Notice
import app.overdrive.uikit.icons.OverdriveIcons

/** What the overlay row's **Allow** does first. */
enum class AllowStep {
    /** Android 13 and later, notifications not yet allowed: show the system question, then the overlay screen. */
    AskNotifications,

    /** Go straight to the system screen where "Display over other apps" is switched on. */
    OpenOverlaySettings,
}

/**
 * The step behind **Allow**: the notification question comes first on Android 13 and later, because the service's
 * notification is what keeps the ride visible in the shade. The answer does not matter to the overlay; whatever the rider
 * says, the overlay screen opens next ([sdkInt] is the device's API level).
 */
fun allowStep(
    access: OverlayAccess,
    sdkInt: Int = Build.VERSION.SDK_INT,
): AllowStep =
    if (!access.notificationsAllowed && sdkInt >= Build.VERSION_CODES.TIRAMISU) {
        AllowStep.AskNotifications
    } else {
        AllowStep.OpenOverlaySettings
    }

/**
 * The Ready screen's row that asks for "Display over other apps" (ADR 0006): one title, one sentence on why, one
 * button that opens the system screen. It is shown only while the permission is missing and disappears on its own once
 * the rider comes back with it granted. Declining costs nothing: the ride works as before and the top bar's overlay
 * button still previews the overlay inside the app.
 */
@Composable
fun OverlayPermissionNotice(
    onAllow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Notice(
        icon = OverdriveIcons.PictureInPicture2,
        title = "Show numbers over other apps",
        text = "Allow “Display over other apps” so the overlay stays on screen while you watch video.",
        modifier = modifier,
        actionLabel = "Allow",
        onAction = onAllow,
    )
}

/**
 * What the overlay may do right now, read again every time the screen resumes: the permission screens return no result,
 * so coming back from one is the only moment the app learns that the rider allowed something.
 */
@Composable
fun rememberOverlayAccess(overlay: OverlayController): OverlayAccess {
    var access by remember(overlay) { mutableStateOf(overlay.access()) }
    LifecycleResumeEffect(overlay) {
        access = overlay.access()
        onPauseOrDispose { }
    }
    return access
}

/**
 * Starts the overlay service from this visible screen whenever a ride is [live], and again each time the screen is
 * started during one (the service may have been stopped by the system meanwhile). Starting from a visible screen is
 * what keeps Android's limits on starting foreground services from the background out of the way. Idempotent, and it
 * does nothing without the permission (see [OverlayController.ensureRunning]).
 */
@Composable
fun EnsureOverlayService(
    live: Boolean,
    overlay: OverlayController,
) {
    LifecycleStartEffect(live, overlay) {
        if (live) overlay.ensureRunning()
        onStopOrDispose { }
    }
}
