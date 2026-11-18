package app.overdrive.ride.overlay

import android.app.Activity
import android.app.Application
import android.os.Bundle
import app.overdrive.ride.AppContainer
import app.overdrive.ride.host.RideUi
import app.overdrive.ride.settings.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Everything the overlay service reads from the app, as an interface so a test can stand in for the ride (ADR 0006).
 * [ContainerOverlayModel] is the real one, over the same [AppContainer] instances the activity uses: the service never
 * builds a second ride.
 */
interface OverlayModel {
    /** The current ride: snapshot, workout profile and the rest. The service maps and filters it itself. */
    val rides: StateFlow<RideUi>

    /** The rider's settings: overlay mode, opacity and blur. */
    val settings: StateFlow<AppSettings>

    /** Whether one of the app's activities is on screen. */
    val appVisible: StateFlow<Boolean>

    /** Changes the settings (mode and position); validation happens in the repository. */
    fun updateSettings(change: (AppSettings) -> AppSettings)
}

/** The application hands the service its [OverlayModel]; a test application hands it a fake. */
interface OverlayModelProvider {
    fun overlayModel(): OverlayModel
}

class ContainerOverlayModel(
    private val container: AppContainer,
) : OverlayModel {
    override val rides: StateFlow<RideUi> get() = container.host.ui
    override val settings: StateFlow<AppSettings> get() = container.settings
    override val appVisible: StateFlow<Boolean> get() = container.visibility.visible

    override fun updateSettings(change: (AppSettings) -> AppSettings) = container.updateSettings(change)
}

/**
 * Whether the app is on screen: true while at least one of its activities is started. The overlay window hides while
 * the rider is in Overdrive and shows once they leave for another app. Registered on the application, so it also
 * reports while no activity exists. Touched on the main thread only (activity callbacks).
 */
class AppVisibility {
    private var started = 0
    private val state = MutableStateFlow(false)

    val visible: StateFlow<Boolean> = state.asStateFlow()

    fun activityStarted() {
        started++
        state.value = true
    }

    fun activityStopped() {
        started = (started - 1).coerceAtLeast(0)
        state.value = started > 0
    }

    /** Follows the activities of [application]. */
    fun register(application: Application) {
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) = activityStarted()

                override fun onActivityStopped(activity: Activity) = activityStopped()

                override fun onActivityCreated(
                    activity: Activity,
                    savedInstanceState: Bundle?,
                ) = Unit

                override fun onActivityResumed(activity: Activity) = Unit

                override fun onActivityPaused(activity: Activity) = Unit

                override fun onActivitySaveInstanceState(
                    activity: Activity,
                    outState: Bundle,
                ) = Unit

                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
    }
}
