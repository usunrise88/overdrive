package app.overdrive.ride

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import app.overdrive.data.rides.RideStore
import app.overdrive.ride.host.AudioManagerMediaController
import app.overdrive.ride.host.MediaController
import app.overdrive.ride.host.RideChoice
import app.overdrive.ride.host.RideHost
import app.overdrive.ride.host.SimulatedRideSessionFactory
import app.overdrive.ride.overlay.AndroidOverlayController
import app.overdrive.ride.overlay.AppVisibility
import app.overdrive.ride.overlay.ContainerOverlayModel
import app.overdrive.ride.overlay.OverlayController
import app.overdrive.ride.overlay.OverlayModel
import app.overdrive.ride.rides.RideLibrary
import app.overdrive.ride.settings.AppSettings
import app.overdrive.ride.settings.SettingsRepository
import app.overdrive.ride.workouts.WorkoutLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/**
 * What lives as long as the process (ADR 0005): the settings store, the workout library, the stored rides and the one
 * [RideHost]. Built by [OverdriveApp] on first use, so the activity can be destroyed and recreated (rotation, process
 * kept alive behind a video app) without touching the ride, and the overlay service reads the same instances through
 * [overlayModel]. No DI framework: this is the whole object graph.
 *
 * @param ridesDirectory where the rides are kept: the app's private `files/rides`, which nothing outside the app can read
 * except through the FileProvider (ADR 0008). A test passes a temporary folder, which is how a "restart" is simulated.
 * @param settingsName the settings' DataStore file; a second container in one process (a test) needs its own, because
 * DataStore allows one instance per file.
 */
class AppContainer(
    context: Context,
    ridesDirectory: File = File(context.applicationContext.filesDir, "rides"),
    settingsName: String = "settings",
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** One thread for everything that touches the rides folder: the log writes, the stores, the scan (ADR 0008). */
    private val diskScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val settingsRepository =
        SettingsRepository(
            PreferenceDataStoreFactory.create(scope = settingsScope) {
                appContext.preferencesDataStoreFile(settingsName)
            },
        )

    /** The current settings. Defaults until the first read from disk completes, a few milliseconds after start. */
    val settings: StateFlow<AppSettings> =
        settingsRepository.settings.stateIn(
            scope,
            SharingStarted.Eagerly,
            AppSettings(),
        )

    val library: WorkoutLibrary = WorkoutLibrary.fromAssets(appContext.assets)

    /**
     * The rides on disk: records the ride as it happens, stores it when it ends, and on start offers the ones that were
     * cut off (ADR 0008). The scan runs in the background from the moment the container exists.
     */
    val rides: RideLibrary =
        RideLibrary(
            store = RideStore(ridesDirectory),
            scope = diskScope,
            report = { note, cause -> Log.w(TAG, "$note: ${cause.javaClass.simpleName}: ${cause.message}") },
        ).also { it.start() }

    /** The one ride. The first bundled workout is selected at start. */
    val host: RideHost =
        RideHost(
            factory = SimulatedRideSessionFactory(),
            settings = settingsRepository.settings,
            initialChoice = library.workouts.value.firstOrNull()?.let { RideChoice.Planned(it) } ?: RideChoice.Free,
            persistence = rides,
        )

    val media: MediaController = AudioManagerMediaController(appContext)

    /** Whether one of the activities is on screen: the overlay window shows only while the app is not. */
    val visibility: AppVisibility = AppVisibility().also { (appContext as? Application)?.let(it::register) }

    /** The activity's side of the overlay: permissions, the screens that grant them, starting the service. */
    val overlay: OverlayController = AndroidOverlayController(appContext)

    /** What the overlay service reads: this container's ride, settings and visibility (ADR 0006). */
    val overlayModel: OverlayModel = ContainerOverlayModel(this)

    /** Changes the settings from the UI; validation happens in the repository. */
    fun updateSettings(change: (AppSettings) -> AppSettings) {
        scope.launch { settingsRepository.update(change) }
    }

    fun resetOverlayPosition() {
        scope.launch { settingsRepository.resetOverlayPosition() }
    }

    /** Stops the ride and everything else the container started. Only tests call it; the process owns it otherwise. */
    fun close() {
        host.close()
        scope.cancel()
        diskScope.cancel()
        settingsScope.cancel()
    }

    private companion object {
        const val TAG = "OverdriveRides"
    }
}
