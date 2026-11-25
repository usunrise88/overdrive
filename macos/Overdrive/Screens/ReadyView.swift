import AppKit
import OverdriveCore
import SwiftUI
import UniformTypeIdentifiers

/// Ready screen (design/system.md §6.1): left workout/plan column (360 pt) + right detail, profile, and Start column.
public struct ReadyView: View {
    @ObservedObject public var container: AppContainer
    @State private var showThisWeek = false
    @State private var showRouteChooser = false
    @State private var pendingDiscard: InterruptedRideCandidate?

    public init(container: AppContainer) {
        self.container = container
    }

    public var body: some View {
        HStack(spacing: 0) {
            leftColumn
                .frame(width: 360)
            Rectangle()
                .fill(OverdriveColors.border)
                .frame(width: OverdriveDimens.strokeHairline)
            rightColumn
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .onDrop(of: [.fileURL], isTargeted: nil) { providers in
            guard let first = providers.first else { return false }
            _ = first.loadObject(ofClass: URL.self) { url, _ in
                if let url {
                    Task { @MainActor in container.importWorkoutFile(url: url) }
                }
            }
            return true
        }
        .sheet(isPresented: $showRouteChooser) {
            routeChooserSheet
        }
        .alert("Discard this ride?", isPresented: Binding(get: { pendingDiscard != nil }, set: { if !$0 { pendingDiscard = nil } })) {
            Button("Discard", role: .destructive) {
                if let cand = pendingDiscard { container.discardInterrupted(cand) }
                pendingDiscard = nil
            }
            Button("Keep it", role: .cancel) { pendingDiscard = nil }
        } message: {
            Text("The recovered samples will be permanently deleted.")
        }
    }

    // MARK: - Left Column (Planned + Free ride + Local workouts)

    private var leftColumn: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: OverdriveDimens.spaceS4) {
                    if container.planState.visible {
                        plannedSection
                    }

                    Text("Ride")
                        .odStyle(OverdriveTypography.label, color: OverdriveColors.textTertiary)
                        .padding(.horizontal, OverdriveDimens.spaceS2)

                    VStack(spacing: OverdriveDimens.spaceS1) {
                        selectableRow(
                            title: "Free ride",
                            subtitle: selectedFreeRouteName ?? "Flat road with virtual gears",
                            selected: container.choice == .free
                        ) {
                            container.selectChoice(.free)
                        }

                        ForEach(container.localWorkouts, id: \.id) { item in
                            selectableRow(
                                title: item.workout.name,
                                subtitle: item.workout.description_ ?? "\(UiFormat.durationWords(Int(AppleCoreFactories.shared.workoutDurationSeconds(workout: item.workout))))",
                                selected: container.choice == .local(id: item.id, workout: item.workout)
                            ) {
                                container.selectChoice(.local(id: item.id, workout: item.workout))
                            }
                        }
                    }
                }
                .padding(OverdriveDimens.spaceS4)
            }

            OdDivider()
            HStack {
                OdButton("Open workout file", icon: .folderOpen, variant: .ghost, height: 44) {
                    let panel = NSOpenPanel()
                    panel.allowedContentTypes = [UTType.json, UTType(filenameExtension: "zwo") ?? .xml]
                    panel.allowsMultipleSelection = false
                    if panel.runModal() == .OK, let url = panel.url {
                        container.importWorkoutFile(url: url)
                    }
                }
                Spacer()
            }
            .padding(.horizontal, OverdriveDimens.spaceS4)
            .padding(.vertical, OverdriveDimens.spaceS3)
        }
    }

    private var plannedSection: some View {
        let today = todayString()
        let todayWorkouts = container.planState.workouts.filter { $0.date == today }
        let laterWorkouts = container.planState.workouts.filter { $0.date > today }

        return VStack(alignment: .leading, spacing: OverdriveDimens.spaceS2) {
            HStack {
                Text("Planned").odStyle(OverdriveTypography.label, color: OverdriveColors.textTertiary)
                Spacer()
                OdButton(icon: .refreshCw, variant: .ghost, height: 32, enabled: !container.planState.refreshing) {
                    container.refreshRemote(reason: .requested)
                }
            }
            .padding(.horizontal, OverdriveDimens.spaceS2)

            if container.planState.refreshing {
                Text("Updating\u{2026}")
                    .odStyle(OverdriveTypography.label, color: OverdriveColors.textSecondary)
                    .padding(.horizontal, OverdriveDimens.spaceS2)
            } else if let problem = container.planState.problem {
                HStack(spacing: 6) {
                    OdIcon(.triangleAlert, size: 14, color: OverdriveColors.textPrimary)
                    Text(problem is PlanProblemTokenRejected ? "The service did not accept the token." : "Out of date")
                        .odStyle(OverdriveTypography.label, color: OverdriveColors.textPrimary)
                }
                .padding(.horizontal, OverdriveDimens.spaceS2)
            }

            if todayWorkouts.isEmpty {
                Text("Nothing planned today")
                    .odStyle(OverdriveTypography.body, color: OverdriveColors.textSecondary)
                    .padding(.horizontal, OverdriveDimens.spaceS2)
            } else {
                ForEach(todayWorkouts, id: \.id) { pw in
                    plannedWorkoutRows(pw, isToday: true)
                }
            }

            if !laterWorkouts.isEmpty {
                Button {
                    showThisWeek.toggle()
                } label: {
                    HStack {
                        Text("This week").odStyle(OverdriveTypography.bodyStrong, color: OverdriveColors.textPrimary)
                        Spacer()
                        Text("\(laterWorkouts.count) workouts").odStyle(OverdriveTypography.label, color: OverdriveColors.textSecondary)
                        OdIcon(showThisWeek ? .chevronDown : .chevronRight, size: 18, color: OverdriveColors.textSecondary)
                    }
                    .padding(.horizontal, OverdriveDimens.spaceS3)
                    .frame(height: 44)
                }
                .buttonStyle(.plain)

                if showThisWeek {
                    ForEach(laterWorkouts, id: \.id) { pw in
                        plannedWorkoutRows(pw, isToday: false)
                    }
                }
            }
            OdDivider().padding(.top, OverdriveDimens.spaceS2)
        }
    }

    @ViewBuilder
    private func plannedWorkoutRows(_ pw: PlannedWorkout, isToday: Bool) -> some View {
        let coachTag = pw.workout.liveCoach == true ? (container.settings.liveCoachEnabled ? " \u{00B7} Live coach" : " \u{00B7} Live coach off") : ""
        let subtitle = (pw.workout.description_ ?? pw.date) + coachTag
        selectableRow(
            title: pw.name,
            subtitle: subtitle,
            selected: container.choice == .planned(workout: pw, useVariant: false)
        ) {
            container.selectChoice(.planned(workout: pw, useVariant: false))
        }

        if let variant = pw.variant {
            selectableRow(
                title: "Easier version",
                subtitle: (variant.note ?? "Lower effort alternative") + coachTag,
                selected: container.choice == .planned(workout: pw, useVariant: true)
            ) {
                container.selectChoice(.planned(workout: pw, useVariant: true))
            }
            .padding(.leading, OverdriveDimens.spaceS4)

            if isToday, let rec = container.recoveryState,
               rec.state == .ready || rec.state == .caution || rec.state == .rest {
                Text("Recovery: \(rec.state.name.lowercased())")
                    .odStyle(OverdriveTypography.label, color: OverdriveColors.textSecondary)
                    .padding(.leading, OverdriveDimens.spaceS8)
            }
        }
    }

    private func selectableRow(title: String, subtitle: String, selected: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: OverdriveDimens.spaceS3) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title)
                        .odStyle(OverdriveTypography.bodyStrong, color: OverdriveColors.textPrimary)
                        .lineLimit(1)
                    Text(subtitle)
                        .odStyle(OverdriveTypography.label, color: OverdriveColors.textSecondary)
                        .lineLimit(1)
                }
                Spacer()
                if selected {
                    OdIcon(.check, size: 18, color: OverdriveColors.textPrimary)
                }
            }
            .padding(.horizontal, OverdriveDimens.spaceS3)
            .padding(.vertical, OverdriveDimens.spaceS2)
            .frame(minHeight: 56)
            .background(selected ? OverdriveColors.surface2 : Color.clear)
            .clipShape(RoundedRectangle(cornerRadius: OverdriveDimens.radiusMd))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    // MARK: - Right Column

    private var rightColumn: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: OverdriveDimens.spaceS5) {
                // Bluetooth notice
                if container.settings.deviceSource == .bluetooth && container.bleAvailability != .ready {
                    bluetoothNotice
                }

                // Interrupted ride recovery notice
                if let cand = container.interruptedRides.first {
                    OdNotice(
                        icon: .triangleAlert,
                        title: "An interrupted ride was found",
                        message: "Length \(UiFormat.durationWords(cand.durationSeconds)). Save it as a FIT file or discard it.",
                        primaryAction: ("Save", { container.recoverInterrupted(cand) }),
                        secondaryAction: ("Discard", { pendingDiscard = cand })
                    )
                }

                if let err = container.workoutImportError {
                    OdNotice(icon: .triangleAlert, title: "Workout could not be opened", message: err)
                }

                if let last = container.storedRides.first {
                    lastRideCard(last)
                }

                // Title & Description
                VStack(alignment: .leading, spacing: OverdriveDimens.spaceS2) {
                    Text(container.choice.title)
                        .odStyle(OverdriveTypography.title, color: OverdriveColors.textPrimary)
                    if let desc = activeDescription {
                        Text(desc)
                            .odStyle(OverdriveTypography.body, color: OverdriveColors.textSecondary)
                    }
                }

                // Route chooser row for Free ride
                if container.choice == .free && !container.routesList.isEmpty {
                    routeRow
                }

                // Workout detail stats & profile
                if let workout = container.choice.effectiveWorkout {
                    workoutDetail(workout)
                } else {
                    freeRideStats
                }

                // Start action bar
                HStack(spacing: OverdriveDimens.spaceS4) {
                    OdButton("Start", icon: .play, variant: .primary, height: OverdriveDimens.sizeButtonHeightRide, minWidth: 220, enabled: container.canStartRide) {
                        container.startRide()
                    }
                    startHint
                }
                .padding(.top, OverdriveDimens.spaceS2)
            }
            .padding(OverdriveDimens.spaceS6)
        }
    }

    @ViewBuilder
    private var bluetoothNotice: some View {
        switch container.bleAvailability {
        case .permissionDenied:
            OdNotice(
                icon: .bluetoothOff,
                title: "Allow Bluetooth access",
                message: "Overdrive needs Bluetooth permission in System Settings to connect to your trainer, strap, and Click.",
                primaryAction: ("Open Settings", { container.bleStack.openBluetoothSettings() })
            )
        case .bluetoothOff:
            OdNotice(
                icon: .bluetoothOff,
                title: "Bluetooth is off",
                message: "Turn Bluetooth on in macOS Control Center or System Settings.",
                primaryAction: ("Open Settings", { container.bleStack.openBluetoothSettings() })
            )
        case .unsupported:
            OdNotice(
                icon: .bluetoothOff,
                title: "No Bluetooth LE on this Mac",
                message: "Switch to Simulated devices under Settings, Developer to test without hardware."
            )
        default:
            EmptyView()
        }
    }

    private func lastRideCard(_ ride: StoredRideMeta) -> some View {
        OdCard(padding: OverdriveDimens.spaceS4) {
            HStack(spacing: OverdriveDimens.spaceS4) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Last ride").odStyle(OverdriveTypography.caption, color: OverdriveColors.textTertiary)
                    Text("\(ride.title) \u{00B7} \(UiFormat.durationWords(Int(ride.durationSeconds)))")
                        .odStyle(OverdriveTypography.bodyStrong, color: OverdriveColors.textPrimary)
                    if let msg = container.lastSaveMessage {
                        Text(msg).odStyle(OverdriveTypography.label, color: OverdriveColors.textSecondary)
                    }
                }
                Spacer()
                OdButton("Save to folder", icon: .folderOpen, variant: .secondary, height: 38) {
                    container.saveFitToFolder(ride)
                }
                OdButton("Reveal in Finder", icon: .share2, variant: .ghost, height: 38) {
                    container.revealInFinder(ride.fitURL)
                }
            }
        }
    }

    private var routeRow: some View {
        OdCard(padding: OverdriveDimens.spaceS4) {
            Button {
                showRouteChooser = true
            } label: {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Route").odStyle(OverdriveTypography.bodyStrong, color: OverdriveColors.textPrimary)
                        if let id = container.selectedFreeRouteId,
                           let meta = container.routesList.first(where: { $0.id == id }) {
                            let mapWord = meta.hasCoordinates ? "has a map" : "profile only"
                            Text("\(UiFormat.distanceKm(Double(meta.distanceMeters))) km \u{00B7} \(meta.ascentMeters) m up \u{00B7} \(mapWord)")
                                .odStyle(OverdriveTypography.label, color: OverdriveColors.textSecondary)
                        } else {
                            Text("No hills \u{00B7} 0% grade")
                                .odStyle(OverdriveTypography.label, color: OverdriveColors.textSecondary)
                        }
                    }
                    Spacer()
                    Text(selectedFreeRouteName ?? "Flat road")
                        .odStyle(OverdriveTypography.bodyStrong, color: OverdriveColors.textSecondary)
                    OdIcon(.chevronRight, size: 18, color: OverdriveColors.textSecondary)
                }
            }
            .buttonStyle(.plain)
        }
    }

    private var freeRideStats: some View {
        OdCard(padding: 0) {
            HStack(spacing: 0) {
                OdStatTile(label: "FTP", value: "\(container.effectiveFtp)", unit: "W", style: OverdriveTypography.numS)
                Rectangle().fill(OverdriveColors.border).frame(width: 1)
                OdStatTile(label: "Gears", value: "\(container.settings.gearCount)", unit: "\(String(format: "%.1f", container.settings.gradeStepPercent))%/step", style: OverdriveTypography.numS)
                Rectangle().fill(OverdriveColors.border).frame(width: 1)
                OdStatTile(label: "Mode", value: selectedFreeRouteName != nil ? "Route" : "Flat", style: OverdriveTypography.numS)
            }
        }
    }

    private func workoutDetail(_ workout: Workout) -> some View {
        let flat = AppleCoreFactories.shared.workoutFlatSteps(workout: workout)
        let totalSec = flat.reduce(0) { $0 + Int($1.durationSeconds) }
        let maxPct = flat.compactMap { $0.targetTo?.doubleValue ?? $0.targetFrom?.doubleValue }.max() ?? 0
        let profileSteps = flat.map { step -> ProfileStepData in
            let s = step.targetFrom?.doubleValue ?? 0.5
            let e = step.targetTo?.doubleValue ?? s
            let z = Int(PowerZone.Companion.shared.of(powerWatts: Int32(((s + e) * 0.5 * Double(container.effectiveFtp)).rounded()), ftp: Int32(container.effectiveFtp)).number)
            return ProfileStepData(durationSeconds: Int(step.durationSeconds), startPctFtp: s, endPctFtp: e, isFreeRide: step.mode == .simulation, isHeartRate: step.heartRate != nil, zone: z)
        }

        return VStack(alignment: .leading, spacing: OverdriveDimens.spaceS4) {
            OdCard(padding: 0) {
                HStack(spacing: 0) {
                    OdStatTile(label: "Duration", value: UiFormat.durationWords(totalSec), style: OverdriveTypography.numS)
                    Rectangle().fill(OverdriveColors.border).frame(width: 1)
                    OdStatTile(label: "FTP", value: "\(container.effectiveFtp)", unit: "W", style: OverdriveTypography.numS)
                    Rectangle().fill(OverdriveColors.border).frame(width: 1)
                    OdStatTile(label: "Hardest step", value: maxPct > 0 ? "\(Int((maxPct * 100).rounded()))%" : UiFormat.emptyDash, style: OverdriveTypography.numS, isEmpty: maxPct == 0)
                    Rectangle().fill(OverdriveColors.border).frame(width: 1)
                    OdStatTile(label: "Mode", value: "ERG", style: OverdriveTypography.numS)
                }
            }

            OdCard {
                WorkoutProfileView(data: WorkoutProfileData(steps: profileSteps), elapsedSeconds: 0)
                    .frame(height: 168)
            }

            OdCard(padding: 0) {
                VStack(spacing: 0) {
                    ForEach(Array(flat.enumerated()), id: \.offset) { idx, step in
                        if idx > 0 { OdDivider() }
                        let targetPct = step.targetFrom?.doubleValue
                        let watts = targetPct.map { Int(($0 * Double(container.effectiveFtp)).rounded()) }
                        let zone = watts.map { Int(PowerZone.Companion.shared.of(powerWatts: Int32($0), ftp: Int32(container.effectiveFtp)).number) }
                        HStack(spacing: OverdriveDimens.spaceS3) {
                            if let zone {
                                OdZoneChip(zone: zone)
                            } else {
                                Text(step.heartRate != nil ? "HR" : "Free")
                                    .odStyle(OverdriveTypography.caption, color: OverdriveColors.textSecondary)
                                    .frame(width: 28)
                            }
                            Text(step.label)
                                .odStyle(OverdriveTypography.bodyStrong, color: OverdriveColors.textPrimary)
                                .lineLimit(1)
                            Spacer()
                            Text(UiFormat.minutesAndSeconds(Int(step.durationSeconds)))
                                .odStyle(OverdriveTypography.numXS, color: OverdriveColors.textSecondary)
                                .frame(width: 56, alignment: .trailing)
                            Text(targetPct.map { "\(Int(($0 * 100).rounded()))%" } ?? UiFormat.emptyDash)
                                .odStyle(OverdriveTypography.numXS, color: OverdriveColors.textSecondary)
                                .frame(width: 48, alignment: .trailing)
                            Text(watts.map { "\($0) W" } ?? UiFormat.emptyDash)
                                .odStyle(OverdriveTypography.numXS, color: OverdriveColors.textPrimary)
                                .frame(width: 64, alignment: .trailing)
                        }
                        .padding(.horizontal, OverdriveDimens.spaceS4)
                        .frame(height: 44)
                    }
                }
            }
        }
    }

    @ViewBuilder
    private var startHint: some View {
        if container.canStartRide {
            Text("Or start pedalling. The ride begins on the first pedal stroke.")
                .odStyle(OverdriveTypography.body, color: OverdriveColors.textSecondary)
        } else if container.settings.deviceSource == .bluetooth && container.rememberedDevices.trainer == nil {
            HStack(spacing: OverdriveDimens.spaceS3) {
                Text("No trainer is paired.")
                    .odStyle(OverdriveTypography.body, color: OverdriveColors.textSecondary)
                OdButton("Pair a trainer", icon: .bluetooth, variant: .secondary, height: 42) {
                    container.screen = .devices
                }
            }
        } else if container.settings.deviceSource == .bluetooth && container.bleAvailability != .ready {
            Text("Waiting for Bluetooth.")
                .odStyle(OverdriveTypography.body, color: OverdriveColors.textSecondary)
        } else {
            Text("Waiting for the trainer to connect.")
                .odStyle(OverdriveTypography.body, color: OverdriveColors.textSecondary)
        }
    }

    private var routeChooserSheet: some View {
        VStack(alignment: .leading, spacing: OverdriveDimens.spaceS4) {
            HStack {
                Text("Route").odStyle(OverdriveTypography.heading, color: OverdriveColors.textPrimary)
                Spacer()
                OdButton("Done", variant: .ghost, height: 34) { showRouteChooser = false }
            }
            ScrollView {
                VStack(spacing: OverdriveDimens.spaceS1) {
                    selectableRow(title: "Flat road", subtitle: "No hills", selected: container.selectedFreeRouteId == nil) {
                        container.selectFreeRoute(nil)
                        showRouteChooser = false
                    }
                    ForEach(container.routesList, id: \.id) { route in
                        let mapWord = route.hasCoordinates ? "has a map" : "profile only"
                        selectableRow(
                            title: route.name,
                            subtitle: "\(UiFormat.distanceKm(Double(route.distanceMeters))) km \u{00B7} \(route.ascentMeters) m up \u{00B7} \(mapWord)",
                            selected: container.selectedFreeRouteId == route.id
                        ) {
                            container.selectFreeRoute(route.id)
                            showRouteChooser = false
                        }
                    }
                }
            }
        }
        .padding(OverdriveDimens.spaceS5)
        .frame(width: 440, height: 420)
        .background(OverdriveColors.surface1)
    }

    private var selectedFreeRouteName: String? {
        guard let id = container.selectedFreeRouteId else { return nil }
        return container.routesList.first(where: { $0.id == id })?.name
    }

    private var activeDescription: String? {
        if case .free = container.choice {
            return selectedFreeRouteName != nil
                ? "Simulation on the route's grade with virtual gears. Virtual speed includes gravity."
                : "Simulation on the flat with virtual gears. Shift with the Click or arrow keys."
        }
        return container.choice.effectiveWorkout?.description_
    }

    private func todayString() -> String {
        let df = DateFormatter()
        df.dateFormat = "yyyy-MM-dd"
        return df.string(from: Date())
    }
}
