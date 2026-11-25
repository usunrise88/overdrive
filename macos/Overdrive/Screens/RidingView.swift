import OverdriveCore
import SwiftUI

/// Live Riding & Paused screen (design/system.md §6.2 & §6.3).
public struct RidingView: View {
    @ObservedObject public var container: AppContainer
    @State private var confirmFinish = false

    public init(container: AppContainer) {
        self.container = container
    }

    private var isPaused: Bool { container.snapshot.state == .paused }

    public var body: some View {
        VStack(spacing: 0) {
            VStack(spacing: OverdriveDimens.spaceS4) {
                if isPaused {
                    pausedBanner
                }
                if container.logFailed {
                    OdNotice(
                        icon: .triangleAlert,
                        title: "This ride is not being saved",
                        message: "Keep riding. Overdrive tries to save it again when you finish."
                    )
                }

                // Row 1: Power hero card (424 pt) + 3x2 tile group
                HStack(spacing: OverdriveDimens.spaceS4) {
                    powerCard
                        .frame(width: 424)
                    metricsTileGroup
                }
                .frame(height: 312)

                // Row 2: Workout profile card OR Free-ride gear/route card
                if let w = container.snapshot.workout {
                    workoutCard(w)
                } else {
                    freeRideCard
                }

                Spacer(minLength: 0)
            }
            .padding(.top, OverdriveDimens.spaceS5)
            .padding(.horizontal, OverdriveDimens.spaceS6)
            .padding(.bottom, OverdriveDimens.spaceS4)

            OdDivider()
            controlBar
        }
        .alert("Finish and save this ride?", isPresented: $confirmFinish) {
            Button("Finish", role: .destructive) {
                container.finishRide()
            }
            Button("Keep riding", role: .cancel) {}
        }
    }

    private var pausedBanner: some View {
        HStack(spacing: OverdriveDimens.spaceS3) {
            OdIcon(.pause, size: 18, color: OverdriveColors.textPrimary)
            Text("Paused at \(UiFormat.elapsed(Int(container.snapshot.elapsedSeconds)))")
                .odStyle(OverdriveTypography.bodyStrong, color: OverdriveColors.textPrimary)
            Text(pauseExplanation)
                .odStyle(OverdriveTypography.body, color: OverdriveColors.textSecondary)
            Spacer()
        }
        .padding(.horizontal, OverdriveDimens.spaceS4)
        .frame(height: 48)
        .background(OverdriveColors.surface2)
        .clipShape(RoundedRectangle(cornerRadius: OverdriveDimens.radiusMd))
    }

    private var pauseExplanation: String {
        switch container.snapshot.pauseReason {
        case .trainerDisconnected:
            return "Trainer connection lost. Reconnecting automatically."
        case .manual:
            return "Press Resume or Space to continue. Target power ramps back in over 3 s."
        default:
            return "Pedal or press Resume to continue. Target power ramps back in over 3 s."
        }
    }

    private var powerCard: some View {
        let s = container.snapshot
        let power = s.power?.intValue
        let zone = s.zone.map { Int($0.number) }
        let pctFtp = power.map { Int((Double($0) / Double(max(1, Int(s.ftp))) * 100.0).rounded()) }

        return OdCard(padding: OverdriveDimens.spaceS5) {
            VStack(alignment: .leading, spacing: 0) {
                HStack {
                    Text("Power").odStyle(OverdriveTypography.label, color: OverdriveColors.textTertiary)
                    Spacer()
                    if !isPaused, let z = zone {
                        HStack(spacing: OverdriveDimens.spaceS2) {
                            OdZoneChip(zone: z)
                            Text("\(OverdriveColors.zoneNames[z - 1]) \u{00B7} \(pctFtp ?? 0)%")
                                .odStyle(OverdriveTypography.label, color: OverdriveColors.textSecondary)
                        }
                    }
                }

                Spacer()

                HStack(alignment: .firstTextBaseline, spacing: OverdriveDimens.spaceS2) {
                    Text(power.map(String.init) ?? UiFormat.emptyDash)
                        .odStyle(
                            OverdriveTypography.numHero,
                            color: (isPaused || power == nil) ? OverdriveColors.textTertiary : OverdriveColors.zone(zone ?? 1)
                        )
                    if power != nil {
                        Text("W")
                            .odStyle(OverdriveTypography.title, color: OverdriveColors.textSecondary)
                    }
                }

                Spacer()

                HStack(spacing: OverdriveDimens.spaceS6) {
                    miniReadout(
                        label: s.stallGuardActive ? "Cadence guard" : "Target",
                        value: (s.stallGuardActive ? s.stallGuardWatts?.intValue : s.targetPower?.intValue).map { "\($0) W" } ?? UiFormat.emptyDash
                    )
                    miniReadout(label: "Intensity", value: s.mode == .erg ? "\(s.intensityPercent)%" : UiFormat.emptyDash)
                    miniReadout(label: "Lap average", value: container.lapAveragePower.map { "\($0) W" } ?? UiFormat.emptyDash)
                }
            }
        }
    }

    private func miniReadout(label: String, value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).odStyle(OverdriveTypography.caption, color: OverdriveColors.textTertiary)
            Text(value).odStyle(OverdriveTypography.numS, color: isPaused ? OverdriveColors.textTertiary : OverdriveColors.textPrimary)
        }
    }

    private var metricsTileGroup: some View {
        let s = container.snapshot
        let color = isPaused ? OverdriveColors.textTertiary : OverdriveColors.textPrimary

        return OdCard(padding: 0) {
            VStack(spacing: 0) {
                HStack(spacing: 0) {
                    OdStatTile(label: "Heart rate", value: s.heartRate.map { "\($0.intValue)" } ?? "", unit: "bpm", valueColor: color, isEmpty: s.heartRate == nil)
                    Rectangle().fill(OverdriveColors.border).frame(width: 1)
                    OdStatTile(label: "Cadence", value: s.cadence.map { "\($0.intValue)" } ?? "", unit: "rpm", valueColor: color, isEmpty: s.cadence == nil)
                    Rectangle().fill(OverdriveColors.border).frame(width: 1)
                    OdStatTile(label: "Time", value: UiFormat.elapsed(Int(s.elapsedSeconds)), valueColor: OverdriveColors.textPrimary)
                }
                OdDivider()
                HStack(spacing: 0) {
                    OdStatTile(label: "Speed", value: UiFormat.speed(s.speedKmh), unit: "km/h", valueColor: color)
                    Rectangle().fill(OverdriveColors.border).frame(width: 1)
                    OdStatTile(label: "Distance", value: UiFormat.distanceKm(s.distanceMeters), unit: "km", valueColor: color)
                    Rectangle().fill(OverdriveColors.border).frame(width: 1)
                    OdStatTile(label: "Average power", value: container.averagePower.map(String.init) ?? "", unit: "W", valueColor: color, isEmpty: container.averagePower == nil)
                }
            }
        }
    }

    private func workoutCard(_ w: WorkoutProgress) -> some View {
        let steps = w.steps.map { step -> ProfileStepData in
            let s = step.startTargetFraction?.doubleValue ?? 0.5
            let e = step.endTargetFraction?.doubleValue ?? s
            let z = Int(PowerZone.Companion.shared.of(powerWatts: Int32(((s + e) * 0.5 * Double(container.snapshot.ftp)).rounded()), ftp: container.snapshot.ftp).number)
            return ProfileStepData(durationSeconds: Int(step.durationSeconds), startPctFtp: s, endPctFtp: e, isFreeRide: step.isFreeRide, isHeartRate: step.isHeartRate, zone: z)
        }
        return OdCard {
            VStack(alignment: .leading, spacing: OverdriveDimens.spaceS3) {
                HStack(alignment: .top) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Interval").odStyle(OverdriveTypography.label, color: OverdriveColors.textTertiary)
                        Text(w.stepLabel).odStyle(OverdriveTypography.title, color: OverdriveColors.textPrimary)
                    }
                    Spacer()
                    if let next = w.nextStep {
                        VStack(alignment: .trailing, spacing: 2) {
                            Text("Next").odStyle(OverdriveTypography.label, color: OverdriveColors.textTertiary)
                            Text("\(next.label) \u{00B7} \(UiFormat.minutesAndSeconds(Int(next.durationSeconds)))")
                                .odStyle(OverdriveTypography.bodyStrong, color: OverdriveColors.textSecondary)
                        }
                        .padding(.trailing, OverdriveDimens.spaceS6)
                    }
                    VStack(alignment: .trailing, spacing: 2) {
                        Text("Left in interval").odStyle(OverdriveTypography.label, color: OverdriveColors.textTertiary)
                        Text(UiFormat.minutesAndSeconds(Int(w.stepRemainingSeconds)))
                            .odStyle(OverdriveTypography.numL, color: OverdriveColors.textPrimary)
                    }
                }

                WorkoutProfileView(
                    data: WorkoutProfileData(steps: steps),
                    elapsedSeconds: Int(w.workoutElapsedSeconds),
                    trace: container.trace
                )
                .frame(height: 156)
            }
        }
    }

    private var freeRideCard: some View {
        let gear = container.snapshot.gear?.intValue ?? Int(container.snapshot.neutralGear)
        return OdCard {
            HStack(spacing: OverdriveDimens.spaceS8) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Gear").odStyle(OverdriveTypography.label, color: OverdriveColors.textTertiary)
                    Text("\(gear) / \(container.snapshot.gearCount)")
                        .odStyle(OverdriveTypography.numL, color: isPaused ? OverdriveColors.textTertiary : OverdriveColors.textPrimary)
                }
                if let route = container.snapshot.route {
                    Rectangle().fill(OverdriveColors.border).frame(width: 1, height: 64)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(route.name).odStyle(OverdriveTypography.label, color: OverdriveColors.textTertiary)
                        Text(String(format: "%.1f%% grade", route.gradePercent))
                            .odStyle(OverdriveTypography.numL, color: OverdriveColors.textPrimary)
                    }
                }
                Spacer()
            }
        }
    }

    private var controlBar: some View {
        HStack(spacing: OverdriveDimens.spaceS3) {
            if isPaused {
                OdButton("Finish ride", icon: .flag, variant: .secondary, height: OverdriveDimens.sizeButtonHeightRide) {
                    confirmFinish = true
                }
                Spacer()
                OdButton("Resume", icon: .play, variant: .primary, height: OverdriveDimens.sizeButtonHeightRide, minWidth: 200) {
                    container.resumeRide()
                }
            } else {
                // Left: Ride controls mirroring Click actions 1:1
                if container.snapshot.workout != nil {
                    OdButton(icon: .skipBack, variant: .secondary, height: OverdriveDimens.sizeButtonHeightRide) {
                        container.dispatchAction(.previousInterval)
                    }
                }
                OdButton(icon: .minus, variant: .secondary, height: OverdriveDimens.sizeButtonHeightRide) {
                    container.dispatchAction(.gearDown)
                }
                VStack(spacing: 2) {
                    Text(container.snapshot.mode == .erg ? "\(container.snapshot.intensityPercent)%" : "\(container.snapshot.gear?.intValue ?? Int(container.snapshot.neutralGear))")
                        .odStyle(OverdriveTypography.numS, color: OverdriveColors.textPrimary)
                    Text(container.snapshot.mode == .erg ? "Intensity" : "Gear")
                        .odStyle(OverdriveTypography.caption, color: OverdriveColors.textTertiary)
                }
                .frame(width: 84)
                OdButton(icon: .plus, variant: .secondary, height: OverdriveDimens.sizeButtonHeightRide) {
                    container.dispatchAction(.gearUp)
                }
                if container.snapshot.workout != nil {
                    OdButton(icon: .skipForward, variant: .secondary, height: OverdriveDimens.sizeButtonHeightRide) {
                        container.dispatchAction(.nextInterval)
                    }
                }

                Spacer()

                // Right: Media controls + Live Coach mute + Pause
                if case .on(let muted) = container.liveCoach.overlayState.badge {
                    OdButton(muted ? "Coach muted" : "Mute coach", variant: .secondary, height: 48, enabled: !muted) {
                        container.liveCoach.mute()
                    }
                }

                HStack(spacing: OverdriveDimens.spaceS1) {
                    OdButton(icon: .play, variant: .ghost, height: 52) {
                        container.dispatchAction(.playPauseMedia)
                    }
                    OdButton(icon: .volume1, variant: .ghost, height: 52) {
                        container.dispatchAction(.volumeDown)
                    }
                    OdButton(icon: .volume2, variant: .ghost, height: 52) {
                        container.dispatchAction(.volumeUp)
                    }
                }

                Rectangle()
                    .fill(OverdriveColors.border)
                    .frame(width: 1, height: 40)

                OdButton("Pause", icon: .pause, variant: .secondary, height: OverdriveDimens.sizeButtonHeightRide) {
                    container.pauseRide()
                }
            }
        }
        .padding(.horizontal, OverdriveDimens.spaceS6)
        .frame(height: 96)
        .background(OverdriveColors.bg)
    }
}
