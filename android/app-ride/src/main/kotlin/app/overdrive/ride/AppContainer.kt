package app.overdrive.ride

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import app.overdrive.ble.AndroidBle
import app.overdrive.ble.BleStack
import app.overdrive.ble.log.BleLogRole
import app.overdrive.ble.log.BleLogStore
import app.overdrive.ble.log.BleSessionLogger
import app.overdrive.core.ble.GattTransportFactory
import app.overdrive.core.control.ActionRouter
import app.overdrive.core.control.GestureTiming
import app.overdrive.core.sync.NoopSyncProvider
import app.overdrive.core.sync.OutboxEntry
import app.overdrive.core.sync.SyncCoordinator
import app.overdrive.data.rides.RideStore
import app.overdrive.data.sync.FileOutbox
import app.overdrive.ride.devices.BleDevicesModel
import app.overdrive.ride.devices.DeviceRole
import app.overdrive.ride.devices.DeviceStore
import app.overdrive.ride.devices.PairedDevices
import app.overdrive.ride.devices.PairingController
import app.overdrive.ride.handlebar.ButtonLearning
import app.overdrive.ride.handlebar.HandlebarService
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
    outboxFile: File = File(context.applicationContext.filesDir, "sync/outbox.log"),
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
     * The sync outbox (spec §4.9, ADR 0012): finished rides waiting for an upload, on the tablet's disk, surviving the
     * app being killed. Phase 1 has no destination, so the coordinator runs with the no-op provider, which acknowledges
     * nothing, and every ride stays queued for Phase 2. **There is no network code, and nothing here is uploaded.**
     */
    val outbox: FileOutbox = FileOutbox(outboxFile)

    val sync: SyncCoordinator = SyncCoordinator(scope, outbox, { NoopSyncProvider }).also { it.start() }

    /**
     * The rides on disk: records the ride as it happens, stores it when it ends, and on start offers the ones that were
     * cut off (ADR 0008). The scan runs in the background from the moment the container exists. Every stored ride is
     * queued in the outbox, a recovered one and the rides already on disk at start included.
     */
    val rides: RideLibrary =
        RideLibrary(
            store = RideStore(ridesDirectory),
            scope = diskScope,
            report = { note, cause -> Log.w(TAG, "$note: ${cause.javaClass.simpleName}: ${cause.message}") },
            onSaved = { stored ->
                sync.rideFinished(
                    OutboxEntry(
                        rideId = stored.id,
                        enqueuedAtMillis = System.currentTimeMillis(),
                        startedAtMillis = stored.startEpochMillis,
                        durationSeconds = stored.durationSeconds,
                        title = stored.title,
                    ),
                )
            },
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

    private val clickTransports: GattTransportFactory =
        BleSessionLogger(
            bleLogs,
            BleLogRole.CLICK,
            scope,
            bleLogScope,
            ble.nanoClock,
            { pairedDevices.click.value?.remembered?.name },
            recordBle,
        ).wrap(ble.transports)

    /**
     * The paired trainer, strap and Click: **long-lived**, owned by this container and not by any ride, connecting from
     * the moment the app starts and reconnecting whenever Bluetooth allows (ADR 0010, ADR 0012). The rides only use them.
     * A change of the Click's developer options replaces the Click's device (a short reconnect).
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
            transports = { role ->
                when (role) {
                    DeviceRole.Trainer -> trainerTransports
                    DeviceRole.Strap -> strapTransports
                    DeviceRole.Click -> clickTransports
                }
            },
            clickOptions = settingsRepository.settings.map { it.click }.distinctUntilChanged().map { it.toOptions() },
        )

    /** The Devices screen's scan, list and pairing. */
    val pairing: PairingController =
        PairingController(
            scope,
            ble.scanner,
            pairedDevices,
            canChange = { host.devices.value.canChange },
            clickOptions = { settings.value.click.toOptions() },
        )

    /** The Bluetooth notice's two fixes: the permission question and the system's Bluetooth settings. */
    val bluetooth: BluetoothController = AndroidBluetoothController(appContext, ble)

    private val bleRows = BleDevicesModel(pairedDevices, ble.environment)

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

    /** The media keys of the ride bar and of the Click: system volume and a media key for whichever app plays. */
    val media: MediaController = AudioManagerMediaController(appContext)

    /**
     * **The one routing rule** (spec §4.7, ADR 0012): core's [ActionRouter] over the host's current session and the media.
     * The Click's gesture layer calls it, and so do the ride screen's buttons (`RideActions` delegates to it), so the two
     * cannot diverge.
     */
    val router: ActionRouter = ActionRouter({ host.currentSession() }, media)

    /**
     * The *Learn buttons* flow: asks for the five roles in turn, from the Click's presses, and saves the mapping. Offered
     * only with real devices and no live ride; the handlebar says nothing while it runs.
     */
    val learning: ButtonLearning =
        ButtonLearning(
            scope = scope,
            buttons = pairedDevices.clickButtons,
            current = { settings.value.buttonMapping },
            save = { mapping -> settingsRepository.update { it.copy(buttonMapping = mapping) } },
            canLearn = { host.devices.value.let { it.canChange && !it.simulated } },
        )

    /**
     * The handlebar: the Click's presses, through the mapping and the gesture layer, into [router]. It runs **in this
     * container's scope, with no activity**, kept alive behind a video app by the overlay's foreground service, so a press
     * with the app in the background reaches the ride exactly as one with it on screen does (ADR 0012).
     */
    val handlebar: HandlebarService =
        HandlebarService(
            scope = scope,
            buttons = pairedDevices.clickButtons,
            mapping = { settings.value.buttonMapping },
            timing =
                settingsRepository.settings
                    .map { it.click.holdThresholdMillis to it.click.volumeRepeatMillis }
                    .distinctUntilChanged()
                    .map { (hold, repeat) -> GestureTiming(hold.toLong(), repeat.toLong()) },
            router = router,
            nowMillis = SystemClock::elapsedRealtime,
            suspended = { learning.active },
        )

    init {
        // Connect what is remembered, with no user action. Started **here, after the properties above are assigned**, and
        // not where `pairedDevices` is declared: the first transport opens on another thread within milliseconds and its
        // log header reads `pairedDevices` (the model name), which would still be null if the container were being built.
        pairedDevices.start()
        handlebar.start()
        // A ride that goes live while the buttons are being learned ends the lesson: the handlebar is the ride's again.
        scope.launch {
            host.devices
                .map { it.canChange }
                .distinctUntilChanged()
                .collect { if (!it) learning.cancel() }
        }
    }

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

    /** The Devices screen's **Retry now** for the Click (it also starts over a Click that is connected but silent). */
    fun retryClick() {
        pairedDevices.controls(DeviceRole.Click)?.retryNow()
    }

    /** Stops the ride and everything else the container started. Only tests call it; the process owns it otherwise. */
    fun close() {
        handlebar.close()
        learning.cancel()
        sync.close()
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
