package app.overdrive.ride.host

import app.overdrive.core.device.ConnectionState
import app.overdrive.core.record.InMemoryRideRecorder
import app.overdrive.core.ride.FlatStep
import app.overdrive.core.ride.RideSession
import app.overdrive.core.ride.RideSnapshot
import app.overdrive.core.ride.RideState
import app.overdrive.core.ride.TrainerMode
import app.overdrive.core.ride.flatSteps
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
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
 */
class RideHost(
    private val factory: RideSessionFactory,
    private val settings: Flow<AppSettings>,
    initialChoice: RideChoice = RideChoice.Free,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
    private val debounceMillis: Long = SETTINGS_DEBOUNCE_MS,
) : AutoCloseable {
    private class Run(
        val number: Int,
        val choice: RideChoice,
        val settings: AppSettings,
        val job: Job,
        val handle: RideSessionHandle,
        val recorder: InMemoryRideRecorder,
        val stats: RideStatsAccumulator,
        val profile: ProfileData?,
        val stepsOfWorkout: List<FlatStep>,
    ) {
        var finished: FinishedRide? = null
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

    init {
        scope.launch {
            currentSettings.value = settings.first()
            prepare(mutableChoice.value)
        }
        scope.launch { settings.collect { currentSettings.value = it } }
        scope.launch { observeRideInputs() }
    }

    @OptIn(FlowPreview::class)
    private suspend fun observeRideInputs() {
        settings
            .map { it.rideInputs() }
            .distinctUntilChanged()
            .drop(1)
            .debounce(debounceMillis)
            .collect {
                val run = active
                if (run == null || run.handleState() == RideState.IDLE || run.handleState() == RideState.READY) {
                    prepare(mutableChoice.value)
                }
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
        val recorder = InMemoryRideRecorder()
        val workout = choice.workoutOrNull
        val handle = factory.create(RideRequest(runScope, workout, settings, recorder, number))
        val steps = workout?.flatSteps().orEmpty()
        val run =
            Run(
                number = number,
                choice = choice,
                settings = settings,
                job = job,
                handle = handle,
                recorder = recorder,
                stats = RideStatsAccumulator(settings.ftp),
                profile = if (steps.isEmpty()) null else WorkoutDescriber.profileOf(steps),
                stepsOfWorkout = steps,
            )
        active = run
        runScope.launch { handle.session.snapshots.collect { publish(run, it) } }
        runScope.launch {
            combine(
                handle.trainer.connection,
                handle.heartRate?.connection ?: flowOf(null),
                currentSettings.map { it.simulatedClick }.distinctUntilChanged(),
            ) { trainer, strap, click ->
                DeviceRows.of(handle.trainer.name, trainer, handle.heartRate?.name, strap, click)
            }.collect { mutableDevices.value = it }
        }
    }

    private fun publish(
        run: Run,
        snapshot: RideSnapshot,
    ) {
        if (active !== run) return
        val stats = run.stats.accept(snapshot)
        if (snapshot.state == RideState.FINISHED && run.finished == null) {
            run.recorder.record?.let { record ->
                run.finished = FinishedRide.from(record, run.choice.title, run.stepsOfWorkout)
            }
        }
        mutableSnapshots.value = snapshot
        mutableUi.value =
            RideUi(
                snapshot = snapshot,
                choice = run.choice,
                profile = run.profile,
                averagePower = stats.averagePower,
                lapAveragePower = stats.lapAveragePower,
                trace = stats.trace,
                finished = run.finished,
                rideNumber = run.number,
            )
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
                    ConnectionState.CONNECTED -> DeviceRowUi(DeviceStatus.Connected, "$trainerName · ERG and simulation")
                    ConnectionState.CONNECTING -> DeviceRowUi(DeviceStatus.Searching, "Connecting to $trainerName…")
                    ConnectionState.DISCONNECTED -> DeviceRowUi(DeviceStatus.Lost, "Connection lost · retrying")
                },
            heartRate =
                when {
                    strapName == null || strap == null -> DeviceRowUi(DeviceStatus.NotPaired, "Not paired")
                    strap == ConnectionState.CONNECTED -> DeviceRowUi(DeviceStatus.Connected, strapName)
                    strap == ConnectionState.CONNECTING -> DeviceRowUi(DeviceStatus.Searching, "Searching for heart-rate straps…")
                    else -> DeviceRowUi(DeviceStatus.Lost, "Connection lost · retrying")
                },
            click =
                when (click) {
                    SimulatedClick.Connected -> DeviceRowUi(DeviceStatus.Connected, "Simulated Click")
                    SimulatedClick.Locked -> DeviceRowUi(DeviceStatus.Locked, "Simulated Click · connected")
                    SimulatedClick.NotPaired -> DeviceRowUi(DeviceStatus.NotPaired, "Not paired")
                },
        )
}
