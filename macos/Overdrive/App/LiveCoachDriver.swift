import Foundation
import OverdriveCore

/// Thin macOS driver around `core`'s `LiveSessionController`, `CueStream`, `CuePlayback`, and `MacCuePlayer` (ADR 0039, ADR 0044).
@MainActor
public final class LiveCoachDriver: ObservableObject {
    @Published public private(set) var overlayState: OverlayCoachState = .none

    private let client: LiveCoachClient
    private let player: CuePlayer
    private let enabledProvider: () -> Bool
    private let scope = AppleScopes.shared.singleThread()

    private var controller: LiveSessionController?
    private var playback: CuePlayback?
    private var streamTask: Task<Void, Never>?
    private var wakeTask: Task<Void, Never>?
    private var activeSessionId: String?
    private var activeRideNumber = 0
    private var currentPlate: String?

    public init(client: LiveCoachClient, player: CuePlayer, enabled: @escaping () -> Bool) {
        self.client = client
        self.player = player
        self.enabledProvider = enabled
    }

    public func onRideSnapshot(
        rideNumber: Int,
        rideId: String?,
        workout: Workout?,
        structureHash: String?,
        variantRidden: Bool,
        snapshot: RideSnapshot
    ) {
        if rideNumber != activeRideNumber {
            stopCurrent()
            activeRideNumber = rideNumber
        }
        guard enabledProvider(),
              let workout,
              workout.liveCoach?.boolValue == true,
              client.isConfigured else {
            return
        }

        if controller == nil, let rideId, snapshot.state == .riding || snapshot.state == .paused {
            let plan = LivePlan(
                rideId: rideId,
                workoutId: workout.id,
                structureHash: structureHash,
                variantRidden: variantRidden
            )
            controller = AppleCoreFactories.shared.createLiveSessionController(
                plan: plan,
                wallClockMillis: { KotlinLong(longLong: Self.wallMillis()) }
            )
        }

        guard let controller else { return }
        let effects = controller.onRide(nowMillis: Self.monotonicMillis(), snapshot: snapshot)
        apply(effects: effects, controller: controller)
    }

    public func mute() {
        guard let controller else { return }
        playback?.mute()
        let effects = controller.mute(nowMillis: Self.monotonicMillis())
        apply(effects: effects, controller: controller)
    }

    public func stopCurrent() {
        wakeTask?.cancel()
        wakeTask = nil
        streamTask?.cancel()
        streamTask = nil
        playback?.close()
        playback = nil
        if let controller {
            let effects = controller.close(reason: .stopped)
            apply(effects: effects, controller: controller)
        }
        controller = nil
        activeSessionId = nil
        currentPlate = nil
        overlayState = .none
    }

    private func apply(effects: [LiveEffect], controller: LiveSessionController) {
        updateBadge(controller.state)
        for effect in effects {
            if let start = effect as? LiveEffectStartSession {
                Task { [weak self] in
                    guard let self else { return }
                    let answer = try? await self.client.start(request: start.request)
                    guard let answer, self.controller === controller else { return }
                    let next = controller.onStartAnswer(nowMillis: Self.monotonicMillis(), answer: answer)
                    self.apply(effects: next, controller: controller)
                }
            } else if let send = effect as? LiveEffectSendSnapshot {
                Task { [weak self] in
                    guard let self else { return }
                    let answer = try? await self.client.snapshot(sessionId: send.sessionId, snapshot: send.snapshot)
                    guard let answer, self.controller === controller else { return }
                    let next = controller.onSnapshotAnswer(nowMillis: Self.monotonicMillis(), session: send.sessionId, answer: answer)
                    self.apply(effects: next, controller: controller)
                }
            } else if let open = effect as? LiveEffectOpenStream {
                openCueStream(sessionId: open.sessionId, controller: controller)
            } else if effect is LiveEffectCloseStream {
                streamTask?.cancel()
                streamTask = nil
                playback?.close()
                playback = nil
            } else if let end = effect as? LiveEffectEndSession {
                let body = LiveSessionEnd(reason: end.reason, clock: KotlinLong(longLong: Self.wallMillis()))
                Task { [weak self] in
                    try? await self?.client.end(sessionId: end.sessionId, end: body)
                }
            }
        }
        scheduleWake(for: controller)
    }

    private func scheduleWake(for controller: LiveSessionController) {
        wakeTask?.cancel()
        wakeTask = nil
        guard let nextWake = controller.nextWakeMillis()?.int64Value else { return }
        let delayMs = max(50, nextWake - Self.monotonicMillis())
        wakeTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(delayMs) * 1_000_000)
            guard !Task.isCancelled, let self, self.controller === controller else { return }
            let next = controller.onTime(nowMillis: Self.monotonicMillis())
            self.apply(effects: next, controller: controller)
        }
    }

    private func openCueStream(sessionId: String, controller: LiveSessionController) {
        streamTask?.cancel()
        playback?.close()
        activeSessionId = sessionId

        let pb = AppleCoreFactories.shared.createCuePlayback(
            scope: scope,
            player: player,
            nowMillis: { KotlinLong(longLong: Self.monotonicMillis()) },
            report: { [weak self] cueId, played in
                guard let self, let sid = self.activeSessionId else { return }
                Task {
                    try? await self.client.played(
                        sessionId: sid,
                        cueId: cueId.int32Value,
                        played: LiveCuePlayed(played: played.boolValue, clock: KotlinLong(longLong: Self.wallMillis()))
                    )
                }
            },
            onPlate: { [weak self] text in
                Task { @MainActor in
                    self?.currentPlate = text
                    if let c = self?.controller {
                        self?.updateBadge(c.state)
                    }
                }
            }
        )
        playback = pb

        let source = client.cues(sessionId: sessionId)
        let stream = AppleCoreFactories.shared.createCueStream(source: source, onCue: { cue in pb.offer(cue: cue) })
        streamTask = Task { [weak self] in
            guard let end = try? await stream.run() else { return }
            await MainActor.run {
                guard let self, self.controller === controller else { return }
                let next = controller.onStreamEnd(nowMillis: Self.monotonicMillis(), session: sessionId, end: end)
                self.apply(effects: next, controller: controller)
            }
        }
    }

    private func updateBadge(_ state: LiveCoachState) {
        if state is LiveCoachStateOff {
            overlayState = .none
        } else if state is LiveCoachStateUnavailable {
            overlayState = OverlayCoachState(badge: .unavailable, plateText: nil)
        } else if let on = state as? LiveCoachStateOn {
            overlayState = OverlayCoachState(badge: .on(muted: on.muted), plateText: on.muted ? nil : currentPlate)
        }
    }

    private static func monotonicMillis() -> Int64 {
        Int64(ProcessInfo.processInfo.systemUptime * 1000.0)
    }

    private static func wallMillis() -> Int64 {
        Int64(Date().timeIntervalSince1970 * 1000.0)
    }
}
