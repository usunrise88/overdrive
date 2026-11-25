import AppKit
import Foundation
import OverdriveCore

public enum AppScreen: Equatable {
    case ride
    case devices
    case learnButtons
    case settings
}

public enum SelectedRideChoice: Equatable {
    case free
    case local(id: String, workout: Workout)
    case planned(workout: PlannedWorkout, useVariant: Bool)

    public var title: String {
        switch self {
        case .free: return "Free ride"
        case .local(_, let w): return w.name
        case .planned(let pw, _): return pw.name
        }
    }

    public var effectiveWorkout: Workout? {
        switch self {
        case .free:
            return nil
        case .local(_, let w):
            return w
        case .planned(let pw, let useVariant):
            return AppleCoreFactories.shared.effectivePlannedWorkout(planned: pw, useVariant: useVariant)
        }
    }

    public var structureHash: String? {
        switch self {
        case .planned(let pw, let useVariant):
            return useVariant ? pw.variant?.structureHash : pw.structureHash
        default:
            return nil
        }
    }

    public var isVariant: Bool {
        if case .planned(_, let useVariant) = self { return useVariant }
        return false
    }
}

public struct DiscoveredDevice: Identifiable, Equatable {
    public let id: String
    public let role: MacDeviceRole
    public let name: String
    public let rssi: Int
    public let lastSeenUptime: TimeInterval
}

public struct DeviceRowStatus: Equatable {
    public let role: MacDeviceRole
    public let pairedName: String?
    public let detail: String
    public let connected: Bool
    public let connecting: Bool
    public let needsAttention: Bool
    public let canRetry: Bool
    public let batteryPercent: Int?
}

public struct FinishedRideSummary: Equatable {
    public let title: String
    public let startEpochMillis: Int64
    public let endEpochMillis: Int64?
    public let summary: RideSummary
    public let laps: [(number: Int, name: String, durationSeconds: Int, avgPower: Int?, avgHr: Int?)]
    public let zoneSeconds: [Int]
    public var saveState: SaveStatus

    public static func == (lhs: FinishedRideSummary, rhs: FinishedRideSummary) -> Bool {
        lhs.title == rhs.title && lhs.startEpochMillis == rhs.startEpochMillis && lhs.saveState == rhs.saveState
    }

    public enum SaveStatus: Equatable {
        case saving
        case saved(StoredRideMeta)
        case failed(String)
    }
}

/// Process-lifetime object graph for the macOS ride app (mirrors `android/app-ride`'s `AppContainer.kt`, ADR 0044).
@MainActor
public final class AppContainer: ObservableObject {
    // Navigation & visibility
    @Published public var screen: AppScreen = .ride
    @Published public var mainWindowVisible: Bool = true

    // Settings & Devices
    @Published public var settings: MacAppSettings
    @Published public var rememberedDevices: RememberedDevicesRecord
    @Published public var syncToken: String
    @Published public var syncUrlWarning: String?
    @Published public var syncUrlError: String?
    @Published public var bleAvailability: BleAvailability = .bluetoothOff
    @Published public var isScanning: Bool = false
    @Published public var scanError: String?
    @Published public var discoveredDevices: [DiscoveredDevice] = []
    @Published public var trainerStatusText: String = "Not paired"
    @Published public var strapStatusText: String = "Not paired"
    @Published public var clickStatusText: String = "Not paired"
    @Published public var trainerConnected: Bool = false
    @Published public var strapConnected: Bool = false
    @Published public var clickConnected: Bool = false
    @Published public var clickLockedMessage: String?
    @Published public var accessibilityTrusted: Bool = MacMediaController.isAccessibilityTrusted

    // Workouts, Plan, Profile, Routes, Recovery
    @Published public var localWorkouts: [(id: String, workout: Workout)] = []
    @Published public var workoutImportError: String?
    @Published public var choice: SelectedRideChoice = .free
    @Published public var selectedFreeRouteId: String? = nil
    @Published public var planState: PlanState = AppleCoreFactories.shared.defaultPlanState()
    @Published public var profileState: ProfileState = AppleCoreFactories.shared.defaultProfileState()
    @Published public var routesList: [RouteSummary] = []
    @Published public var downloadedRoutes: [String: Route] = [:]
    @Published public var recoveryState: RecoveryResponse? = nil

    // Live ride state
    @Published public var snapshot: RideSnapshot = AppleCoreFactories.shared.defaultRideSnapshot(ftp: 200)
    @Published public var averagePower: Int? = nil
    @Published public var lapAveragePower: Int? = nil
    @Published public var trace: [ProfileTracePoint] = []
    @Published public var logFailed: Bool = false
    @Published public var finishedSummary: FinishedRideSummary? = nil
    @Published public var previousAttemptLine: String? = nil

    // Disk rides & Sync
    @Published public var storedRides: [StoredRideMeta] = []
    @Published public var interruptedRides: [InterruptedRideCandidate] = []
    @Published public var lastSaveMessage: String? = nil
    @Published public var syncPendingCount: Int = 0
    @Published public var syncStatusLine: String? = nil
    @Published public var lastUploadMillis: Int64? = nil
    @Published public var bleLogsSummary: (count: Int, totalBytes: Int64) = (0, 0)
    @Published public var bleLogsShareMessage: String? = nil

    // Button learning
    @Published public var learningStepIndex: Int = 0
    @Published public var learningError: String? = nil
    @Published public var learningDone: Bool = false
    @Published public var learningMapPreview: [HandlebarButton: Int32] = [:]
    @Published public var learningCoachMute: Bool = false

    // Subsystems
    public let dataDirectory: URL
    public let settingsStore: SettingsStore
    public let keychain: KeychainStore
    public let rideStore: MacRideStore
    public let bleStack: MacBleStack
    public let bleLogs: BleLogStore
    public let mediaController: MacMediaController
    public let cuePlayer: MacCuePlayer
    public let overlayController: OverlayWindowController
    public let sleepAssertion: SleepAssertion
    public let menuBar: MenuBarController
    public let liveCoach: LiveCoachDriver

    private let coreScope = AppleScopes.shared.background()
    private let rideScope = AppleScopes.shared.singleThread()
    private let logScope = AppleScopes.shared.io()
    private let httpTransport = URLSessionTransport()
    private let outbox: MacFileOutbox
    private let lastUploadStore: LastUploadStore
    private let syncProvider: HttpSyncProvider
    private let syncCoordinator: SyncCoordinator
    private let planRepo: PlanRepository
    private let profileRepo: ProfileRepository
    private let serviceClient: ServiceClient
    private let routeLibrary: RouteLibrary
    private let recoveryResource: RemoteResource<RecoveryResponse>

    private var trainerDevice: FtmsTrainerDevice?
    private var strapDevice: HeartRateStrapDevice?
    private var clickDevice: ClickDevice?
    private var handlebarControls: HandlebarControls?
    private var actionRouter: ActionRouter!
    private var buttonLearner: ButtonLearner?
    private var coachButtonLearner: CoachButtonLearner?
    private var clickButtonSubscription: AppleFlowSubscription?

    private var activeSession: RideSession?
    private var activeSimulatedBike: SimulatedBike?
    private var activeInMemoryRecorder: InMemoryRideRecorder?
    private var activeDiskRecorder: AsyncRideRecorder?
    private var activeRideNumber: Int = 0
    private var activeRideId: String? = nil
    private var snapshotSub: AppleFlowSubscription?
    private var scanSub: AppleFlowSubscription?
    private var scanSweeper: Timer?
    private var subscriptions: [AppleFlowSubscription] = []
    private var syncSettleTask: Task<Void, Never>?

    private var lastOverlayEmitMs: Int64 = 0
    private var lastOverlayState: OverlayUiState?
    private var powerSum: Int = 0
    private var powerCount: Int = 0
    private var lapIndexForAvg: Int = -1
    private var lapPowerSum: Int = 0
    private var lapPowerCount: Int = 0
    private var autoExpandedStep: Bool = false

    public init(directory: URL? = nil) {
        let base = directory ?? FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Overdrive", isDirectory: true)
        self.dataDirectory = base
        self.settingsStore = SettingsStore(directory: base)
        self.keychain = KeychainStore()
        self.rideStore = MacRideStore(directory: base.appendingPathComponent("rides", isDirectory: true))
        self.bleStack = MacBleStack()
        self.bleLogs = BleLogStore(directory: base.appendingPathComponent("ble-logs", isDirectory: true))
        self.mediaController = MacMediaController()
        self.overlayController = OverlayWindowController()
        self.sleepAssertion = SleepAssertion()
        self.menuBar = MenuBarController()

        let loadedSettings = settingsStore.loadSettings()
        let loadedDevices = settingsStore.loadDevices()
        let loadedToken = keychain.readToken()
        self.settings = loadedSettings
        self.rememberedDevices = loadedDevices
        self.syncToken = loadedToken

        var volumeRef: Double = loadedSettings.coachVolume
        self.cuePlayer = MacCuePlayer(volume: { Float(volumeRef) })

        self.outbox = MacFileOutbox(fileURL: base.appendingPathComponent("sync/outbox.json"))
        self.lastUploadStore = LastUploadStore(fileURL: base.appendingPathComponent("sync/last-upload"))
        self.lastUploadMillis = lastUploadStore.read()

        var configHolder: () -> SyncConfig = { SyncConfig(baseUrl: "", token: "") }
        let transport = AppleCoreFactories.shared.wrapHttpClient(client: self.httpTransport)
        let payloads = AppleCoreFactories.shared.wrapRidePayloads(delegate: self.rideStore)

        self.syncProvider = AppleCoreFactories.shared.createSyncProvider(
            config: { configHolder() },
            rides: payloads,
            transport: transport
        )
        self.syncCoordinator = AppleCoreFactories.shared.createSyncCoordinator(
            scope: coreScope,
            outbox: self.outbox,
            provider: self.syncProvider,
            lastSuccessMillis: self.lastUploadMillis.map { KotlinLong(longLong: $0) }
        )
        self.planRepo = AppleCoreFactories.shared.createPlanRepository(
            config: { configHolder() },
            transport: transport,
            cache: MacPlanCache(fileURL: base.appendingPathComponent("plan/workouts")),
            today: { Self.localToday() },
            wallMillis: { KotlinLong(longLong: Self.wallMillis()) },
            elapsedMillis: { KotlinLong(longLong: Self.monotonicMillis()) }
        )
        self.profileRepo = AppleCoreFactories.shared.createProfileRepository(
            config: { configHolder() },
            reader: transport,
            writer: transport,
            cache: MacProfileCache(fileURL: base.appendingPathComponent("profile/profile")),
            elapsedMillis: { KotlinLong(longLong: Self.monotonicMillis()) }
        )
        self.serviceClient = AppleCoreFactories.shared.createServiceClient(
            config: { configHolder() },
            reader: transport,
            poster: transport,
            streamer: transport
        )
        let routeDir = MacTextStoreDirectory(directory: base.appendingPathComponent("routes/points", isDirectory: true), maxFiles: 30)
        self.routeLibrary = AppleCoreFactories.shared.createRouteLibrary(
            client: serviceClient,
            config: { configHolder() },
            listStore: MacTextStore(fileURL: base.appendingPathComponent("routes/list")),
            detailStore: { id in routeDir.store(forKey: id) },
            wallMillis: { KotlinLong(longLong: Self.wallMillis()) },
            elapsedMillis: { KotlinLong(longLong: Self.monotonicMillis()) }
        )
        self.recoveryResource = AppleCoreFactories.shared.createRecoveryResource(
            client: serviceClient,
            config: { configHolder() },
            store: MacTextStore(fileURL: base.appendingPathComponent("cache/recovery")),
            wallMillis: { KotlinLong(longLong: Self.wallMillis()) },
            elapsedMillis: { KotlinLong(longLong: Self.monotonicMillis()) }
        )

        let liveClient = AppleCoreFactories.shared.createLiveCoachClient(client: serviceClient)
        var liveEnabledRef: () -> Bool = { true }
        self.liveCoach = LiveCoachDriver(
            client: liveClient,
            player: self.cuePlayer,
            enabled: { liveEnabledRef() }
        )

        configHolder = { [weak self] in
            guard let self else { return SyncConfig(baseUrl: "", token: "") }
            return SyncConfig(baseUrl: self.settings.syncUrl, token: self.syncToken)
        }
        liveEnabledRef = { [weak self] in self?.settings.liveCoachEnabled ?? true }
        _ = volumeRef

        self.actionRouter = AppleCoreFactories.shared.createActionRouter(
            session: { [weak self] in self?.activeSession },
            media: self.mediaController,
            coach: CoachControl { [weak self] in
                Task { @MainActor in self?.liveCoach.mute() }
            }
        )

        configureCallbacks()
        loadBundledWorkouts()
        bootstrap()
    }

    // MARK: - Effective Rider Values (Profile overrides local settings, ADR 0021)

    public var effectiveFtp: Int {
        if let profileFtp = profileState.profile?.ftp?.intValue {
            return profileFtp
        }
        return settings.ftp
    }

    public var effectivePhysics: PhysicsSettings {
        if let p = profileState.profile?.physics {
            return PhysicsSettings(
                systemMassKg: p.systemMassKg,
                cdA: p.cdA,
                crr: p.crr,
                airDensityKgPerM3: p.airDensity,
                drivetrainEfficiency: p.drivetrainEfficiency
            )
        }
        return PhysicsSettings(
            systemMassKg: settings.systemMassKg,
            cdA: settings.cdA,
            crr: settings.crr,
            airDensityKgPerM3: PhysicsSettings.Companion.shared.DEFAULT_AIR_DENSITY,
            drivetrainEfficiency: PhysicsSettings.Companion.shared.DEFAULT_DRIVETRAIN_EFFICIENCY
        )
    }

    public var isRideLive: Bool {
        snapshot.state == .riding || snapshot.state == .paused
    }

    public var canChangeDevices: Bool { !isRideLive }

    public var canStartRide: Bool {
        snapshot.state == .ready
    }

    // MARK: - Lifecycle & Bootstrap

    private func configureCallbacks() {
        overlayController.restorePosition(x: settings.overlayX, y: settings.overlayY)
        overlayController.updateLook(OverlayLook(mode: settings.overlayMode, opacity: settings.overlayOpacity, blurEnabled: settings.overlayBlur))

        overlayController.onToggleMode = { [weak self] in
            guard let self else { return }
            if self.autoExpandedStep {
                self.autoExpandedStep = false
                self.overlayController.updateLook(OverlayLook(mode: self.settings.overlayMode, opacity: self.settings.overlayOpacity, blurEnabled: self.settings.overlayBlur))
                return
            }
            let next: OverlayMode = self.settings.overlayMode == .compact ? .detailed : .compact
            self.updateSettings { $0.overlayMode = next }
        }
        overlayController.onOpenMainApp = {
            NSApp.activate(ignoringOtherApps: true)
            NSApp.windows.first(where: { !($0 is OverlayPanel) })?.makeKeyAndOrderFront(nil)
        }
        overlayController.onMuteCoach = { [weak self] in
            self?.liveCoach.mute()
        }
        overlayController.onPositionSaved = { [weak self] fx, fy in
            self?.updateSettings {
                $0.overlayX = fx
                $0.overlayY = fy
            }
        }

        menuBar.onShowApp = {
            NSApp.activate(ignoringOtherApps: true)
            NSApp.windows.first(where: { !($0 is OverlayPanel) })?.makeKeyAndOrderFront(nil)
        }
        menuBar.onToggleOverlay = { [weak self] in
            self?.updateSettings { $0.overlayAlwaysShow.toggle() }
        }
        menuBar.onTogglePause = { [weak self] in
            self?.dispatchAction(.toggleRidePause)
        }
        menuBar.onMuteCoach = { [weak self] in
            self?.liveCoach.mute()
        }
        menuBar.onQuit = {
            NSApp.terminate(nil)
        }
    }

    private func bootstrap() {
        // Watch BLE environment
        subscriptions.append(
            AppleFlowWatcher.shared.watch(
                scope: coreScope,
                flow: bleStack.environment.availability,
                onEach: { [weak self] avail in
                    guard let availability = avail as? BleAvailability else { return }
                    Task { @MainActor in self?.bleAvailability = availability }
                },
                onComplete: {}
            )
        )

        // Watch sync status and report
        syncCoordinator.start()
        subscriptions.append(
            AppleFlowWatcher.shared.watch(
                scope: coreScope,
                flow: syncCoordinator.report,
                onEach: { [weak self] value in
                    guard let report = value as? SyncReport else { return }
                    Task { @MainActor in
                        guard let self else { return }
                        if let last = report.lastSuccessMillis?.int64Value, last != self.lastUploadMillis {
                            self.lastUploadMillis = last
                            self.lastUploadStore.write(last)
                        }
                    }
                },
                onComplete: {}
            )
        )
        subscriptions.append(
            AppleFlowWatcher.shared.watch(
                scope: coreScope,
                flow: syncCoordinator.status,
                onEach: { [weak self] value in
                    guard let status = value as? SyncStatus else { return }
                    Task { @MainActor in self?.updateSyncStatusText(status) }
                },
                onComplete: {}
            )
        )

        // Watch plan, profile, routes, recovery
        subscriptions.append(
            AppleFlowWatcher.shared.watch(
                scope: coreScope,
                flow: planRepo.state,
                onEach: { [weak self] val in
                    guard let state = val as? PlanState else { return }
                    Task { @MainActor in self?.planState = state }
                },
                onComplete: {}
            )
        )
        subscriptions.append(
            AppleFlowWatcher.shared.watch(
                scope: coreScope,
                flow: profileRepo.state,
                onEach: { [weak self] val in
                    guard let state = val as? ProfileState else { return }
                    Task { @MainActor in
                        self?.profileState = state
                        if self?.isRideLive == false { self?.prepareSession() }
                    }
                },
                onComplete: {}
            )
        )
        subscriptions.append(
            AppleFlowWatcher.shared.watch(
                scope: coreScope,
                flow: routeLibrary.list.state,
                onEach: { [weak self] val in
                    guard let state = val as? ResourceState<AnyObject> else { return }
                    let routes = (state.value as? RoutesResponse)?.routes ?? []
                    Task { @MainActor in
                        self?.routesList = routes
                    }
                },
                onComplete: {}
            )
        )
        subscriptions.append(
            AppleFlowWatcher.shared.watch(
                scope: coreScope,
                flow: routeLibrary.details,
                onEach: { [weak self] val in
                    guard let state = val as? RouteDetailsState else { return }
                    Task { @MainActor in
                        self?.downloadedRoutes = state.downloaded
                        if self?.isRideLive == false { self?.prepareSession() }
                    }
                },
                onComplete: {}
            )
        )
        subscriptions.append(
            AppleFlowWatcher.shared.watch(
                scope: coreScope,
                flow: recoveryResource.state,
                onEach: { [weak self] val in
                    guard let state = val as? ResourceState<AnyObject> else { return }
                    let recovery = state.value as? RecoveryResponse
                    Task { @MainActor in self?.recoveryState = recovery }
                },
                onComplete: {}
            )
        )

        // Load disk rides & announce to outbox
        let scanned = rideStore.scanAtStartup()
        storedRides = scanned.stored
        interruptedRides = scanned.interrupted
        bleLogsSummary = bleLogs.summary()
        for stored in storedRides.reversed() {
            syncCoordinator.rideFinished(
                entry: OutboxEntry(
                    rideId: stored.id,
                    enqueuedAtMillis: Self.wallMillis(),
                    startedAtMillis: stored.startEpochMillis,
                    durationSeconds: stored.durationSeconds,
                    title: stored.title
                )
            )
        }

        // Load caches & start initial remote refreshes
        Task {
            try? await planRepo.load()
            try? await profileRepo.load()
            try? await routeLibrary.load()
            try? await recoveryResource.load()
            refreshRemote(reason: .start)
        }

        // Start remembered BLE devices immediately at launch (ADR 0010, ADR 0040)
        reconnectRememberedDevices()
        prepareSession()
    }

    // MARK: - Workouts

    private func loadBundledWorkouts() {
        var loaded: [(id: String, workout: Workout)] = []
        if let dir = Bundle.main.resourceURL?.appendingPathComponent("Workouts"),
           let urls = try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil) {
            for url in urls.sorted(by: { $0.lastPathComponent < $1.lastPathComponent }) {
                if let text = try? String(contentsOf: url, encoding: .utf8),
                   let workout = try? AppleCoreFactories.shared.parseWorkout(fileName: url.lastPathComponent, text: text) {
                    loaded.append((url.lastPathComponent, workout))
                }
            }
        }
        localWorkouts = loaded
        if let first = loaded.first {
            choice = .local(id: first.id, workout: first.workout)
        }
    }

    public func importWorkoutFile(url: URL) {
        workoutImportError = nil
        do {
            let text = try String(contentsOf: url, encoding: .utf8)
            let workout = try AppleCoreFactories.shared.parseWorkout(fileName: url.lastPathComponent, text: text)
            let key = "file-\(url.lastPathComponent)-\(UUID().uuidString.prefix(6))"
            localWorkouts.append((key, workout))
            selectChoice(.local(id: key, workout: workout))
        } catch {
            workoutImportError = "Could not open \(url.lastPathComponent): \(error.localizedDescription)"
        }
    }

    public func selectChoice(_ next: SelectedRideChoice) {
        guard !isRideLive else { return }
        choice = next
        prepareSession()
    }

    public func selectFreeRoute(_ routeId: String?) {
        guard !isRideLive else { return }
        selectedFreeRouteId = routeId
        if let routeId {
            Task { try? await routeLibrary.ensure(id: routeId, why: .requested) }
        }
        prepareSession()
    }

    // MARK: - Ride Lifecycle

    public func prepareSession() {
        guard !isRideLive else { return }
        teardownSession()

        activeRideNumber += 1
        let rideNumber = activeRideNumber
        let rideId = "ride-\(Self.wallMillis())"
        activeRideId = rideId
        finishedSummary = nil
        logFailed = false
        previousAttemptLine = nil
        powerSum = 0
        powerCount = 0
        lapIndexForAvg = -1
        lapPowerSum = 0
        lapPowerCount = 0
        trace = []
        autoExpandedStep = false

        let rawWorkout = choice.effectiveWorkout
        let engineWorkout = rawWorkout.map { AppleCoreFactories.shared.workoutWithoutLiveCoach(workout: $0) }
        let rideSettings = settings.toRideSettings(effectiveFtp: effectiveFtp, effectivePhysics: effectivePhysics)
        let inMem = InMemoryRideRecorder()
        let diskSink: RideRecorder
        do {
            let logFile = rideStore.logURL(for: rideId)
            let logRecorder = try AppleCoreFactories.shared.createRideLogRecorder(path: logFile.path)
            let asyncRec = AsyncRideRecorder(delegate: logRecorder, scope: logScope)
            activeDiskRecorder = asyncRec
            diskSink = CompositeRideRecorder(recorders: [inMem, asyncRec])
        } catch {
            logFailed = true
            diskSink = inMem
        }
        activeInMemoryRecorder = inMem

        let routes = buildRideRoutes(workout: engineWorkout)
        let session: RideEngine

        if settings.deviceSource == .simulated {
            let tickMs = Int64(max(30, 1000 / max(1, settings.demoSpeedMultiplier)))
            let bike = AppleCoreFactories.shared.createSimulatedBike(
                scope: rideScope,
                ftp: Int32(effectiveFtp),
                seed: Int64(rideNumber),
                tickMillis: tickMs,
                scriptedDropouts: settings.scriptedDropouts
            )
            bike.start(connect: true)
            activeSimulatedBike = bike
            session = AppleCoreFactories.shared.createRideEngine(
                scope: rideScope,
                trainer: bike.trainer,
                heartRate: bike.heartRate,
                workout: engineWorkout,
                settings: rideSettings,
                recorder: diskSink,
                routes: routes,
                variantRidden: choice.isVariant
            )
        } else {
            let trainer: TrainerDevice = trainerDevice ?? AppleCoreFactories.shared.unpairedTrainer()
            session = AppleCoreFactories.shared.createRideEngine(
                scope: rideScope,
                trainer: trainer,
                heartRate: strapDevice,
                workout: engineWorkout,
                settings: rideSettings,
                recorder: diskSink,
                routes: routes,
                variantRidden: choice.isVariant
            )
        }

        activeSession = session
        snapshotSub = AppleFlowWatcher.shared.watch(
            scope: coreScope,
            flow: session.snapshots,
            onEach: { [weak self] value in
                guard let snap = value as? RideSnapshot else { return }
                Task { @MainActor in
                    self?.handleSnapshot(snap, rideNumber: rideNumber, rideId: rideId, rawWorkout: rawWorkout)
                }
            },
            onComplete: {}
        )
    }

    public func startRide() {
        activeSession?.start()
        if let hash = choice.structureHash, !settings.syncUrl.isEmpty, !syncToken.isEmpty {
            Task {
                let prev = try? await RepeatsRepositoriesKt.previousAttemptOf(client: serviceClient, hash: hash)
                if let prev {
                    self.previousAttemptLine = "Previous attempt loaded (\(prev.date))"
                }
            }
        }
    }

    public func pauseRide() { activeSession?.pause() }
    public func resumeRide() { activeSession?.resume() }
    public func finishRide() { activeSession?.finish() }

    public func dismissFinishedRide() {
        finishedSummary = nil
        prepareSession()
    }

    public func dispatchAction(_ action: ClickAction) {
        actionRouter.dispatch(action: action)
    }

    private func handleSnapshot(_ snap: RideSnapshot, rideNumber: Int, rideId: String, rawWorkout: Workout?) {
        let wasLive = isRideLive
        snapshot = snap
        let nowLive = isRideLive

        sleepAssertion.setActive(nowLive)

        // Averages & trace (10 s samples)
        if snap.state == .riding, let p = snap.power?.intValue {
            powerSum += p
            powerCount += 1
            averagePower = Int((Double(powerSum) / Double(powerCount)).rounded())

            let stepIdx = Int(snap.workout?.stepIndex ?? -1)
            if stepIdx != lapIndexForAvg {
                lapIndexForAvg = stepIdx
                lapPowerSum = 0
                lapPowerCount = 0
            }
            lapPowerSum += p
            lapPowerCount += 1
            lapAveragePower = Int((Double(lapPowerSum) / Double(lapPowerCount)).rounded())

            let elapsed = Int(snap.elapsedSeconds)
            if trace.isEmpty || elapsed - (trace.last?.elapsedSeconds ?? -10) >= 10 {
                trace.append(ProfileTracePoint(elapsedSeconds: elapsed, pctFtp: Double(p) / Double(max(1, Int(snap.ftp)))))
            }
        }

        // Live coach
        liveCoach.onRideSnapshot(
            rideNumber: rideNumber,
            rideId: rideId,
            workout: rawWorkout,
            structureHash: choice.structureHash,
            variantRidden: choice.isVariant,
            snapshot: snap
        )
        overlayController.updateCoach(liveCoach.overlayState)

        // Overlay metrics with 1 Hz throttle + rider-action bypass + step-change auto-expand
        let mappedOverlay = mapOverlayState(from: snap)
        let nowMs = Self.monotonicMillis()
        let bypass = lastOverlayState.map { OverlayPolicy.isRiderAction(previous: $0, next: mappedOverlay) } ?? true
        if bypass || nowMs - lastOverlayEmitMs >= OverlayPolicy.minUpdateIntervalMs {
            lastOverlayEmitMs = nowMs
            lastOverlayState = mappedOverlay
            overlayController.updateMetrics(mappedOverlay)
        }

        if settings.overlayExpandAtStepChange, let w = snap.workout, settings.overlayMode == .compact {
            let nearBoundary = Int(w.stepRemainingSeconds) <= 10 || Int(w.stepElapsedSeconds) <= 10
            if nearBoundary && !autoExpandedStep {
                autoExpandedStep = true
                overlayController.updateLook(OverlayLook(mode: .detailed, opacity: settings.overlayOpacity, blurEnabled: settings.overlayBlur))
            } else if !nearBoundary && autoExpandedStep {
                autoExpandedStep = false
                overlayController.updateLook(OverlayLook(mode: .compact, opacity: settings.overlayOpacity, blurEnabled: settings.overlayBlur))
            }
        }

        updateOverlayVisibility()
        menuBar.update(
            isLive: nowLive,
            isPaused: snap.state == .paused,
            powerWatts: snap.power?.intValue,
            alwaysShowOverlay: settings.overlayAlwaysShow,
            coachActive: liveCoach.overlayState.badge != .off,
            coachMuted: liveCoach.overlayState.badge == .on(muted: true)
        )

        if snap.state == .finished && finishedSummary == nil {
            finalizeFinishedRide(rideId: rideId, snap: snap)
        }
        if wasLive && !nowLive && snap.state == .finished {
            NSApp.activate(ignoringOtherApps: true)
            NSApp.windows.first(where: { !($0 is OverlayPanel) })?.makeKeyAndOrderFront(nil)
        }
    }

    private func finalizeFinishedRide(rideId: String, snap: RideSnapshot) {
        guard let record = activeInMemoryRecorder?.record else { return }
        let title = choice.title
        let steps = snap.workout?.steps ?? []
        var zones = Array(repeating: 0, count: 7)
        for sample in record.samples {
            if !sample.paused, let p = sample.power?.intValue {
                let z = Int(PowerZone.Companion.shared.of(powerWatts: Int32(p), ftp: record.ride.ftp).number) - 1
                if z >= 0 && z < 7 { zones[z] += 1 }
            }
        }
        let laps: [(Int, String, Int, Int?, Int?)] = record.laps.enumerated().map { idx, lap in
            let stepName: String
            if let sIdx = lap.stepIndex?.intValue, sIdx >= 0, sIdx < steps.count {
                stepName = steps[sIdx].label
            } else {
                stepName = title
            }
            return (
                idx + 1,
                stepName,
                Int(lap.durationSeconds),
                lap.averagePower.map { Int($0.doubleValue.rounded()) },
                lap.averageHeartRate.map { Int($0.doubleValue.rounded()) }
            )
        }
        finishedSummary = FinishedRideSummary(
            title: title,
            startEpochMillis: record.ride.startEpochMillis,
            endEpochMillis: record.ride.endEpochMillis?.int64Value,
            summary: record.summary,
            laps: laps,
            zoneSeconds: zones,
            saveState: .saving
        )

        Task {
            do {
                let meta = try rideStore.save(record: record, title: title)
                finishedSummary?.saveState = .saved(meta)
                storedRides = rideStore.listStoredRides()
                syncCoordinator.rideFinished(
                    entry: OutboxEntry(
                        rideId: meta.id,
                        enqueuedAtMillis: Self.wallMillis(),
                        startedAtMillis: meta.startEpochMillis,
                        durationSeconds: meta.durationSeconds,
                        title: meta.title
                    )
                )
            } catch {
                finishedSummary?.saveState = .failed(error.localizedDescription)
            }
        }
    }

    public func updateOverlayVisibility() {
        let wanted = OverlayPolicy.windowWanted(
            isLive: isRideLive,
            mainWindowVisible: mainWindowVisible,
            alwaysShow: settings.overlayAlwaysShow
        )
        overlayController.updateVisibility(wanted: wanted)
    }

    private func teardownSession() {
        snapshotSub?.cancel()
        snapshotSub = nil
        activeSimulatedBike?.close()
        activeSimulatedBike = nil
        activeSession = nil
        activeInMemoryRecorder = nil
        activeDiskRecorder = nil
        liveCoach.stopCurrent()
        sleepAssertion.setActive(false)
    }

    // MARK: - BLE Pairing & Devices

    public func startScan() {
        guard canChangeDevices else { return }
        stopScan()
        scanError = nil
        discoveredDevices = []
        isScanning = true
        let filter = AppleCoreFactories.shared.createScanFilter(
            serviceUuids: [
                AppleCoreFactories.shared.ftmsServiceUuid,
                AppleCoreFactories.shared.heartRateServiceUuid,
                AppleCoreFactories.shared.clickServiceUuid,
            ]
        )
        let flow = bleStack.scanner.scan(filter: filter)
        scanSub = AppleFlowWatcher.shared.watch(
            scope: coreScope,
            flow: flow,
            onEach: { [weak self] value in
                Task { @MainActor in
                    guard let self else { return }
                    if let found = value as? ScanEventFound {
                        self.ingestAdvertisement(found.advertisement)
                    } else if let failed = value as? ScanEventFailed {
                        self.isScanning = false
                        self.scanError = failed.reason == .permissionDenied
                            ? "Bluetooth permission is off for Overdrive."
                            : "Bluetooth is off."
                    }
                }
            },
            onComplete: {}
        )
        scanSweeper = Timer.scheduledTimer(withTimeInterval: 2.0, repeats: true) { [weak self] _ in
            Task { @MainActor in
                let cutoff = ProcessInfo.processInfo.systemUptime - 12.0
                self?.discoveredDevices.removeAll { $0.lastSeenUptime < cutoff }
            }
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 30.0) { [weak self] in
            self?.stopScan()
        }
    }

    public func stopScan() {
        scanSub?.cancel()
        scanSub = nil
        scanSweeper?.invalidate()
        scanSweeper = nil
        isScanning = false
    }

    public func pairDevice(_ device: DiscoveredDevice) {
        guard canChangeDevices else { return }
        rememberedDevices[device.role] = RememberedDeviceRecord(id: device.id, name: device.name)
        settingsStore.saveDevices(rememberedDevices)
        stopScan()
        reconnectRememberedDevices()
        prepareSession()
    }

    public func forgetDevice(role: MacDeviceRole) {
        guard canChangeDevices else { return }
        rememberedDevices[role] = nil
        settingsStore.saveDevices(rememberedDevices)
        reconnectRememberedDevices()
        prepareSession()
    }

    public func retryDevice(role: MacDeviceRole) {
        switch role {
        case .trainer: trainerDevice?.retryNow()
        case .strap: strapDevice?.retryNow()
        case .click: clickDevice?.retryNow()
        }
    }

    private func ingestAdvertisement(_ adv: Advertisement) {
        let role: MacDeviceRole?
        if AppleCoreFactories.shared.advertisementHasService(adv: adv, uuid: AppleCoreFactories.shared.ftmsServiceUuid) {
            role = .trainer
        } else if AppleCoreFactories.shared.advertisementHasService(adv: adv, uuid: AppleCoreFactories.shared.heartRateServiceUuid) {
            role = .strap
        } else if ClickAdvertisements.shared.recognise(advertisement: adv, options: settings.toClickOptions()) != nil {
            role = .click
        } else {
            role = nil
        }
        guard let role else { return }

        let roundedRssi = (Int(adv.rssi) / 5) * 5
        let name = role == .click ? "Zwift Click (right)" : RememberedDeviceRecord.clean(adv.name ?? role.fallbackName)
        let item = DiscoveredDevice(
            id: AppleCoreFactories.shared.advertisementIdString(adv: adv),
            role: role,
            name: name.isEmpty ? role.fallbackName : name,
            rssi: roundedRssi,
            lastSeenUptime: ProcessInfo.processInfo.systemUptime
        )
        if let idx = discoveredDevices.firstIndex(where: { $0.id == item.id && $0.role == item.role }) {
            discoveredDevices[idx] = item
        } else {
            discoveredDevices.append(item)
        }
        discoveredDevices.sort { $0.rssi > $1.rssi }
    }

    private func reconnectRememberedDevices() {
        trainerDevice?.close()
        strapDevice?.close()
        clickDevice?.close()
        handlebarControls?.close()
        clickButtonSubscription?.cancel()
        trainerDevice = nil
        strapDevice = nil
        clickDevice = nil
        handlebarControls = nil

        let strategy: () -> ConnectStrategy = { [weak self] in
            (self?.settings.connectInBackground ?? true) ? .hybrid : .legacy
        }

        if let tr = rememberedDevices.trainer {
            let factory = wrappedTransportFactory(role: "trainer", model: { tr.name })
            let dev = AppleCoreFactories.shared.createFtmsTrainer(
                scope: coreScope,
                deviceId: tr.id,
                transports: factory,
                environment: bleStack.environment,
                name: tr.name,
                connectStrategy: strategy
            )
            trainerDevice = dev
            dev.start()
            watchLink(role: .trainer, name: tr.name, flow: dev.linkStatus)
        } else {
            trainerConnected = false
            trainerStatusText = "Not paired"
        }

        if let st = rememberedDevices.strap {
            let factory = wrappedTransportFactory(role: "strap", model: { st.name })
            let dev = AppleCoreFactories.shared.createHeartRateStrap(
                scope: coreScope,
                deviceId: st.id,
                transports: factory,
                environment: bleStack.environment,
                name: st.name,
                connectStrategy: strategy
            )
            strapDevice = dev
            dev.start()
            watchLink(role: .strap, name: st.name, flow: dev.linkStatus)
        } else {
            strapConnected = false
            strapStatusText = "Not paired"
        }

        if let cl = rememberedDevices.click {
            let factory = wrappedTransportFactory(role: "click", model: { cl.name })
            let dev = AppleCoreFactories.shared.createClickDevice(
                scope: coreScope,
                deviceId: cl.id,
                transports: factory,
                environment: bleStack.environment,
                name: cl.name,
                options: settings.toClickOptions(),
                connectStrategy: strategy
            )
            clickDevice = dev
            dev.start()
            watchClickState(name: cl.name, flow: dev.state)

            let controls = AppleCoreFactories.shared.createHandlebarControls(
                scope: coreScope,
                changes: dev.buttons,
                mapping: { [weak self] in self?.settings.toButtonMapping() ?? ButtonMapping.Companion.shared.DEFAULT },
                onAction: { [weak self] action in
                    guard let self else { return }
                    Task { @MainActor in
                        if self.screen == .learnButtons { return }
                        self.actionRouter.dispatch(action: action)
                    }
                },
                holdMillis: Int64(settings.clickHoldThresholdMs),
                repeatMillis: Int64(settings.clickVolumeRepeatMs),
                nowMillis: { KotlinLong(longLong: Self.monotonicMillis()) }
            )
            handlebarControls = controls
            controls.start()

            clickButtonSubscription = AppleFlowWatcher.shared.watch(
                scope: coreScope,
                flow: dev.buttons,
                onEach: { [weak self] val in
                    guard let change = val as? ButtonChange else { return }
                    Task { @MainActor in self?.handleLearningPress(change) }
                },
                onComplete: {}
            )
        } else {
            clickConnected = false
            clickStatusText = "Not paired"
            clickLockedMessage = nil
        }
    }

    private func wrappedTransportFactory(role: String, model: @escaping () -> String) -> GattTransportFactory {
        AppleCoreFactories.shared.wrapTransportFactory(
            delegate: RecordingFactoryAdapter(
                base: bleStack.transports,
                bleLogs: bleLogs,
                role: role,
                scope: coreScope,
                logScope: logScope,
                nanoClock: { [weak self] in self?.bleStack.nanoClock() ?? 0 },
                model: model,
                enabled: { [weak self] in self?.settings.recordBleSessions ?? true }
            )
        )
    }

    private func watchLink(role: MacDeviceRole, name: String, flow: Kotlinx_coroutines_coreStateFlow) {
        subscriptions.append(
            AppleFlowWatcher.shared.watch(
                scope: coreScope,
                flow: flow,
                onEach: { [weak self] val in
                    guard let status = val as? LinkStatus else { return }
                    Task { @MainActor in
                        guard let self else { return }
                        let connected = status is LinkStatusConnected
                        let text = self.formatLinkStatus(status, role: role, name: name)
                        if role == .trainer {
                            self.trainerConnected = connected
                            self.trainerStatusText = text
                        } else if role == .strap {
                            self.strapConnected = connected
                            self.strapStatusText = text
                        }
                        self.bleLogsSummary = self.bleLogs.summary()
                    }
                },
                onComplete: {}
            )
        )
    }

    private func watchClickState(name: String, flow: Kotlinx_coroutines_coreStateFlow) {
        subscriptions.append(
            AppleFlowWatcher.shared.watch(
                scope: coreScope,
                flow: flow,
                onEach: { [weak self] val in
                    guard let st = val as? ClickState else { return }
                    Task { @MainActor in
                        guard let self else { return }
                        if st is ClickStateActive {
                            self.clickConnected = true
                            self.clickStatusText = "\(name) \u{00B7} Connected"
                            self.clickLockedMessage = nil
                        } else if let locked = st as? ClickStateNeedsUnlock {
                            self.clickConnected = false
                            self.clickStatusText = "\(name) \u{00B7} Locked"
                            self.clickLockedMessage = locked.signal == .serviceHidden
                                ? "Zwift has it locked. Unlock it through Zwift once; the ride continues without it."
                                : "Press any button on it. If that does nothing, unlock it through Zwift once."
                        } else if st is ClickStateWaitingForDevice {
                            self.clickConnected = false
                            self.clickStatusText = "Waiting for the Click to wake up"
                            self.clickLockedMessage = nil
                        } else if st is ClickStateConnecting {
                            self.clickConnected = false
                            self.clickStatusText = "\(name) \u{00B7} Connecting\u{2026}"
                            self.clickLockedMessage = nil
                        } else {
                            self.clickConnected = false
                            self.clickStatusText = "\(name) \u{00B7} Reconnecting\u{2026}"
                        }
                    }
                },
                onComplete: {}
            )
        )
    }

    private func formatLinkStatus(_ status: LinkStatus, role: MacDeviceRole, name: String) -> String {
        if status is LinkStatusConnected { return "\(name) \u{00B7} Connected" }
        if status is LinkStatusConnecting { return "\(name) \u{00B7} Connecting\u{2026}" }
        if status is LinkStatusWaitingForDevice {
            return "Waiting for the \(role.title.lowercased()) to wake up"
        }
        if status is LinkStatusBluetoothUnavailable {
            return bleAvailability == .permissionDenied ? "\(name) \u{00B7} needs Bluetooth access" : "\(name) \u{00B7} Bluetooth is off"
        }
        return "\(name) \u{00B7} Reconnecting\u{2026}"
    }

    // MARK: - Button Learning

    public func startButtonLearning(coachMuteOnly: Bool = false) {
        guard clickConnected && canChangeDevices else { return }
        learningCoachMute = coachMuteOnly
        learningError = nil
        learningDone = false
        learningStepIndex = 0
        learningMapPreview = [:]
        if coachMuteOnly {
            coachButtonLearner = AppleCoreFactories.shared.createCoachButtonLearner(base: settings.toButtonMapping())
            buttonLearner = nil
        } else {
            buttonLearner = AppleCoreFactories.shared.createButtonLearner(base: settings.toButtonMapping())
            coachButtonLearner = nil
        }
        screen = .learnButtons
    }

    public func cancelButtonLearning() {
        buttonLearner = nil
        coachButtonLearner = nil
        screen = .devices
    }

    public func resetButtonMapping() {
        updateSettings { $0.buttonMappingSerialized = "" }
    }

    private func handleLearningPress(_ change: ButtonChange) {
        guard screen == .learnButtons, !change.synthetic else { return }
        if let learner = buttonLearner {
            let state = learner.feed(held: change.held)
            if let asking = state as? LearnStateAsking {
                learningStepIndex = Int(asking.step) - 1
                learningError = nil
            } else if let done = state as? LearnStateComplete {
                learningDone = true
                learningError = nil
                updateSettings { $0.buttonMappingSerialized = done.mapping.serialize() }
            } else if let rejected = state as? LearnStateRejected {
                learningError = rejected.reason == .combo
                    ? "Two buttons were pressed together. Press just one button."
                    : "That button is already assigned to another role. Press a different one."
            }
        } else if let coachLearner = coachButtonLearner {
            let state = coachLearner.feed(held: change.held)
            if let done = state as? CoachLearnStateComplete {
                learningDone = true
                learningError = nil
                updateSettings { $0.buttonMappingSerialized = done.mapping.serialize() }
            } else if let rejected = state as? CoachLearnStateRejected {
                learningError = rejected.reason == .combo
                    ? "Press a single button for Mute Live Coach."
                    : "That button is already used by one of the five main roles."
            }
        }
    }

    // MARK: - Settings & Sync Controls

    public func updateSettings(_ change: (inout MacAppSettings) -> Void) {
        let old = settings
        var next = settings
        change(&next)
        next = next.sanitized()
        settings = next
        settingsStore.saveSettings(next)

        if old.overlayMode != next.overlayMode || old.overlayOpacity != next.overlayOpacity || old.overlayBlur != next.overlayBlur {
            overlayController.updateLook(OverlayLook(mode: next.overlayMode, opacity: next.overlayOpacity, blurEnabled: next.overlayBlur))
        }
        if old.overlayAlwaysShow != next.overlayAlwaysShow {
            updateOverlayVisibility()
        }
        if old.deviceSource != next.deviceSource || old.ftp != next.ftp || old.gearCount != next.gearCount || old.gradeStepPercent != next.gradeStepPercent || old.cadenceGuardEnabled != next.cadenceGuardEnabled || old.cadenceFloorRpm != next.cadenceFloorRpm {
            if !isRideLive { prepareSession() }
        }
        if old.clickSilenceMeansLocked != next.clickSilenceMeansLocked || old.clickEitherPuck != next.clickEitherPuck || old.clickHoldThresholdMs != next.clickHoldThresholdMs || old.clickVolumeRepeatMs != next.clickVolumeRepeatMs {
            reconnectRememberedDevices()
        }
    }

    public func setSyncUrl(_ raw: String) {
        let check = settings.validateSyncUrl(raw)
        syncUrlWarning = check.warning
        syncUrlError = check.error
        if check.error == nil {
            updateSettings { $0.syncUrl = check.normalized }
            scheduleSyncSettle()
        }
    }

    public func setSyncToken(_ raw: String) {
        let clean = KeychainStore.sanitizeToken(raw)
        syncToken = clean
        keychain.writeToken(clean)
        scheduleSyncSettle()
    }

    public func sendSyncNow() {
        syncProvider.retryRejected()
        syncCoordinator.kick(retryRefused: true)
    }

    public func refreshRemote(reason: PlanRefresh = .requested) {
        let remoteReason: Refresh = reason == .start ? .start : .requested
        let profileReason: ProfileRefresh = reason == .start ? .start : .requested
        Task {
            try? await planRepo.refresh(why: reason)
            try? await profileRepo.refresh(why: profileReason)
            try? await routeLibrary.refreshList(why: remoteReason)
            try? await recoveryResource.refresh(why: remoteReason)
        }
    }

    private func scheduleSyncSettle() {
        syncSettleTask?.cancel()
        syncSettleTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 1_500_000_000)
            guard !Task.isCancelled, let self else { return }
            self.syncProvider.retryRejected()
            self.syncCoordinator.kick(retryRefused: true)
            self.refreshRemote(reason: .settingsChanged)
        }
    }

    public func resetOverlayPosition() {
        updateSettings {
            $0.overlayX = nil
            $0.overlayY = nil
        }
        overlayController.resetPosition()
    }

    // MARK: - FIT & BLE Log File Actions

    public func saveFitToFolder(_ ride: StoredRideMeta) {
        let panel = NSSavePanel()
        panel.nameFieldStringValue = rideStore.suggestedFitFilename(title: ride.title, startEpochMillis: ride.startEpochMillis)
        panel.allowedContentTypes = [.data]
        if panel.runModal() == .OK, let dest = panel.url {
            do {
                if FileManager.default.fileExists(atPath: dest.path) {
                    try FileManager.default.removeItem(at: dest)
                }
                try FileManager.default.copyItem(at: ride.fitURL, to: dest)
                lastSaveMessage = "Saved to \(dest.lastPathComponent)."
            } catch {
                lastSaveMessage = "Could not save FIT file: \(error.localizedDescription)"
            }
        }
    }

    public func revealInFinder(_ url: URL) {
        NSWorkspace.shared.activateFileViewerSelecting([url])
    }

    public func exportBleLogsZip() {
        let df = DateFormatter()
        df.dateFormat = "yyyyMMdd-HHmm"
        let panel = NSSavePanel()
        panel.nameFieldStringValue = "overdrive-ble-logs-\(df.string(from: Date())).zip"
        if panel.runModal() == .OK, let dest = panel.url {
            do {
                let res = try bleLogs.exportZip(to: dest)
                bleLogsShareMessage = "Exported \(res.included) sessions to \(dest.lastPathComponent) (contains heart-rate data; never uploaded)."
            } catch {
                bleLogsShareMessage = error.localizedDescription
            }
        }
    }

    public func recoverInterrupted(_ candidate: InterruptedRideCandidate) {
        do {
            let meta = try rideStore.recoverAndSave(candidate)
            interruptedRides.removeAll { $0.id == candidate.id }
            storedRides = rideStore.listStoredRides()
            syncCoordinator.rideFinished(
                entry: OutboxEntry(
                    rideId: meta.id,
                    enqueuedAtMillis: Self.wallMillis(),
                    startedAtMillis: meta.startEpochMillis,
                    durationSeconds: meta.durationSeconds,
                    title: meta.title
                )
            )
            lastSaveMessage = "Recovered and saved \(meta.title)."
        } catch {
            lastSaveMessage = "Could not recover ride: \(error.localizedDescription)"
        }
    }

    public func discardInterrupted(_ candidate: InterruptedRideCandidate) {
        rideStore.discardInterrupted(candidate)
        interruptedRides.removeAll { $0.id == candidate.id }
    }

    // MARK: - Mapping Helpers

    private func buildRideRoutes(workout: Workout?) -> RideRoutes {
        if workout == nil, let id = selectedFreeRouteId, let route = downloadedRoutes[id] {
            return AppleCoreFactories.shared.freeRideRoutes(route: route, difficultyPercent: Int32(settings.routeDifficultyPercent))
        }
        return AppleCoreFactories.shared.noneRideRoutes()
    }

    private func mapOverlayState(from snap: RideSnapshot) -> OverlayUiState {
        let fifth: OverlayFifthCell
        if snap.stallGuardActive, let w = snap.stallGuardWatts?.intValue {
            fifth = .cadenceGuard(watts: w)
        } else if let target = snap.targetPower?.intValue {
            fifth = .target(watts: target, intensityPercent: snap.heartRateHold == nil ? Int(snap.intensityPercent) : nil)
        } else if let gear = snap.gear?.intValue {
            fifth = .gear(current: gear, total: Int(snap.gearCount))
        } else {
            fifth = .none
        }

        let hold: OverlayHeartRateHold? = snap.heartRateHold.map { h in
            let hr = snap.heartRate?.intValue
            let statusStr: String
            let showGauge: Bool
            if h.strapLost {
                statusStr = "Strap lost \u{00B7} power at the minimum, \(h.powerMin) W"
                showGauge = false
            } else if h.signalLost {
                statusStr = "Strap lost \u{00B7} holding \(snap.targetPower?.intValue ?? Int(h.powerMin)) W"
                showGauge = false
            } else if h.startRamp {
                statusStr = "Easing in \u{00B7} target \(h.targetBpm) bpm \u{00B1}\(h.band)"
                showGauge = false
            } else if let hr {
                let bandState: String
                if h.inBand?.boolValue == true {
                    bandState = "In band"
                } else if hr > Int(h.targetBpm) {
                    bandState = "Above band"
                } else {
                    bandState = "Below band"
                }
                statusStr = "Target \(h.targetBpm) bpm \u{00B1}\(h.band) \u{00B7} \(bandState)"
                showGauge = true
            } else {
                statusStr = "No heart rate \u{00B7} target \(h.targetBpm) bpm"
                showGauge = false
            }
            return OverlayHeartRateHold(
                targetBpm: Int(h.targetBpm),
                deltaBpm: 0,
                bandBpm: Int(h.band),
                currentBpm: hr,
                statusText: statusStr,
                showGauge: showGauge
            )
        }

        let workoutInfo: OverlayWorkoutInfo? = snap.workout.map { w in
            let profileSteps = w.steps.map { step in
                let startPct = step.targetFrom?.doubleValue ?? 0.5
                let endPct = step.targetTo?.doubleValue ?? startPct
                let midWatts = Int((startPct + endPct) * 0.5 * Double(snap.ftp))
                let z = Int(PowerZone.Companion.shared.of(powerWatts: Int32(midWatts), ftp: snap.ftp).number)
                return ProfileStepData(
                    durationSeconds: Int(step.durationSeconds),
                    startPctFtp: startPct,
                    endPctFtp: endPct,
                    isFreeRide: step.mode == .simulation,
                    isHeartRate: step.heartRate != nil,
                    zone: z
                )
            }
            let nextText: String
            if let next = w.nextStep {
                let dur = UiFormat.minutesAndSeconds(Int(next.durationSeconds))
                if let frac = next.targetFrom?.doubleValue {
                    let watts = Int((frac * Double(snap.ftp)).rounded())
                    nextText = "\(next.label) \u{00B7} \(dur) \u{00B7} \(watts) W"
                } else {
                    nextText = "\(next.label) \u{00B7} \(dur)"
                }
            } else {
                nextText = "Finish"
            }
            return OverlayWorkoutInfo(
                intervalName: w.stepLabel,
                repeatBadge: nil,
                stepRemainingSeconds: Int(w.stepRemainingSeconds),
                nextSummary: nextText,
                previousAttemptLine: previousAttemptLine,
                elapsedSeconds: Int(w.workoutElapsedSeconds),
                profile: WorkoutProfileData(steps: profileSteps)
            )
        }

        return OverlayUiState(
            power: snap.power?.intValue,
            zone: snap.zone.map { Int($0.number) },
            heartRate: snap.heartRate?.intValue,
            cadence: snap.cadence?.intValue,
            elapsedSeconds: Int(snap.elapsedSeconds),
            speedKmh: snap.speedKmh,
            distanceMeters: snap.distanceMeters,
            paused: snap.state == .paused,
            fifthCell: fifth,
            hold: hold,
            workout: workoutInfo
        )
    }

    private func updateSyncStatusText(_ status: SyncStatus) {
        if let idle = status as? SyncStatusIdle {
            syncPendingCount = Int(idle.pending)
            syncStatusLine = nil
        } else if let syncing = status as? SyncStatusSyncing {
            syncPendingCount = Int(syncing.pending)
            syncStatusLine = "Uploading\u{2026}"
        } else if let rejected = status as? SyncStatusTokenRejected {
            syncPendingCount = Int(rejected.pending)
            syncStatusLine = "The service did not accept the token"
        } else if let waiting = status as? SyncStatusWaitingToRetry {
            syncPendingCount = Int(waiting.pending)
            syncStatusLine = waiting.reason ?? "Trying again soon"
        } else if let unconf = status as? SyncStatusNotConfigured {
            syncPendingCount = Int(unconf.pending)
            syncStatusLine = settings.syncUrl.isEmpty ? "Not configured" : nil
        } else if let unavail = status as? SyncStatusOutboxUnavailable {
            syncStatusLine = unavail.reason ?? "Outbox unavailable"
        } else {
            syncStatusLine = nil
        }
    }

    private static func localToday() -> String {
        let df = DateFormatter()
        df.dateFormat = "yyyy-MM-dd"
        return df.string(from: Date())
    }

    private static func wallMillis() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000.0)
    }

    private static func monotonicMillis() -> Int64 {
        Int64(ProcessInfo.processInfo.systemUptime * 1000.0)
    }
}

private final class RecordingFactoryAdapter: NSObject, AppleGattTransportFactory {
    private let base: GattTransportFactory
    private let bleLogs: BleLogStore
    private let role: String
    private let scope: Kotlinx_coroutines_coreCoroutineScope
    private let logScope: Kotlinx_coroutines_coreCoroutineScope
    private let nanoClock: () -> Int64
    private let model: () -> String
    private let enabled: () -> Bool

    init(
        base: GattTransportFactory,
        bleLogs: BleLogStore,
        role: String,
        scope: Kotlinx_coroutines_coreCoroutineScope,
        logScope: Kotlinx_coroutines_coreCoroutineScope,
        nanoClock: @escaping () -> Int64,
        model: @escaping () -> String,
        enabled: @escaping () -> Bool
    ) {
        self.base = base
        self.bleLogs = bleLogs
        self.role = role
        self.scope = scope
        self.logScope = logScope
        self.nanoClock = nanoClock
        self.model = model
        self.enabled = enabled
    }

    func open(deviceId: String, mode: ConnectMode) -> GattTransport {
        let raw = AppleCoreFactories.shared.openWrappedTransport(factory: base, deviceId: deviceId, mode: mode)
        guard enabled() else { return raw }
        let sink = bleLogs.openSessionSink(role: role)
        return AppleCoreFactories.shared.createRecordingTransport(
            delegate: raw,
            scope: scope,
            logScope: logScope,
            sink: sink,
            nanoClock: { KotlinLong(longLong: self.nanoClock()) },
            model: model(),
            mode: mode
        )
    }
}
