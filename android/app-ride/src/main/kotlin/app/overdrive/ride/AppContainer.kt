package app.overdrive.ride

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import app.overdrive.ble.AndroidBle
import app.overdrive.ble.BleStack
import app.overdrive.ble.log.BleLogRole
import app.overdrive.ble.log.BleLogStore
import app.overdrive.ble.log.BleSessionLogger
import app.overdrive.core.ble.GattTransportFactory
import app.overdrive.data.rides.RideStore
import app.overdrive.ride.devices.BleDevicesModel
import app.overdrive.ride.devices.DeviceRole
import app.overdrive.ride.devices.DeviceStore
import app.overdrive.ride.devices.PairedDevices
import app.overdrive.ride.devices.PairingController
import app.overdrive.ride.host.AudioManagerMediaController
import app.overdrive.ride.host.BleRideSessionFactory
import app.overdrive.ride.host.DeviceSourceFactory
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
import app.overdrive.ride.ui.AndroidBluetoothController
import app.overdrive.ride.ui.BluetoothController
import app.overdrive.ride.workouts.WorkoutLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    devicesName: String = if (settingsName == "settings") "devices" else "$settingsName-devices",
    bleLogDirectory: File = File(context.applicationContext.filesDir, "ble-logs"),
    bleStack: (Context, CoroutineScope) -> BleStack = { appContext, scope -> AndroidBle(appContext, scope) },
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

    /**
     * Bluetooth: the radio and the permissions, the scanner and the GATT transports (ADR 0010). Everything above it sees
     * only `core`'s interfaces, so a test gives the container a fake one.
     */
    val ble: BleStack = bleStack(appContext, scope)

    /** What the Bluetooth session files are written to and shared from: `filesDir/ble-logs/` (ADR 0010). */
    val bleLogs: BleLogStore = BleLogStore(bleLogDirectory)

    /** The raw Bluetooth logs are written by one thread of their own, so a slow disk never reaches the radio or the ride. */
    private val bleLogScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private val recordBle: () -> Boolean = { settings.value.recordBleSessions }

    private val trainerTransports: GattTransportFactory =
        BleSessionLogger(
            bleLogs,
            BleLogRole.TRAINER,
            scope,
            bleLogScope,
            ble.nanoClock,
            { pairedDevices.trainer.value?.remembered?.name },
            recordBle,
        ).wrap(ble.transports)

    private val strapTransports: GattTransportFactory =
        BleSessionLogger(
            bleLogs,
            BleLogRole.STRAP,
            scope,
            bleLogScope,
            ble.nanoClock,
            { pairedDevices.strap.value?.remembered?.name },
            recordBle,
        ).wrap(ble.transports)

    /**
     * The paired trainer and strap: **long-lived**, owned by this container and not by any ride, connecting from the
     * moment the app starts and reconnecting whenever Bluetooth allows (ADR 0010). The rides only use them.
     */
    val pairedDevices: PairedDevices =
        PairedDevices(
            scope = scope,
            store =
                DeviceStore(
                    PreferenceDataStoreFactory.create(scope = settingsScope) {
                        appContext.preferencesDataStoreFile(devicesName)
                    },
                ),
            environment = ble.environment,
            transports = { role -> if (role == DeviceRole.Trainer) trainerTransports else strapTransports },
        )

    /** The Devices screen's scan, list and pairing. */
    val pairing: PairingController =
        PairingController(scope, ble.scanner, pairedDevices, canChange = { host.devices.value.canChange })

    /** The Bluetooth notice's two fixes: the permission question and the system's Bluetooth settings. */
    val bluetooth: BluetoothController = AndroidBluetoothController(appContext, ble)

    private val bleRows =
        BleDevicesModel(
            pairedDevices,
            ble.environment,
            settings.map { it.simulatedClick }.distinctUntilChanged(),
        )

    /**
     * The one ride. The first bundled workout is selected at start. The developer setting chooses the real devices (the
     * default) or the simulated ones; a change of what is paired rebuilds a ride that has not started.
     */
    val host: RideHost =
        RideHost(
            factory =
                DeviceSourceFactory(
                    bluetooth = BleRideSessionFactory(pairedDevices, bleRows.ui),
                    simulated = SimulatedRideSessionFactory(),
                ),
            settings = settingsRepository.settings,
            initialChoice = library.workouts.value.firstOrNull()?.let { RideChoice.Planned(it) } ?: RideChoice.Free,
            persistence = rides,
            rideTriggers = pairedDevices.changes,
        )

    init {
        // Connect what is remembered, with no user action. Started **here, after the properties above are assigned**, and
        // not where `pairedDevices` is declared: the first transport opens on another thread within milliseconds and its
        // log header reads `pairedDevices` (the model name), which would still be null if the container were being built.
        pairedDevices.start()
    }

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
        pairing.stop()
        pairedDevices.close()
        ble.close()
        scope.cancel()
        bleLogScope.cancel()
        diskScope.cancel()
        settingsScope.cancel()
    }

    private companion object {
        const val TAG = "OverdriveRides"
    }
}
