package app.overdrive.ride.overlay

import android.Manifest
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import app.overdrive.core.ride.RideState
import app.overdrive.ride.host.RideMapping
import app.overdrive.ride.settings.OverlayStartMode
import app.overdrive.uikit.tokens.OverdriveDimens
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.function.Consumer
import kotlin.math.roundToInt

/**
 * The ride's foreground service and the owner of the overlay window (spec §4.6, ADR 0006).
 *
 * **Started** by the visible ride screen ([OverlayController.ensureRunning]) once a ride is live, so Android's limits on
 * starting foreground services from the background never apply. **Shows** the window while a ride is live and the app is
 * not on screen; hides it when the rider comes back to the app. **Stops itself** when the ride is dismissed after its
 * summary, when the summary is on screen, or when no ride ever started ([ServiceStopRule]).
 *
 * It reads the ride from the application's [OverlayModel] (the same instances the activity uses), never builds a second
 * one. State is mapped and rate limited off the main thread ([overlayStates]); the main thread only runs the window
 * calls and the Compose frame.
 */
class OverlayService :
    Service(),
    OverlayWindowCallbacks {
    private lateinit var model: OverlayModel
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val viewOwner = OverlayViewOwner()
    private val stopRule = ServiceStopRule()
    private var windowContext: Context? = null
    private var windowManager: WindowManager? = null
    private var controller: OverlayWindowController? = null
    private var blurListener: Consumer<Boolean>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        OverlayNotification.ensureChannel(this)
        enterForeground(OverlayNotification.build(this, finished = false))
        val provider = application as? OverlayModelProvider
        if (provider == null) {
            stopSelf()
            return
        }
        model = provider.overlayModel()
        viewOwner.start()
        val context = overlayWindowContext()
        windowContext = context
        val manager = context.getSystemService(WindowManager::class.java)
        windowManager = manager
        val density = context.resources.displayMetrics.density
        controller =
            OverlayWindowController(
                windowManager = manager,
                metrics =
                    OverlayWindowMetrics(
                        density = density,
                        initialSize =
                            PxSize(
                                (OverdriveDimens.overlayCompactWidth.value * density).roundToInt(),
                                (OverdriveDimens.overlayCompactHeight.value * density).roundToInt(),
                            ),
                    ),
                callbacks = this,
                viewOwner = viewOwner,
                animationsEnabled = ::systemAnimationsOn,
            )
        watchRide()
        watchBlur(manager, density)
    }

    /**
     * Goes to the foreground with the types [ForegroundServiceTypes] chooses: `specialUse` always, `connectedDevice` while
     * "Nearby devices" is allowed. If the system refuses the pair all the same (the permission was revoked between the
     * check and the call), it starts again with `specialUse` alone, so the overlay never depends on Bluetooth.
     */
    private fun enterForeground(notification: Notification) {
        val sdk = Build.VERSION.SDK_INT
        val granted = checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        val types = ForegroundServiceTypes.choose(sdk, granted)
        try {
            startForegroundOf(notification, types)
        } catch (error: SecurityException) {
            val fallback = ForegroundServiceTypes.fallback(sdk, types) ?: throw error
            Log.w(TAG, "Foreground service type refused (${error.javaClass.simpleName}); starting with specialUse only")
            startForegroundOf(notification, fallback)
        }
    }

    private fun startForegroundOf(
        notification: Notification,
        types: Int,
    ) {
        if (types == ForegroundServiceTypes.NONE) {
            startForeground(OverlayNotification.ID, notification)
        } else {
            startForeground(OverlayNotification.ID, notification, types)
        }
    }

    // The ride lives in this process, so there is nothing to restart into if the system kills it.
    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int = START_NOT_STICKY

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        controller?.onScreenChanged()
    }

    override fun onDestroy() {
        main.cancel()
        blurListener?.let { windowManager?.removeCrossWindowBlurEnabledListener(it) }
        // The window goes first, then the lifecycle ends (docs/android/overlay-notes.md §3.2).
        controller?.hide()
        viewOwner.destroy()
        super.onDestroy()
    }

    // ---- The ride ---------------------------------------------------------------------------------

    private fun watchRide() {
        val states = model.rides.map { it.snapshot.state }.distinctUntilChanged().flowOn(Dispatchers.Default)
        val stateAndVisibility = combine(states, model.appVisible) { state, visible -> state to visible }
        main.launch {
            OverlayPolicy
                .windowShown(stateAndVisibility.map { (state, visible) -> OverlayPolicy.windowWanted(state, visible) })
                .collect { shown -> if (shown) showWindow() else controller?.hide() }
        }
        main.launch {
            var previous: RideState? = null
            stateAndVisibility.collect { (state, visible) ->
                if (state == RideState.FINISHED && previous?.let(OverlayPolicy::isLive) == true) {
                    // The summary belongs on screen (design/system.md §5.4); the notification is the fallback if the
                    // system refuses the launch.
                    OverlayNotification.update(this@OverlayService, finished = true)
                    if (!visible) openApp()
                }
                previous = state
                if (stopRule.onUpdate(state, visible)) stopSelf()
            }
        }
        main.launch {
            delay(OverlayPolicy.START_GRACE_MS)
            if (stopRule.onGraceElapsed()) stopSelf()
        }
    }

    private fun showWindow() {
        val controller = controller ?: return
        val context = windowContext ?: return
        if (controller.isShown) return
        val ride = model.rides.value
        val settings = model.settings.value
        val initial = RideMapping.overlayState(ride.snapshot, ride.profile)
        val states = overlayStates(model.rides)
        val look = model.settings.map { it.toOverlaySettings() }.distinctUntilChanged()
        val content =
            ComposeView(context).apply {
                setContent { OverlayWindowContent(states, initial, look, settings.toOverlaySettings()) }
            }
        val shown = controller.show(content, settings.overlayX, settings.overlayY, blurRadiusPx())
        if (!shown) {
            Log.w(TAG, "The system refused the overlay window; the ride goes on without it")
            stopSelf()
        }
    }

    // ---- Blur -------------------------------------------------------------------------------------

    /** The blur behind the window: the radius when the rider wants it and the system allows it, else 0. */
    private fun blurRadiusPx(): Int {
        val manager = windowManager ?: return 0
        val density = windowContext?.resources?.displayMetrics?.density ?: return 0
        return if (model.settings.value.overlayBlur && manager.isCrossWindowBlurEnabled) {
            OverlayWindowSpec.blurRadiusPx(density)
        } else {
            0
        }
    }

    private fun watchBlur(
        manager: WindowManager,
        density: Float,
    ) {
        // The setting changes while the window is shown (never from the window itself), and the system can turn
        // cross-window blur off and on (battery saver): either way the window follows.
        main.launch {
            model.settings
                .map { it.overlayBlur }
                .distinctUntilChanged()
                .collect { controller?.setBlur(blurRadiusPx()) }
        }
        val listener = Consumer<Boolean> { controller?.setBlur(blurRadiusPx()) }
        blurListener = listener
        manager.addCrossWindowBlurEnabledListener(mainExecutor, listener)
    }

    // ---- Window callbacks -------------------------------------------------------------------------

    override fun onTap() {
        model.updateSettings {
            it.copy(
                overlayMode =
                    if (it.overlayMode ==
                        OverlayStartMode.Compact
                    ) {
                        OverlayStartMode.Detailed
                    } else {
                        OverlayStartMode.Compact
                    },
            )
        }
    }

    override fun onLongPress() = openApp()

    override fun onPositionChanged(
        x: Float,
        y: Float,
    ) {
        model.updateSettings { it.copy(overlayX = x, overlayY = y) }
    }

    // ---- Helpers ----------------------------------------------------------------------------------

    /**
     * Brings the app to the front. A visible overlay window and the "Display over other apps" permission are both
     * listed exemptions from the background-activity-start limits, so this should work from the overlay's own touch; it
     * has not been tried on the tablet (ADR 0006), and the notification's tap does the same through a `PendingIntent`.
     */
    private fun openApp() {
        try {
            startActivity(openAppIntent(this))
        } catch (error: RuntimeException) {
            Log.w(TAG, "The app could not be brought to the front", error)
        }
    }

    private fun overlayWindowContext(): Context =
        try {
            val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } catch (error: RuntimeException) {
            Log.w(TAG, "No window context, using the service context", error)
            this
        }

    private fun systemAnimationsOn(): Boolean =
        Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f

    private companion object {
        const val TAG = "OverlayService"
    }
}
