import SwiftUI

/// Finished ride summary screen (design/system.md §6.4).
public struct FinishedView: View {
    @ObservedObject public var container: AppContainer
    public let summary: FinishedRideSummary

    public init(container: AppContainer, summary: FinishedRideSummary) {
        self.container = container
        self.summary = summary
    }

    public var body: some View {
        HStack(alignment: .top, spacing: OverdriveDimens.spaceS6) {
            // Left: Summary tiles + Time in zones + Save status & actions
            VStack(alignment: .leading, spacing: OverdriveDimens.spaceS5) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(summary.title).odStyle(OverdriveTypography.title, color: OverdriveColors.textPrimary)
                    Text(dateRangeText).odStyle(OverdriveTypography.body, color: OverdriveColors.textSecondary)
                }

                OdCard(padding: 0) {
                    VStack(spacing: 0) {
                        HStack(spacing: 0) {
                            OdStatTile(label: "Duration", value: UiFormat.elapsed(Int(summary.summary.durationSeconds)), style: OverdriveTypography.numS)
                            Rectangle().fill(OverdriveColors.border).frame(width: 1)
                            OdStatTile(label: "Moving time", value: UiFormat.elapsed(Int(summary.summary.movingSeconds)), style: OverdriveTypography.numS)
                            Rectangle().fill(OverdriveColors.border).frame(width: 1)
                            OdStatTile(label: "Average power", value: summary.summary.averagePower.map { "\(Int($0.doubleValue.rounded()))" } ?? "", unit: "W", style: OverdriveTypography.numS, isEmpty: summary.summary.averagePower == nil)
                            Rectangle().fill(OverdriveColors.border).frame(width: 1)
                            OdStatTile(label: "Normalised", value: summary.summary.normalizedPower.map { "\(Int($0.doubleValue.rounded()))" } ?? "", unit: "W", style: OverdriveTypography.numS, isEmpty: summary.summary.normalizedPower == nil)
                        }
                        OdDivider()
                        HStack(spacing: 0) {
                            OdStatTile(label: "Average HR", value: summary.summary.averageHeartRate.map { "\(Int($0.doubleValue.rounded()))" } ?? "", unit: "bpm", style: OverdriveTypography.numS, isEmpty: summary.summary.averageHeartRate == nil)
                            Rectangle().fill(OverdriveColors.border).frame(width: 1)
                            OdStatTile(label: "Max HR", value: summary.summary.maxHeartRate.map { "\($0.intValue)" } ?? "", unit: "bpm", style: OverdriveTypography.numS, isEmpty: summary.summary.maxHeartRate == nil)
                            Rectangle().fill(OverdriveColors.border).frame(width: 1)
                            OdStatTile(label: "Work", value: String(format: "%.0f", summary.summary.workKilojoules), unit: "kJ", style: OverdriveTypography.numS)
                            Rectangle().fill(OverdriveColors.border).frame(width: 1)
                            OdStatTile(label: "Load", value: summary.summary.trainingStressScore.map { String(format: "%.0f", $0.doubleValue) } ?? "", style: OverdriveTypography.numS, isEmpty: summary.summary.trainingStressScore == nil)
                        }
                    }
                }

                OdCard {
                    VStack(alignment: .leading, spacing: OverdriveDimens.spaceS3) {
                        Text("Time in power zones").odStyle(OverdriveTypography.heading, color: OverdriveColors.textPrimary)
                        ZoneTimeBarsView(zoneSeconds: summary.zoneSeconds)
                    }
                }

                Spacer()

                saveFooter
            }

            // Right: Laps table (440 pt)
            OdCard(padding: 0) {
                VStack(spacing: 0) {
                    HStack {
                        Text("Lap").odStyle(OverdriveTypography.caption, color: OverdriveColors.textTertiary).frame(width: 36, alignment: .leading)
                        Text("Name").odStyle(OverdriveTypography.caption, color: OverdriveColors.textTertiary)
                        Spacer()
                        Text("Time").odStyle(OverdriveTypography.caption, color: OverdriveColors.textTertiary).frame(width: 58, alignment: .trailing)
                        Text("Watts").odStyle(OverdriveTypography.caption, color: OverdriveColors.textTertiary).frame(width: 54, alignment: .trailing)
                        Text("HR").odStyle(OverdriveTypography.caption, color: OverdriveColors.textTertiary).frame(width: 44, alignment: .trailing)
                    }
                    .padding(.horizontal, OverdriveDimens.spaceS4)
                    .frame(height: 36)
                    OdDivider()
                    ScrollView {
                        VStack(spacing: 0) {
                            ForEach(summary.laps, id: \.number) { lap in
                                HStack {
                                    Text("\(lap.number)").odStyle(OverdriveTypography.numXS, color: OverdriveColors.textTertiary).frame(width: 36, alignment: .leading)
                                    Text(lap.name).odStyle(OverdriveTypography.body, color: OverdriveColors.textPrimary).lineLimit(1)
                                    Spacer()
                                    Text(UiFormat.minutesAndSeconds(lap.durationSeconds)).odStyle(OverdriveTypography.numXS, color: OverdriveColors.textSecondary).frame(width: 58, alignment: .trailing)
                                    Text(lap.avgPower.map(String.init) ?? UiFormat.emptyDash).odStyle(OverdriveTypography.numXS, color: OverdriveColors.textPrimary).frame(width: 54, alignment: .trailing)
                                    Text(lap.avgHr.map(String.init) ?? UiFormat.emptyDash).odStyle(OverdriveTypography.numXS, color: OverdriveColors.textSecondary).frame(width: 44, alignment: .trailing)
                                }
                                .padding(.horizontal, OverdriveDimens.spaceS4)
                                .frame(height: 36)
                                OdDivider()
                            }
                        }
                    }
                }
            }
            .frame(width: 440)
        }
        .padding(OverdriveDimens.spaceS6)
    }

    private var saveFooter: some View {
        VStack(alignment: .leading, spacing: OverdriveDimens.spaceS3) {
            switch summary.saveState {
            case .saving:
                HStack(spacing: OverdriveDimens.spaceS2) {
                    OdSpinner(size: 16)
                    Text("Saving the ride\u{2026}").odStyle(OverdriveTypography.label, color: OverdriveColors.textSecondary)
                }
            case .saved(let meta):
                HStack(spacing: OverdriveDimens.spaceS2) {
                    OdIcon(.check, size: 16, color: OverdriveColors.textPrimary)
                    Text("Saved on this Mac \u{00B7} \(container.rideStore.suggestedFitFilename(title: meta.title, startEpochMillis: meta.startEpochMillis)) \u{00B7} \(UiFormat.fileSize(meta.fitBytes))")
                        .odStyle(OverdriveTypography.label, color: OverdriveColors.textSecondary)
                }
            case .failed(let msg):
                HStack(spacing: OverdriveDimens.spaceS2) {
                    OdIcon(.triangleAlert, size: 16, color: OverdriveColors.textPrimary)
                    Text("The ride could not be saved: \(msg)")
                        .odStyle(OverdriveTypography.label, color: OverdriveColors.textPrimary)
                }
            }

            if let msg = container.lastSaveMessage {
                Text(msg).odStyle(OverdriveTypography.label, color: OverdriveColors.textSecondary)
            }

            HStack(spacing: OverdriveDimens.spaceS3) {
                if case .saved(let meta) = summary.saveState {
                    OdButton("Save to folder", icon: .folderOpen, variant: .secondary, height: 52) {
                        container.saveFitToFolder(meta)
                    }
                    OdButton("Reveal in Finder", icon: .share2, variant: .secondary, height: 52) {
                        container.revealInFinder(meta.fitURL)
                    }
                }
                Spacer()
                OdButton("Done", variant: .primary, height: 52, minWidth: 160) {
                    container.dismissFinishedRide()
                }
            }
        }
    }

    private var dateRangeText: String {
        let start = Date(timeIntervalSince1970: TimeInterval(summary.startEpochMillis) / 1000.0)
        let df = DateFormatter()
        df.dateStyle = .medium
        df.timeStyle = .short
        return df.string(from: start)
    }
}
