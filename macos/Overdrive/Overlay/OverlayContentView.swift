import SwiftUI

public enum OverlayMode: String, Codable, Sendable, CaseIterable {
    case compact
    case detailed
}

public enum OverlayFifthCell: Equatable, Sendable {
    case gear(current: Int, total: Int)
    case target(watts: Int, intensityPercent: Int?)
    case cadenceGuard(watts: Int)
    case none
}

public struct OverlayHeartRateHold: Equatable, Sendable {
    public let targetBpm: Int
    public let deltaBpm: Int
    public let bandBpm: Int
    public let currentBpm: Int?
    public let statusText: String
    public let showGauge: Bool

    public init(
        targetBpm: Int,
        deltaBpm: Int = 0,
        bandBpm: Int = 5,
        currentBpm: Int? = nil,
        statusText: String,
        showGauge: Bool
    ) {
        self.targetBpm = targetBpm
        self.deltaBpm = deltaBpm
        self.bandBpm = bandBpm
        self.currentBpm = currentBpm
        self.statusText = statusText
        self.showGauge = showGauge
    }
}

public struct OverlayWorkoutInfo: Equatable, Sendable {
    public let intervalName: String
    public let repeatBadge: String?
    public let stepRemainingSeconds: Int
    public let nextSummary: String
    public let previousAttemptLine: String?
    public let elapsedSeconds: Int
    public let profile: WorkoutProfileData

    public init(
        intervalName: String,
        repeatBadge: String? = nil,
        stepRemainingSeconds: Int,
        nextSummary: String,
        previousAttemptLine: String? = nil,
        elapsedSeconds: Int,
        profile: WorkoutProfileData
    ) {
        self.intervalName = intervalName
        self.repeatBadge = repeatBadge
        self.stepRemainingSeconds = stepRemainingSeconds
        self.nextSummary = nextSummary
        self.previousAttemptLine = previousAttemptLine
        self.elapsedSeconds = elapsedSeconds
        self.profile = profile
    }
}

public struct OverlayUiState: Equatable, Sendable {
    public let power: Int?
    public let zone: Int?
    public let heartRate: Int?
    public let cadence: Int?
    public let elapsedSeconds: Int
    public let speedKmh: Double
    public let distanceMeters: Double
    public let paused: Bool
    public let fifthCell: OverlayFifthCell
    public let hold: OverlayHeartRateHold?
    public let workout: OverlayWorkoutInfo?

    public static let sample = OverlayUiState(
        power: 222,
        zone: 3,
        heartRate: 152,
        cadence: 89,
        elapsedSeconds: 1937,
        speedKmh: 34.6,
        distanceMeters: 18420,
        paused: false,
        fifthCell: .target(watts: 220, intensityPercent: 100),
        hold: nil,
        workout: nil
    )

    public init(
        power: Int?,
        zone: Int?,
        heartRate: Int?,
        cadence: Int?,
        elapsedSeconds: Int,
        speedKmh: Double,
        distanceMeters: Double,
        paused: Bool,
        fifthCell: OverlayFifthCell,
        hold: OverlayHeartRateHold? = nil,
        workout: OverlayWorkoutInfo? = nil
    ) {
        self.power = power
        self.zone = zone
        self.heartRate = heartRate
        self.cadence = cadence
        self.elapsedSeconds = elapsedSeconds
        self.speedKmh = speedKmh
        self.distanceMeters = distanceMeters
        self.paused = paused
        self.fifthCell = fifthCell
        self.hold = hold
        self.workout = workout
    }
}

public enum OverlayCoachBadge: Equatable, Sendable {
    case off
    case unavailable
    case on(muted: Bool)
}

public struct OverlayCoachState: Equatable, Sendable {
    public let badge: OverlayCoachBadge
    public let plateText: String?

    public static let none = OverlayCoachState(badge: .off, plateText: nil)

    public init(badge: OverlayCoachBadge, plateText: String? = nil) {
        self.badge = badge
        self.plateText = plateText
    }
}

public struct OverlayLook: Equatable, Sendable {
    public let mode: OverlayMode
    public let opacity: Double
    public let blurEnabled: Bool

    public init(mode: OverlayMode = .compact, opacity: Double = OverdriveDimens.overlayOpacityDefault, blurEnabled: Bool = false) {
        self.mode = mode
        self.opacity = opacity
        self.blurEnabled = blurEnabled
    }
}

/// Separate observable models so metrics, coach cues, and visual settings never trigger unrelated invalidations.
@MainActor
public final class OverlayViewModel: ObservableObject {
    @Published public var metrics: OverlayUiState = .sample
    @Published public var coach: OverlayCoachState = .none
    @Published public var look: OverlayLook = OverlayLook()
    public var muteControlFrameInWindow: CGRect = .zero

    public init() {}
}

/// The floating HUD content (design/system.md §5.1 – §5.7). Zero animations at rest.
public struct OverlayContentView: View {
    @ObservedObject public var model: OverlayViewModel
    public var onMuteFrameChanged: ((CGRect) -> Void)?

    public init(model: OverlayViewModel, onMuteFrameChanged: ((CGRect) -> Void)? = nil) {
        self.model = model
        self.onMuteFrameChanged = onMuteFrameChanged
    }

    public var body: some View {
        VStack(alignment: .leading, spacing: OverdriveDimens.overlayCoachGap) {
            panelBody
            coachStack
        }
        .frame(width: OverdriveDimens.overlayCompactWidth, alignment: .leading)
        .fixedSize(horizontal: true, vertical: true)
    }

    private var panelBody: some View {
        VStack(alignment: .leading, spacing: OverdriveDimens.overlayDetailedSectionGap) {
            VStack(alignment: .leading, spacing: 4) {
                mainRow
                if let hold = model.metrics.hold {
                    holdLine(hold)
                }
            }
            if model.look.mode == .detailed {
                OdDivider()
                speedDistanceRow
                if let workout = model.metrics.workout {
                    OdDivider()
                    workoutSection(workout)
                }
            }
        }
        .padding(.horizontal, OverdriveDimens.overlayPaddingHorizontal)
        .padding(.vertical, OverdriveDimens.overlayPaddingVertical)
        .frame(width: OverdriveDimens.overlayCompactWidth, alignment: .leading)
        .background(OverdriveColors.overlayBacking.opacity(model.look.opacity))
        .overlay(
            RoundedRectangle(cornerRadius: OverdriveDimens.overlayRadius)
                .stroke(OverdriveColors.overlayBorder, lineWidth: OverdriveDimens.strokeHairline)
        )
        .clipShape(RoundedRectangle(cornerRadius: OverdriveDimens.overlayRadius))
    }

    private var mainRow: some View {
        let m = model.metrics
        let dim = m.paused
        return HStack(alignment: .lastTextBaseline, spacing: OverdriveDimens.overlayCellGap) {
            // Power cell (92 pt)
            VStack(alignment: .leading, spacing: OverdriveDimens.overlayLabelValueGap) {
                HStack(spacing: 6) {
                    Text("Power").odStyle(OverdriveTypography.caption, color: OverdriveColors.textTertiary)
                    if !dim, let z = m.zone {
                        Text("Z\(z)").odStyle(OverdriveTypography.caption, color: OverdriveColors.textSecondary)
                    }
                }
                Text(m.power.map(String.init) ?? UiFormat.emptyDash)
                    .odStyle(
                        OverdriveTypography.numM,
                        color: dim || m.power == nil ? OverdriveColors.textTertiary : OverdriveColors.zone(m.zone ?? 1)
                    )
                    .lineLimit(1)
            }
            .frame(width: OverdriveDimens.overlaySlotPower, alignment: .leading)

            // Heart rate cell (50 pt)
            metricCell(
                label: "Heart",
                value: m.heartRate.map(String.init),
                width: OverdriveDimens.overlaySlotHeartRate,
                dim: dim
            )

            // Cadence cell (50 pt)
            metricCell(
                label: "Cadence",
                value: m.cadence.map(String.init),
                width: OverdriveDimens.overlaySlotCadence,
                dim: dim
            )

            // Elapsed / Paused cell (96 pt)
            VStack(alignment: .leading, spacing: OverdriveDimens.overlayLabelValueGap) {
                Text(dim ? "Paused" : "Time")
                    .odStyle(OverdriveTypography.caption, color: dim ? OverdriveColors.textPrimary : OverdriveColors.textTertiary)
                Text(UiFormat.elapsed(m.elapsedSeconds))
                    .odStyle(OverdriveTypography.numS, color: OverdriveColors.textPrimary)
                    .lineLimit(1)
            }
            .frame(width: OverdriveDimens.overlaySlotElapsed, alignment: .leading)

            // Fifth cell: Gear / Target / Cadence guard (80 pt)
            fifthCellView(m.fifthCell, dim: dim)
        }
    }

    private func metricCell(label: String, value: String?, width: CGFloat, dim: Bool) -> some View {
        VStack(alignment: .leading, spacing: OverdriveDimens.overlayLabelValueGap) {
            Text(label).odStyle(OverdriveTypography.caption, color: OverdriveColors.textTertiary)
            Text(value ?? UiFormat.emptyDash)
                .odStyle(OverdriveTypography.numS, color: dim || value == nil ? OverdriveColors.textTertiary : OverdriveColors.textPrimary)
                .lineLimit(1)
        }
        .frame(width: width, alignment: .leading)
    }

    @ViewBuilder
    private func fifthCellView(_ cell: OverlayFifthCell, dim: Bool) -> some View {
        switch cell {
        case .gear(let current, _):
            metricCell(label: "Gear", value: "\(current)", width: OverdriveDimens.overlaySlotGearOrTarget, dim: dim)
        case .target(let watts, let intensity):
            VStack(alignment: .leading, spacing: OverdriveDimens.overlayLabelValueGap) {
                HStack(spacing: 4) {
                    Text("Target").odStyle(OverdriveTypography.caption, color: OverdriveColors.textTertiary)
                    if let intensity {
                        Text("\(intensity)%").odStyle(OverdriveTypography.caption, color: OverdriveColors.textSecondary)
                    }
                }
                Text("\(watts)")
                    .odStyle(OverdriveTypography.numS, color: dim ? OverdriveColors.textTertiary : OverdriveColors.textPrimary)
                    .lineLimit(1)
            }
            .frame(width: OverdriveDimens.overlaySlotGearOrTarget, alignment: .leading)
        case .cadenceGuard(let watts):
            VStack(alignment: .leading, spacing: OverdriveDimens.overlayLabelValueGap) {
                Text("Cadence guard")
                    .odStyle(OverdriveTypography.caption, color: OverdriveColors.textPrimary)
                    .lineLimit(1)
                Text("\(watts)")
                    .odStyle(OverdriveTypography.numS, color: dim ? OverdriveColors.textTertiary : OverdriveColors.textPrimary)
                    .lineLimit(1)
            }
            .frame(width: 96, alignment: .leading)
        case .none:
            metricCell(label: "Gear", value: nil, width: OverdriveDimens.overlaySlotGearOrTarget, dim: dim)
        }
    }

    private func holdLine(_ hold: OverlayHeartRateHold) -> some View {
        HStack(spacing: OverdriveDimens.spaceS2) {
            if hold.showGauge, let hr = hold.currentBpm {
                hrGauge(hr: hr, target: hold.targetBpm, band: hold.bandBpm)
            }
            Text(hold.statusText)
                .odStyle(
                    OverdriveTypography.label,
                    color: model.metrics.paused ? OverdriveColors.textTertiary : OverdriveColors.textSecondary
                )
                .lineLimit(1)
        }
        .frame(height: 20, alignment: .leading)
    }

    private func hrGauge(hr: Int, target: Int, band: Int) -> some View {
        let width: CGFloat = 72
        let halfRange = max(2.0, Double(band * 2))
        let norm = min(1.0, max(0.0, (Double(hr - target) / halfRange + 1.0) / 2.0))
        return ZStack(alignment: .leading) {
            RoundedRectangle(cornerRadius: 4)
                .fill(OverdriveColors.surface3)
                .frame(width: width, height: 8)
            RoundedRectangle(cornerRadius: 2)
                .fill(OverdriveColors.borderStrong)
                .frame(width: width / 2.0, height: 8)
                .offset(x: width / 4.0)
            Rectangle()
                .fill(OverdriveColors.textPrimary)
                .frame(width: 3, height: 8)
                .offset(x: max(0, min(width - 3, CGFloat(norm) * (width - 3))))
        }
        .frame(width: width, height: 8)
    }

    private var speedDistanceRow: some View {
        let m = model.metrics
        let dim = m.paused
        return HStack(alignment: .lastTextBaseline, spacing: OverdriveDimens.overlayCellGap) {
            VStack(alignment: .leading, spacing: OverdriveDimens.overlayLabelValueGap) {
                Text("Speed").odStyle(OverdriveTypography.caption, color: OverdriveColors.textTertiary)
                HStack(alignment: .firstTextBaseline, spacing: 4) {
                    Text(UiFormat.speed(m.speedKmh))
                        .odStyle(OverdriveTypography.numS, color: dim ? OverdriveColors.textTertiary : OverdriveColors.textPrimary)
                    Text("km/h").odStyle(OverdriveTypography.label, color: OverdriveColors.textSecondary)
                }
            }
            .frame(width: OverdriveDimens.overlaySlotPower + 30, alignment: .leading)

            VStack(alignment: .leading, spacing: OverdriveDimens.overlayLabelValueGap) {
                Text("Distance").odStyle(OverdriveTypography.caption, color: OverdriveColors.textTertiary)
                HStack(alignment: .firstTextBaseline, spacing: 4) {
                    Text(UiFormat.distanceKm(m.distanceMeters))
                        .odStyle(OverdriveTypography.numS, color: dim ? OverdriveColors.textTertiary : OverdriveColors.textPrimary)
                    Text("km").odStyle(OverdriveTypography.label, color: OverdriveColors.textSecondary)
                }
            }
            Spacer()
        }
    }

    private func workoutSection(_ w: OverlayWorkoutInfo) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline) {
                HStack(spacing: 6) {
                    Text(w.intervalName)
                        .odStyle(OverdriveTypography.bodyStrong, color: OverdriveColors.textPrimary)
                        .lineLimit(1)
                    if let badge = w.repeatBadge {
                        Text(badge).odStyle(OverdriveTypography.body, color: OverdriveColors.textSecondary)
                    }
                }
                Spacer()
                HStack(alignment: .firstTextBaseline, spacing: 4) {
                    Text(UiFormat.minutesAndSeconds(w.stepRemainingSeconds))
                        .odStyle(OverdriveTypography.numS, color: OverdriveColors.textPrimary)
                    Text("left").odStyle(OverdriveTypography.label, color: OverdriveColors.textSecondary)
                }
            }
            HStack(spacing: 6) {
                Text("Next").odStyle(OverdriveTypography.label, color: OverdriveColors.textTertiary)
                Text(w.nextSummary).odStyle(OverdriveTypography.label, color: OverdriveColors.textSecondary).lineLimit(1)
            }
            if let prev = w.previousAttemptLine {
                Text(prev)
                    .odStyle(OverdriveTypography.label, color: OverdriveColors.textSecondary)
                    .lineLimit(1)
            }
            WorkoutProfileView(
                data: w.profile,
                elapsedSeconds: w.elapsedSeconds,
                showFtpLine: false,
                showAxisTicks: false,
                surfaceRGB: (0x06 / 255.0, 0x08 / 255.0, 0x0B / 255.0)
            )
            .frame(height: OverdriveDimens.overlayDetailedProfileHeight)
            .padding(.top, 4)
        }
    }

    @ViewBuilder
    private var coachStack: some View {
        switch model.coach.badge {
        case .off:
            EmptyView()
        case .unavailable:
            Text("Live coach unavailable")
                .odStyle(OverdriveTypography.label, color: OverdriveColors.textSecondary)
                .padding(.horizontal, OverdriveDimens.spaceS4)
                .frame(height: OverdriveDimens.overlayCoachControlHeight)
                .background(OverdriveColors.overlayBacking.opacity(model.look.opacity))
                .overlay(
                    RoundedRectangle(cornerRadius: OverdriveDimens.overlayRadius)
                        .stroke(OverdriveColors.overlayBorder, lineWidth: OverdriveDimens.strokeHairline)
                )
                .clipShape(RoundedRectangle(cornerRadius: OverdriveDimens.overlayRadius))
        case .on(let muted):
            VStack(alignment: .leading, spacing: OverdriveDimens.overlayCoachGap) {
                Text(muted ? "Coach muted" : "Coach on")
                    .odStyle(OverdriveTypography.label, color: muted ? OverdriveColors.textPrimary : OverdriveColors.textSecondary)
                    .frame(width: OverdriveDimens.overlayCoachControlWidth, height: OverdriveDimens.overlayCoachControlHeight)
                    .background(OverdriveColors.overlayBacking.opacity(model.look.opacity))
                    .overlay(
                        RoundedRectangle(cornerRadius: OverdriveDimens.overlayRadius)
                            .stroke(OverdriveColors.overlayBorder, lineWidth: OverdriveDimens.strokeHairline)
                    )
                    .clipShape(RoundedRectangle(cornerRadius: OverdriveDimens.overlayRadius))
                    .background(
                        GeometryReader { geo in
                            Color.clear.onAppear {
                                onMuteFrameChanged?(geo.frame(in: .named("overlayRoot")))
                            }
                            .onChange(of: geo.frame(in: .named("overlayRoot"))) { _, rect in
                                onMuteFrameChanged?(rect)
                            }
                        }
                    )

                if let plate = model.coach.plateText, !muted {
                    Text(plate)
                        .odStyle(OverdriveTypography.body, color: OverdriveColors.textPrimary)
                        .lineLimit(OverdriveDimens.overlayCoachPlateMaxLines)
                        .padding(.horizontal, OverdriveDimens.overlayPaddingHorizontal)
                        .padding(.vertical, OverdriveDimens.overlayCoachPlateVertical)
                        .frame(width: OverdriveDimens.overlayCompactWidth, alignment: .leading)
                        .background(OverdriveColors.overlayBacking.opacity(model.look.opacity))
                        .overlay(
                            RoundedRectangle(cornerRadius: OverdriveDimens.overlayRadius)
                                .stroke(OverdriveColors.overlayBorder, lineWidth: OverdriveDimens.strokeHairline)
                        )
                        .clipShape(RoundedRectangle(cornerRadius: OverdriveDimens.overlayRadius))
                }
            }
        }
    }
}
