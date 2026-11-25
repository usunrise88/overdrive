@file:OptIn(ExperimentalForeignApi::class)

package app.overdrive.core.apple

import app.overdrive.core.ble.BleEnvironment
import app.overdrive.core.ble.ConnectMode
import app.overdrive.core.ble.DeviceId
import app.overdrive.core.ble.GattTransport
import app.overdrive.core.ble.GattTransportFactory
import app.overdrive.core.ble.adapter.FtmsOptions
import app.overdrive.core.ble.adapter.FtmsTrainerDevice
import app.overdrive.core.ble.adapter.HeartRateOptions
import app.overdrive.core.ble.adapter.HeartRateStrapDevice
import app.overdrive.core.ble.link.ConnectStrategy
import app.overdrive.core.ble.recording.BleSessionRecorder
import app.overdrive.core.ble.recording.LineSink
import app.overdrive.core.ble.recording.RecordingGattTransport
import app.overdrive.core.click.ButtonChange
import app.overdrive.core.click.ClickBits
import app.overdrive.core.click.ClickDevice
import app.overdrive.core.click.ClickOptions
import app.overdrive.core.click.ClickSide
import app.overdrive.core.control.ActionRouter
import app.overdrive.core.control.ButtonLearner
import app.overdrive.core.control.ButtonMapping
import app.overdrive.core.control.ClickAction
import app.overdrive.core.control.CoachButtonLearner
import app.overdrive.core.control.CoachControl
import app.overdrive.core.control.GestureTiming
import app.overdrive.core.control.HandlebarControls
import app.overdrive.core.control.MediaKeys
import app.overdrive.core.device.DeviceDisconnect
import app.overdrive.core.device.HeartRateDevice
import app.overdrive.core.device.SimulatedBike
import app.overdrive.core.device.SimulatedDevice
import app.overdrive.core.device.TrainerDevice
import app.overdrive.core.fit.FitOptions
import app.overdrive.core.fit.FitRoutes
import app.overdrive.core.fit.FitWriter
import app.overdrive.core.gear.GearSettings
import app.overdrive.core.live.CuePlayback
import app.overdrive.core.live.CuePlayer
import app.overdrive.core.live.CueStream
import app.overdrive.core.live.CueStreamSource
import app.overdrive.core.live.LiveCoachClient
import app.overdrive.core.live.LiveCue
import app.overdrive.core.live.LivePlan
import app.overdrive.core.live.LiveSessionController
import app.overdrive.core.physics.PhysicsSettings
import app.overdrive.core.plan.PlanCache
import app.overdrive.core.plan.PlanRepository
import app.overdrive.core.profile.ProfileCache
import app.overdrive.core.profile.ProfileRepository
import app.overdrive.core.record.FileByteSource
import app.overdrive.core.record.RecoveredRide
import app.overdrive.core.record.RideLog
import app.overdrive.core.record.RideRecord
import app.overdrive.core.record.RideRecordJson
import app.overdrive.core.record.RideRecorder
import app.overdrive.core.remote.RemoteResource
import app.overdrive.core.remote.ServiceClient
import app.overdrive.core.remote.TextStore
import app.overdrive.core.remote.recoveryResource
import app.overdrive.core.ride.FlatStep
import app.overdrive.core.ride.RideEngine
import app.overdrive.core.ride.RideSession
import app.overdrive.core.ride.RideSettings
import app.overdrive.core.ride.RideSnapshot
import app.overdrive.core.ride.RideState
import app.overdrive.core.ride.StallGuardSettings
import app.overdrive.core.ride.flatSteps
import app.overdrive.core.route.RideRoutes
import app.overdrive.core.route.Route
import app.overdrive.core.route.RouteLibrary
import app.overdrive.core.sync.HttpGetTransport
import app.overdrive.core.sync.HttpPatchTransport
import app.overdrive.core.sync.HttpPostTransport
import app.overdrive.core.sync.HttpStreamTransport
import app.overdrive.core.sync.HttpSyncProvider
import app.overdrive.core.sync.HttpTransport
import app.overdrive.core.sync.Outbox
import app.overdrive.core.sync.RidePayloads
import app.overdrive.core.sync.SyncConfig
import app.overdrive.core.sync.SyncCoordinator
import app.overdrive.core.wellness.RecoveryResponse
import app.overdrive.core.workout.Workout
import app.overdrive.core.workout.WorkoutImport
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import platform.Foundation.NSData
import platform.Foundation.create
import platform.posix.memcpy
import kotlin.time.Duration.Companion.milliseconds

/** Fast O(1) conversion between Swift `Data` (`NSData`) and Kotlin `ByteArray`. */
public object AppleBytes {
    public fun toByteArray(data: NSData): ByteArray {
        val size = data.length.toInt()
        if (size == 0) return ByteArray(0)
        val out = ByteArray(size)
        out.usePinned { pinned ->
            memcpy(pinned.addressOf(0), data.bytes, data.length)
        }
        return out
    }

    public fun toNSData(bytes: ByteArray): NSData {
        if (bytes.isEmpty()) return NSData()
        return bytes.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
        }
    }
}

/** Handle returned when observing a Kotlin [Flow] from Swift. Call [cancel] to stop collecting. */
public fun interface AppleFlowSubscription {
    public fun cancel()
}

/** Bridges any Kotlin [Flow] to Swift callbacks without requiring compiler plugins (ADR 0044). */
public object AppleFlowWatcher {
    public fun <T> watch(
        scope: CoroutineScope,
        flow: Flow<T>,
        onEach: (T) -> Unit,
        onComplete: () -> Unit,
    ): AppleFlowSubscription {
        val job =
            scope.launch {
                try {
                    flow.collect { value -> onEach(value) }
                } finally {
                    onComplete()
                }
            }
        return AppleFlowSubscription { job.cancel() }
    }
}

/** Creates and tears down structured [CoroutineScope]s for the macOS app container. */
public object AppleScopes {
    public fun background(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    public fun singleThread(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

    public fun io(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    public fun cancel(scope: CoroutineScope) {
        scope.cancel()
    }
}

/** Mutable bridges so Swift implementations can push values into Kotlin [Flow]/[StateFlow]s cleanly. */
public class AppleStateBridge<T>(
    initial: T,
) {
    public val flow: MutableStateFlow<T> = MutableStateFlow(initial)

    public fun set(value: T) {
        flow.value = value
    }

    public fun get(): T = flow.value
}

public class AppleEventBridge<T>(
    bufferCapacity: Int = DEFAULT_BUFFER,
) {
    public val flow: MutableSharedFlow<T> =
        MutableSharedFlow(
            extraBufferCapacity = bufferCapacity,
        )

    public fun emit(value: T): Boolean = flow.tryEmit(value)

    private companion object {
        const val DEFAULT_BUFFER = 128
    }
}

/**
 * Convenience factories with explicit signatures for Swift callers (ObjC/Swift interop does not
 * carry Kotlin default parameter values).
 */
public object AppleCoreFactories {
    public fun createFtmsTrainer(
        scope: CoroutineScope,
        deviceId: String,
        transports: GattTransportFactory,
        environment: BleEnvironment,
        name: String,
        connectStrategy: () -> ConnectStrategy,
    ): FtmsTrainerDevice =
        FtmsTrainerDevice(
            scope = scope,
            device = DeviceId(deviceId),
            transports = transports,
            environment = environment,
            name = name,
            options = FtmsOptions(),
            connectStrategy = connectStrategy,
        )

    public fun createHeartRateStrap(
        scope: CoroutineScope,
        deviceId: String,
        transports: GattTransportFactory,
        environment: BleEnvironment,
        name: String,
        connectStrategy: () -> ConnectStrategy,
    ): HeartRateStrapDevice =
        HeartRateStrapDevice(
            scope = scope,
            device = DeviceId(deviceId),
            transports = transports,
            environment = environment,
            name = name,
            options = HeartRateOptions(),
            connectStrategy = connectStrategy,
        )

    public fun createClickDevice(
        scope: CoroutineScope,
        deviceId: String,
        transports: GattTransportFactory,
        environment: BleEnvironment,
        name: String,
        options: ClickOptions,
        connectStrategy: () -> ConnectStrategy,
    ): ClickDevice =
        ClickDevice(
            scope = scope,
            device = DeviceId(deviceId),
            transports = transports,
            environment = environment,
            name = name,
            options = options,
            connectStrategy = connectStrategy,
        )

    public fun createRecordingTransport(
        delegate: GattTransport,
        scope: CoroutineScope,
        logScope: CoroutineScope,
        sink: LineSink,
        nanoClock: () -> Long,
        model: String?,
        mode: ConnectMode,
    ): GattTransport {
        val recorder = BleSessionRecorder(logScope, sink, nanoClock, model)
        return RecordingGattTransport(delegate, recorder, scope, mode)
    }

    public fun createRideEngine(
        scope: CoroutineScope,
        trainer: TrainerDevice,
        heartRate: HeartRateDevice?,
        workout: Workout?,
        settings: RideSettings,
        recorder: RideRecorder,
        routes: RideRoutes,
        variantRidden: Boolean,
    ): RideEngine =
        RideEngine(
            scope = scope,
            trainer = trainer,
            heartRate = heartRate,
            workout = workout,
            settings = settings,
            recorder = recorder,
            routes = routes,
            variantRidden = variantRidden,
        )

    public fun createSimulatedBike(
        scope: CoroutineScope,
        ftp: Int,
        seed: Long,
        tickMillis: Long,
        scriptedDropouts: Boolean,
    ): SimulatedBike {
        val events =
            if (scriptedDropouts) {
                listOf(
                    DeviceDisconnect(SimulatedDevice.HEART_RATE, startSecond = 25, durationSeconds = 8),
                    DeviceDisconnect(SimulatedDevice.TRAINER, startSecond = 55, durationSeconds = 6),
                )
            } else {
                emptyList()
            }
        return SimulatedBike(
            scope = scope,
            ftp = ftp,
            seed = seed,
            events = events,
            tickInterval = tickMillis.milliseconds,
        )
    }

    public fun createActionRouter(
        session: () -> RideSession?,
        media: MediaKeys,
        coach: CoachControl,
    ): ActionRouter = ActionRouter(session = session, media = media, coach = coach)

    public fun createHandlebarControls(
        scope: CoroutineScope,
        changes: Flow<ButtonChange>,
        mapping: () -> ButtonMapping,
        onAction: (ClickAction) -> Unit,
        holdMillis: Long,
        repeatMillis: Long,
        nowMillis: () -> Long,
    ): HandlebarControls =
        HandlebarControls(
            scope = scope,
            changes = changes,
            mapping = mapping,
            onAction = onAction,
            timing = GestureTiming(holdMillis, repeatMillis),
            nowMillis = nowMillis,
        )

    public fun createSyncProvider(
        config: () -> SyncConfig,
        rides: RidePayloads,
        transport: HttpTransport,
    ): HttpSyncProvider = HttpSyncProvider(config = config, rides = rides, transport = transport)

    public fun createSyncCoordinator(
        scope: CoroutineScope,
        outbox: Outbox,
        provider: HttpSyncProvider,
        lastSuccessMillis: Long?,
    ): SyncCoordinator =
        SyncCoordinator(
            scope = scope,
            outbox = outbox,
            provider = { provider },
            lastSuccessMillis = lastSuccessMillis,
        )

    public fun createPlanRepository(
        config: () -> SyncConfig,
        transport: HttpGetTransport,
        cache: PlanCache,
        today: () -> String,
        wallMillis: () -> Long,
        elapsedMillis: () -> Long,
    ): PlanRepository =
        PlanRepository(
            config = config,
            transport = transport,
            cache = cache,
            today = today,
            wallMillis = wallMillis,
            elapsedMillis = elapsedMillis,
        )

    public fun createProfileRepository(
        config: () -> SyncConfig,
        reader: HttpGetTransport,
        writer: HttpPatchTransport,
        cache: ProfileCache,
        elapsedMillis: () -> Long,
    ): ProfileRepository =
        ProfileRepository(
            config = config,
            reader = reader,
            writer = writer,
            cache = cache,
            elapsedMillis = elapsedMillis,
        )

    public fun createServiceClient(
        config: () -> SyncConfig,
        reader: HttpGetTransport,
        poster: HttpPostTransport?,
        streamer: HttpStreamTransport?,
    ): ServiceClient =
        ServiceClient(
            config = config,
            reader = reader,
            poster = poster,
            streamer = streamer,
        )

    public fun createRouteLibrary(
        client: ServiceClient,
        config: () -> SyncConfig,
        listStore: TextStore,
        detailStore: (String) -> TextStore,
        wallMillis: () -> Long,
        elapsedMillis: () -> Long,
    ): RouteLibrary =
        RouteLibrary(
            client = client,
            config = config,
            listStore = listStore,
            detailStore = detailStore,
            wallMillis = wallMillis,
            elapsedMillis = elapsedMillis,
        )

    public fun createRecoveryResource(
        client: ServiceClient,
        config: () -> SyncConfig,
        store: TextStore,
        wallMillis: () -> Long,
        elapsedMillis: () -> Long,
    ): RemoteResource<RecoveryResponse> =
        recoveryResource(
            client = client,
            config = config,
            store = store,
            wallMillis = wallMillis,
            elapsedMillis = elapsedMillis,
        )

    public fun createLiveCoachClient(client: ServiceClient): LiveCoachClient = LiveCoachClient(client)

    public fun createCuePlayback(
        scope: CoroutineScope,
        player: CuePlayer,
        nowMillis: () -> Long,
        report: (Int, Boolean) -> Unit,
        onPlate: (String?) -> Unit,
    ): CuePlayback =
        CuePlayback(
            scope = scope,
            player = player,
            nowMillis = nowMillis,
            report = report,
            onPlate = onPlate,
        )

    public fun parseWorkout(
        fileName: String,
        text: String,
    ): Workout =
        if (fileName.lowercase().endsWith(".zwo")) {
            WorkoutImport.fromZwo(text)
        } else {
            WorkoutImport.fromJson(text)
        }

    public fun exportFit(
        record: RideRecord,
        routes: FitRoutes,
        utcOffsetSeconds: Int,
    ): NSData {
        val bytes = FitWriter(FitOptions(utcOffsetSeconds = utcOffsetSeconds)).write(record, routes)
        return AppleBytes.toNSData(bytes)
    }

    public fun encodeRideRecord(record: RideRecord): String = RideRecordJson.encode(record)

    public fun decodeRideRecord(json: String): RideRecord = RideRecordJson.decode(json)

    public fun recoverRideLog(path: String): RecoveredRide? = RideLog.recover(FileByteSource(path))

    public fun createRideSettings(
        ftp: Int,
        physics: PhysicsSettings,
        gearCount: Int,
        gradeStepPercent: Double,
        cadenceGuardEnabled: Boolean,
        cadenceFloorRpm: Int,
    ): RideSettings =
        RideSettings(
            ftp = ftp,
            physics = physics,
            gears =
                GearSettings(
                    count = gearCount,
                    neutralGear = (gearCount + 1) / 2,
                    gradePercentPerGear = gradeStepPercent,
                ),
            stallGuard =
                StallGuardSettings(
                    enabled = cadenceGuardEnabled,
                    cadenceFloorRpm = cadenceFloorRpm,
                ),
        )

    public fun createClickOptions(
        silentMeansLocked: Boolean,
        acceptEitherPuck: Boolean,
    ): ClickOptions =
        ClickOptions(
            acceptedSides =
                if (acceptEitherPuck) {
                    setOf(ClickSide.RIGHT, ClickSide.LEFT)
                } else {
                    setOf(ClickSide.RIGHT)
                },
            silentMeansLocked = silentMeansLocked,
        )

    public fun createLiveSessionController(
        plan: LivePlan,
        wallClockMillis: () -> Long,
    ): LiveSessionController = LiveSessionController(plan = plan, wallClockMillis = wallClockMillis)

    public fun createCueStream(
        source: CueStreamSource,
        onCue: (LiveCue) -> Unit,
    ): CueStream = CueStream(source = source, onCue = onCue)

    public fun createButtonLearner(base: ButtonMapping): ButtonLearner = ButtonLearner(base = base)

    public fun createCoachButtonLearner(base: ButtonMapping): CoachButtonLearner = CoachButtonLearner(base = base)

    public fun workoutFlatSteps(workout: Workout): List<FlatStep> = workout.flatSteps()

    public fun workoutDurationSeconds(workout: Workout): Int = workout.flatSteps().sumOf { it.durationSeconds }

    public fun clickBitLabel(mask: Int): String = ClickBits.namesOf(mask).joinToString("+").ifEmpty { "None" }

    public fun defaultRideSnapshot(ftp: Int): RideSnapshot = RideSnapshot(state = RideState.IDLE, ftp = ftp)

    public fun noneFitRoutes(): FitRoutes = FitRoutes.None

    public fun noneRideRoutes(): RideRoutes = RideRoutes.None

    public fun freeRideRoutes(
        route: Route,
        difficultyPercent: Int,
    ): RideRoutes = RideRoutes(difficultyPercent = difficultyPercent, freeRide = route)
}
