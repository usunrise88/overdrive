package app.overdrive.ride.host

import app.overdrive.core.device.ConnectionState
import app.overdrive.core.record.CompositeRideRecorder
import app.overdrive.core.record.InMemoryRideRecorder
import app.overdrive.core.record.RideRecord
import app.overdrive.core.ride.FlatStep
import app.overdrive.core.ride.RideSession
import app.overdrive.core.ride.RideSnapshot
import app.overdrive.core.ride.RideState
import app.overdrive.core.ride.TrainerMode
import app.overdrive.core.ride.flatSteps
import app.overdrive.ride.rides.RideDiskLog
import app.overdrive.ride.rides.RidePersistence
import app.overdrive.ride.rides.SaveState
import app.overdrive.ride.settings.AppSettings
import app.overdrive.ride.settings.SimulatedClick
import app.overdrive.ride.workouts.WorkoutDescriber
import app.overdrive.uikit.DeviceStatus
import app.overdrive.uikit.chart.ProfileData
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The one ride session of the app, owned by the `Application` so it outlives the activity (rotation, the activity
 * being destroyed behind a video app) and can be shared with the overlay service of M1-B (ADR 0005).
 *
 * **Threading.** All the ride's work, the engine, the simulated devices and this class's bookkeeping, runs on one
 * background thread ([dispatcher], never the main one, spec §7.3); the simulated devices are single-threaded by
 * design. The UI reads [ui], [snapshots] and [devices] (state flows, safe from any thread) and calls the commands,
 * which are fire and forget.
 *
 * **Lifecycle.** There is always a ride in the making: a session is prepared at start-up for [choice] and again
 * whenever a different workout is chosen, the ride-affecting settings change before the start, or the rider is done
 * with a finished ride. A session in `READY` that is replaced is simply cancelled. A ride that is under way is never
 * replaced; it finishes first.
 *
 * **Persistence (ADR 0008).** Every ride gets a [RideDiskLog] from [persistence]: the engine's recorder is the
 * in-memory record plus a queue in front of the crash-safe log, so the engine never waits for the disk. When the ride
 * finishes the host hands the record over to be stored (record JSON and FIT) and shows the progress in
 * [FinishedRide.save]; if the log fails mid-ride [RideUi.logFailed] turns true and the ride goes on.
 */
class RideHost(
    private val factory: RideSessionFactory,
    private val settings: Flow<AppSettings>,
    initialChoice: RideChoice = RideChoice.Free,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    private val debounceMillis: Long = SETTINGS_DEBOUNCE_MS,
    private val persistence: RidePersistence = RidePersistence.None,
    /**
     * Anything besides the settings that a session is built from, such as which devices are paired (ADR 0010): the latest
     * value is remembered with each prepared session, and a session that is ready or idle is rebuilt, after the same
     * debounce as a setting, when it differs. A ride that has started is never replaced.
     */
    private val rideTriggers: Flow<Any> = flowOf(Unit),
) : AutoCloseable {
    /** What a session was built from: the ride-affecting settings and the latest trigger. */
    private data class Inputs(
        val settings: List<Any>,
        val trigger: Any?,
    )

    private class Run(
        val inputs: Inputs,
        val number: Int,
        val choice: RideChoice,
        val settings: AppSettings,
        val job: Job,
        val scope: CoroutineScope,
        val handle: RideSessionHandle,
        /** The ride in memory: what the finished summary and the stored files are made from. */
        val recorder: InMemoryRideRecorder,
        val disk: RideDiskLog,
        val stats: RideStatsAccumulator,
        val profile: ProfileData?,
        val stepsOfWorkout: List<FlatStep>,
    ) {
        var finished: FinishedRide? = null

        /** The finished ride's record, kept for a retry of the save. */
        var record: RideRecord? = null

        /** The last snapshot and statistics published, so the UI can be republished when only the save state changed. */
        var lastSnapshot: RideSnapshot? = null
        var lastStats: RideStats = RideStats()
    }

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var counter = 0

    @Volatile
    private var active: Run? = null

    private val currentSettings = MutableStateFlow(AppSettings())
    private val mutableChoice = MutableStateFlow(initialChoice)
    private val mutableSnapshots = MutableStateFlow(RideSnapshot(RideState.IDLE, ftp = currentSettings.value.ftp))
    private val mutableUi =
        MutableStateFlow(
            RideUi(mutableSnapshots.value, initialChoice, null, null, null, emptyList(), null, rideNumber = 0),
        )
    private val mutableDevices = MutableStateFlow(DevicesUi.Unknown)

    /** What the rider chose to ride. Change it with [select]. */
    val choice: StateFlow<RideChoice> = mutableChoice.asStateFlow()

    /** The current ride's snapshots: the one flow the ride screens and the overlay read. `IDLE` until the first ride is prepared. */
    val snapshots: StateFlow<RideSnapshot> = mutableSnapshots.asStateFlow()

    /** The snapshot plus averages, power trace and the finished summary, for the ride screens. */
    val ui: StateFlow<RideUi> = mutableUi.asStateFlow()

    /** Trainer, strap and Click, for the Devices screen and the top bar. */
    val devices: StateFlow<DevicesUi> = mutableDevices.asStateFlow()

    /** The action dispatcher behind every ride button; the Click's gesture layer (M4) uses the same one. */
    val actions: RideActions = RideActions({ active?.handle?.session }, { mutableSnapshots.value })

    /** The latest of [rideTriggers]; null until the first one arrives. */
    private val trigger = MutableStateFlow<Any?>(null)

    init {
        // The trigger is collected first, so on the host's single thread it is known before the first session is built.
        scope.launch { rideTriggers.collect { trigger.value = it } }
        scope.launch {
            currentSettings.value = settings.first()
            prepare(mutableChoice.value)
        }
        scope.launch { settings.collect { currentSettings.value = it } }
        scope.launch { observeRideInputs() }
    }

    /**
     * Rebuilds the session when what it is built from has changed (a ride-affecting setting, a paired device) and the
     * ride has not started. The comparison is with what the session was **built from**, not with the previous value, so a
     * change that arrived while the first session was being built is never missed and one that was undone costs nothing.
     */
    @OptIn(FlowPreview::class)
    private suspend fun observeRideInputs() {
        combine(settings.map { it.rideInputs() }.distinctUntilChanged(), trigger) { rideInputs, trigger ->
            Inputs(rideInputs, trigger)
        }.debounce(debounceMillis)
            .collect { wanted ->
                val run = active
                val rebuildable =
                    run == null || run.handleState() == RideState.IDLE || run.handleState() == RideState.READY
                if (rebuildable && run?.inputs != wanted) prepare(mutableChoice.value)
            }
    }

    private fun Run.handleState(): RideState = handle.session.snapshots.value.state

    /** Chooses what to ride next and prepares it. Ignored while a ride is under way. */
    fun select(choice: RideChoice) {
        scope.launch {
            if (active?.isLive() == true) return@launch
            mutableChoice.value = choice
            prepare(choice)
        }
    }

    /** Throws a finished ride away and prepares the next one with the same choice (the **Done** button). */
    fun reset() {
        scope.launch {
            if (active?.isLive() == true) return@launch
            prepare(mutableChoice.value)
        }
    }

    private fun Run.isLive(): Boolean = handleState().let { it == RideState.RIDING || it == RideState.PAUSED }

    /** Retries the trainer's link now (the Devices screen's **Retry now**). */
    fun retryTrainer() {
        active?.handle?.controls?.retryTrainer()
    }

    /** Retries the strap's link now. */
    fun retryHeartRate() {
        active?.handle?.controls?.retryHeartRate()
    }

    /** Tries to store the finished ride again after a failed save (the finished screen's **Retry**). */
    fun retrySave() {
        scope.launch {
            val run = active ?: return@launch
            val failed = run.finished?.save as? SaveState.Failed ?: return@launch
            if (failed.retryable) {
                setSave(run, SaveState.Saving)
                store(run)
            }
        }
    }

    /** Stops the ride's coroutines for good; the host cannot be used afterwards. */
    override fun close() {
        scope.cancel()
    }

    private fun prepare(choice: RideChoice) {
        active?.job?.cancel()
        val settings = currentSettings.value
        val number = ++counter
        val job = SupervisorJob(scope.coroutineContext[Job])
        val runScope = CoroutineScope(scope.coroutineContext + job)
        val live = InMemoryRideRecorder()
        val disk = persistence.begin(choice.title)
        val workout = choice.workoutOrNull
        val handle =
            factory.create(RideRequest(runScope, workout, settings, CompositeRideRecorder(disk.recorder, live), number))
        val steps = workout?.flatSteps().orEmpty()
        val run =
            Run(
                inputs = Inputs(settings.rideInputs(), trigger.value),
                number = number,
                choice = choice,
                settings = settings,
                job = job,
                scope = runScope,
                handle = handle,
                recorder = live,
                disk = disk,
                stats = RideStatsAccumulator(settings.ftp),
                profile = if (steps.isEmpty()) null else WorkoutDescriber.profileOf(steps),
                stepsOfWorkout = steps,
            )
        active = run
        runScope.launch { handle.session.snapshots.collect { publish(run, it) } }
        runScope.launch { disk.failed.collect { republish(run) } }
        // The rows come from the device layer when it has its own (Bluetooth: link states, names, radio), else they are
        // derived from the simulated devices' three-state links.
        val rows: Flow<DevicesUi> =
            handle.devices
                ?: combine(
                    handle.trainer.connection,
                    handle.heartRate?.connection ?: flowOf(null),
                    currentSettings.map { it.simulatedClick }.distinctUntilChanged(),
                ) { trainer, strap, click ->
                    DeviceRows
                        .of(
                            handle.trainer.name,
                            trainer,
                            handle.heartRate?.name,
                            strap,
                            click,
                        ).copy(simulated = true)
                }
        // A ride that is live never changes its devices: pairing and forgetting are not offered meanwhile.
        val rideIsLive = handle.session.snapshots.map { it.state == RideState.RIDING || it.state == RideState.PAUSED }
        runScope.launch {
            combine(rows, rideIsLive.distinctUntilChanged()) { ui, rideLive ->
                ui.copy(
                    canChange =
                        ui.canChange && !rideLive,
                )
            }.collect { mutableDevices.value = it }
        }
    }

    private fun publish(
        run: Run,
        snapshot: RideSnapshot,
    ) {
        if (active !== run) return
        val stats = run.stats.accept(snapshot)
        run.lastSnapshot = snapshot
        run.lastStats = stats
        var justFinished = false
        if (snapshot.state == RideState.FINISHED && run.finished == null) {
            run.recorder.record?.let { record ->
                run.record = record
                run.finished = FinishedRide.from(record, run.choice.title, run.stepsOfWorkout)
                justFinished = true
            }
        }
        mutableSnapshots.value = snapshot
        mutableUi.value = uiOf(run, snapshot, stats)
        if (justFinished) store(run)
    }

    /** Publishes the current ride again without a new snapshot: the save state or the log's health changed. */
    private fun republish(run: Run) {
        val snapshot = run.lastSnapshot
        if (active !== run || snapshot == null) return
        mutableUi.value = uiOf(run, snapshot, run.lastStats)
    }

    private fun uiOf(
        run: Run,
        snapshot: RideSnapshot,
        stats: RideStats,
    ) = RideUi(
        snapshot = snapshot,
        choice = run.choice,
        profile = run.profile,
        averagePower = stats.averagePower,
        lapAveragePower = stats.lapAveragePower,
        trace = stats.trace,
        finished = run.finished,
        rideNumber = run.number,
        logFailed = run.disk.failed.value,
    )

    /**
     * Starts storing the finished ride (the screen shows [SaveState.Saving] already). The disk work runs where
     * [RideDiskLog] puts it, not on this thread; leaving the summary does not stop it, it only stops this ride's screen
     * from updating.
     */
    private fun store(run: Run) {
        val record = run.record ?: return
        run.scope.launch { setSave(run, run.disk.finish(record)) }
    }

    private fun setSave(
        run: Run,
        state: SaveState,
    ) {
        run.finished = run.finished?.copy(save = state)
        republish(run)
    }

    companion object {
        /** Settings steppers repeat every 150 ms; wait for the rider to stop before replacing a ready session. */
        const val SETTINGS_DEBOUNCE_MS: Long = 400
    }
}

/**
 * Every ride button as one function (design/system.md §12: "one action dispatcher"). The ride screen's buttons and,
 * from M4, the Click's gesture layer call these; the routing rule of spec §4.7 lives here once: in an ERG step the
 * gear buttons change the intensity.
 */
class RideActions(
    private val session: () -> RideSession?,
    private val snapshot: () -> RideSnapshot,
) {
    fun start() = session()?.start()

    fun pause() = session()?.pause()

    fun resume() = session()?.resume()

    /** The Click's long press on button 3: pause while riding, resume while paused. */
    fun togglePause() {
        when (snapshot().state) {
            RideState.RIDING -> pause()
            RideState.PAUSED -> resume()
            else -> Unit
        }
    }

    fun finish() = session()?.finish()

    fun nextInterval() = session()?.nextInterval()

    fun previousInterval() = session()?.previousInterval()

    /** One gear harder, or in an ERG step the intensity 5 points up. */
    fun levelUp() {
        if (snapshot().mode == TrainerMode.ERG) session()?.intensityUp() else session()?.gearUp()
    }

    /** One gear easier, or in an ERG step the intensity 5 points down. */
    fun levelDown() {
        if (snapshot().mode == TrainerMode.ERG) session()?.intensityDown() else session()?.gearDown()
    }
}

/** Maps device link states to the rows of the Devices screen (design/system.md §7.7). */
object DeviceRows {
    fun of(
        trainerName: String,
        trainer: ConnectionState,
        strapName: String?,
        strap: ConnectionState?,
        click: SimulatedClick,
    ): DevicesUi =
        DevicesUi(
            trainer =
                when (trainer) {
                    ConnectionState.CONNECTED -> {
                        DeviceRowUi(
                            DeviceStatus.Connected,
                            "$trainerName · ERG and simulation",
                        )
                    }

                    ConnectionState.CONNECTING -> {
                        DeviceRowUi(DeviceStatus.Searching, "Connecting to $trainerName…")
                    }

                    ConnectionState.DISCONNECTED -> {
                        DeviceRowUi(DeviceStatus.Lost, "Connection lost · retrying")
                    }
                },
            heartRate =
                when {
                    strapName == null || strap == null -> {
                        DeviceRowUi(DeviceStatus.NotPaired, "Not paired")
                    }

                    strap == ConnectionState.CONNECTED -> {
                        DeviceRowUi(DeviceStatus.Connected, strapName)
                    }

                    strap == ConnectionState.CONNECTING -> {
                        DeviceRowUi(
                            DeviceStatus.Searching,
                            "Searching for heart-rate straps…",
                        )
                    }

                    else -> {
                        DeviceRowUi(DeviceStatus.Lost, "Connection lost · retrying")
                    }
                },
            click = clickRow(click),
        )

    /** The Click row: simulated until M4 brings the real one (developer setting), in Bluetooth mode as well. */
    fun clickRow(click: SimulatedClick): DeviceRowUi =
        when (click) {
            SimulatedClick.Connected -> DeviceRowUi(DeviceStatus.Connected, "Simulated Click")
            SimulatedClick.Locked -> DeviceRowUi(DeviceStatus.Locked, "Simulated Click · connected")
            SimulatedClick.NotPaired -> DeviceRowUi(DeviceStatus.NotPaired, "Not paired")
        }
}
