package app.overdrive.ride.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.overdrive.ble.BleStack
import app.overdrive.ble.env.AndroidBleEnvironment
import app.overdrive.ride.host.RadioIssue
import app.overdrive.uikit.Notice
import app.overdrive.uikit.icons.OverdriveIcons

/**
 * What the activity can do about Bluetooth (ADR 0010), the counterpart of the overlay's `OverlayController`: re-read the radio
 * and the permissions, and open the two system screens that fix them. An interface so the screens and tests use a fake;
 * [AndroidBluetoothController] is the real one. Neither screen returns a result, so the app learns the outcome on the next
 * resume ([BleStack.refresh]).
 */
interface BluetoothController {
    /** Reads the adapter state and the permissions again. Cheap: called on every resume and after a permission answer. */
    fun refresh()

    /** Opens the system's Bluetooth settings, where the rider switches it on. */
    fun openBluetoothSettings()

    /** Opens this app's settings page, the last resort once the system will no longer ask for a permission. */
    fun openAppSettings()
}

class AndroidBluetoothController(
    context: Context,
    private val stack: BleStack,
) : BluetoothController {
    private val context = context.applicationContext

    override fun refresh() = stack.refresh()

    override fun openBluetoothSettings() = launch(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))

    override fun openAppSettings() =
        launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))

    private fun launch(intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (error: RuntimeException) {
            Log.w(TAG, "No screen for ${intent.action}", error)
        }
    }

    private companion object {
        const val TAG = "BluetoothController"
    }
}

/**
 * The one action that fixes [RadioIssue] and whether the permission can still be asked for: [permissionBlocked] is true
 * once a request was answered "no", and from then on the action opens the app's settings instead (the system shows its
 * question at most twice).
 */
@Immutable
class BluetoothFix(
    val permissionBlocked: Boolean,
    val run: () -> Unit,
) {
    companion object {
        /** Does nothing: previews, screenshots and tests that do not look at it. */
        val None = BluetoothFix(permissionBlocked = false) {}
    }
}

/**
 * The Bluetooth notice of the Ready and Devices screens: **one plain notice with one action**, like the overlay's. It is
 * shown only while [issue] is not null and goes by itself once the rider comes back with it fixed, because the screens
 * read the radio on every resume.
 */
@Composable
fun BluetoothNotice(
    issue: RadioIssue,
    fix: BluetoothFix,
    modifier: Modifier = Modifier,
) {
    when (issue) {
        RadioIssue.PermissionNeeded -> {
            Notice(
                icon = OverdriveIcons.Bluetooth,
                title = "Allow nearby devices",
                text =
                    if (fix.permissionBlocked) {
                        "It is switched off for Overdrive. Turn it on in the app's settings to find and connect your trainer."
                    } else {
                        "Overdrive needs it to find and connect your trainer and heart-rate strap."
                    },
                modifier = modifier,
                actionLabel = if (fix.permissionBlocked) "Open settings" else "Allow",
                onAction = fix.run,
            )
        }

        RadioIssue.BluetoothOff -> {
            Notice(
                icon = OverdriveIcons.BluetoothOff,
                title = "Bluetooth is off",
                text = "Turn it on to connect your trainer and heart-rate strap.",
                modifier = modifier,
                actionLabel = "Turn on",
                onAction = fix.run,
            )
        }

        RadioIssue.Unsupported -> {
            Notice(
                icon = OverdriveIcons.BluetoothOff,
                title = "No Bluetooth on this tablet",
                text = "Use the simulated devices instead: Settings, Developer, Devices.",
                modifier = modifier,
            )
        }
    }
}

/**
 * The action behind [BluetoothNotice]: asks for the Nearby-devices permission (the system question), or opens the Bluetooth
 * settings, whichever [issue] needs, and re-reads the radio on every resume and after each answer. Written like the overlay's
 * onboarding because both fix a state the rider can only change in a system screen.
 */
@Composable
fun rememberBluetoothFix(
    issue: RadioIssue?,
    controller: BluetoothController,
): BluetoothFix {
    var blocked by rememberSaveable { mutableStateOf(false) }
    val request =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { answers ->
            controller.refresh()
            blocked = answers.values.any { !it }
        }
    LifecycleResumeEffect(controller) {
        controller.refresh()
        onPauseOrDispose { }
    }
    // Once it is fixed the next refusal starts from the system question again.
    LaunchedEffect(issue) { if (issue == null) blocked = false }
    return remember(issue, blocked, controller) {
        BluetoothFix(blocked) {
            when (issue) {
                RadioIssue.PermissionNeeded -> {
                    if (blocked) {
                        controller.openAppSettings()
                    } else {
                        request.launch(
                            AndroidBleEnvironment.REQUIRED_PERMISSIONS,
                        )
                    }
                }

                RadioIssue.BluetoothOff -> {
                    controller.openBluetoothSettings()
                }

                RadioIssue.Unsupported, null -> {
                    // Nothing to ask for and nothing to open.
                }
            }
        }
    }
}
