import SwiftUI

/// Two-column Settings screen (design/system.md §6.6).
public struct SettingsView: View {
    @ObservedObject public var container: AppContainer
    @State private var syncUrlDraft: String = ""
    @State private var syncTokenDraft: String = ""
    @State private var showToken: Bool = false

    public init(container: AppContainer) {
        self.container = container
    }

    public var body: some View {
        ScrollView {
            HStack(alignment: .top, spacing: OverdriveDimens.spaceS6) {
                // Left column: Overlay + Developer
                VStack(alignment: .leading, spacing: OverdriveDimens.spaceS6) {
                    overlayGroup
                    developerGroup
                }
                .frame(maxWidth: .infinity)

                // Right column: Rider & gears + Ride + Physics + Sync
                VStack(alignment: .leading, spacing: OverdriveDimens.spaceS6) {
                    riderAndGearsGroup
                    rideGroup
                    physicsGroup
                    syncGroup
                }
                .frame(maxWidth: .infinity)
            }
            .padding(OverdriveDimens.spaceS6)
        }
        .onAppear {
            syncUrlDraft = container.settings.syncUrl
            syncTokenDraft = container.syncToken
        }
    }

    // MARK: - Overlay

    private var overlayGroup: some View {
        groupSection("Overlay") {
            VStack(spacing: 0) {
                // Live preview over a bright backdrop (verifies readability over bright video)
                ZStack {
                    LinearGradient(
                        colors: [Color(argb: 0xFFD9E2EC), Color(argb: 0xFFB0C4D8)],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                    OverlayContentView(model: previewModel)
                        .scaleEffect(0.88)
                }
                .frame(height: 150)
                .clipShape(RoundedRectangle(cornerRadius: OverdriveDimens.radiusMd))
                .padding(OverdriveDimens.spaceS4)

                OdDivider()
                settingRow(title: "Mode", note: "Default layout; click the overlay to switch") {
                    Picker("", selection: Binding(
                        get: { container.settings.overlayMode },
                        set: { mode in container.updateSettings { $0.overlayMode = mode } }
                    )) {
                        Text("Compact").tag(OverlayMode.compact)
                        Text("Detailed").tag(OverlayMode.detailed)
                    }
                    .pickerStyle(.segmented)
                    .frame(width: 180)
                }

                OdDivider()
                settingRow(title: "Opacity", note: "Backing opacity over bright video") {
                    HStack(spacing: OverdriveDimens.spaceS2) {
                        Slider(
                            value: Binding(
                                get: { container.settings.overlayOpacity },
                                set: { v in container.updateSettings { $0.overlayOpacity = v } }
                            ),
                            in: 0.85...1.0,
                            step: 0.05
                        )
                        .frame(width: 120)
                        Text("\(Int((container.settings.overlayOpacity * 100).rounded()))%")
                            .odStyle(OverdriveTypography.numXS, color: OverdriveColors.textPrimary)
                            .frame(width: 44, alignment: .trailing)
                    }
                }

                OdDivider()
                toggleRow(
                    title: "Blur the video behind",
                    note: "Uses NSVisualEffectView behind the overlay backing",
                    isOn: Binding(get: { container.settings.overlayBlur }, set: { v in container.updateSettings { $0.overlayBlur = v } })
                )

                OdDivider()
                toggleRow(
                    title: "Expand at step changes",
                    note: "Shows the detail 10 s before and after each new step",
                    isOn: Binding(get: { container.settings.overlayExpandAtStepChange }, set: { v in container.updateSettings { $0.overlayExpandAtStepChange = v } })
                )

                OdDivider()
                toggleRow(
                    title: "Always show overlay while riding",
                    note: "Keep the HUD visible even when Overdrive's window is active",
                    isOn: Binding(get: { container.settings.overlayAlwaysShow }, set: { v in container.updateSettings { $0.overlayAlwaysShow = v } })
                )

                OdDivider()
                settingRow(title: "Position", note: "Returns the overlay to the top-right corner") {
                    OdButton("Reset", variant: .ghost, height: 34) {
                        container.resetOverlayPosition()
                    }
                }
            }
        }
    }

    private var previewModel: OverlayViewModel {
        let vm = OverlayViewModel()
        vm.metrics = .sample
        vm.look = OverlayLook(mode: .compact, opacity: container.settings.overlayOpacity, blurEnabled: container.settings.overlayBlur)
        return vm
    }

    // MARK: - Rider & Gears

    private var riderAndGearsGroup: some View {
        let fromProfile = container.profileState.profile?.ftp != nil
        return groupSection("Rider and gears") {
            VStack(spacing: 0) {
                settingRow(title: "FTP", note: fromProfile ? "From service" : "Functional threshold power for zones and ERG") {
                    OdStepper(
                        valueText: "\(container.effectiveFtp) W",
                        canDecrement: container.effectiveFtp > 35,
                        canIncrement: container.effectiveFtp < 1995,
                        onDecrement: { container.updateSettings { $0.ftp -= 5 } },
                        onIncrement: { container.updateSettings { $0.ftp += 5 } }
                    )
                }
                OdDivider()
                settingRow(title: "Gears", note: "Virtual gears for simulation steps (10 to 30)") {
                    OdStepper(
                        valueText: "\(container.settings.gearCount)",
                        canDecrement: container.settings.gearCount > 10,
                        canIncrement: container.settings.gearCount < 30,
                        onDecrement: { container.updateSettings { $0.gearCount -= 1 } },
                        onIncrement: { container.updateSettings { $0.gearCount += 1 } }
                    )
                }
                OdDivider()
                settingRow(title: "Step per gear", note: "Total grade stays clamped inside the trainer's 15% limit") {
                    OdStepper(
                        valueText: String(format: "%.1f%%", container.settings.gradeStepPercent),
                        canDecrement: container.settings.gradeStepPercent > 0.5,
                        canIncrement: container.settings.gradeStepPercent < 2.0,
                        onDecrement: { container.updateSettings { $0.gradeStepPercent -= 0.1 } },
                        onIncrement: { container.updateSettings { $0.gradeStepPercent += 0.1 } }
                    )
                }
                OdDivider()
                settingRow(title: "Route difficulty", note: "Share of the road's grade you feel; 0% is flat") {
                    OdStepper(
                        valueText: "\(container.settings.routeDifficultyPercent)%",
                        canDecrement: container.settings.routeDifficultyPercent > 0,
                        canIncrement: container.settings.routeDifficultyPercent < 100,
                        onDecrement: { container.updateSettings { $0.routeDifficultyPercent -= 10 } },
                        onIncrement: { container.updateSettings { $0.routeDifficultyPercent += 10 } }
                    )
                }
            }
        }
    }

    // MARK: - Ride

    private var rideGroup: some View {
        groupSection("Ride") {
            VStack(spacing: 0) {
                toggleRow(
                    title: "Cadence guard",
                    note: "Lowers the ERG target to 70% when your cadence drops below the floor",
                    isOn: Binding(get: { container.settings.cadenceGuardEnabled }, set: { v in container.updateSettings { $0.cadenceGuardEnabled = v } })
                )
                OdDivider()
                settingRow(title: "Cadence floor", note: "Minimum cadence before the guard takes hold") {
                    OdStepper(
                        valueText: "\(container.settings.cadenceFloorRpm) rpm",
                        canDecrement: container.settings.cadenceFloorRpm > 30,
                        canIncrement: container.settings.cadenceFloorRpm < 90,
                        onDecrement: { container.updateSettings { $0.cadenceFloorRpm -= 5 } },
                        onIncrement: { container.updateSettings { $0.cadenceFloorRpm += 5 } }
                    )
                }
                OdDivider()
                toggleRow(
                    title: "Live coach",
                    note: "Short spoken cues over your video, from the service. Never changes your targets",
                    isOn: Binding(get: { container.settings.liveCoachEnabled }, set: { v in container.updateSettings { $0.liveCoachEnabled = v } })
                )
                OdDivider()
                settingRow(title: "Coach volume", note: "Volume of spoken Live Coach cues") {
                    HStack(spacing: OverdriveDimens.spaceS2) {
                        Slider(
                            value: Binding(
                                get: { container.settings.coachVolume },
                                set: { v in container.updateSettings { $0.coachVolume = v } }
                            ),
                            in: 0.1...1.0,
                            step: 0.1
                        )
                        .frame(width: 120)
                        Text("\(Int((container.settings.coachVolume * 100).rounded()))%")
                            .odStyle(OverdriveTypography.numXS, color: OverdriveColors.textPrimary)
                            .frame(width: 44, alignment: .trailing)
                    }
                }
            }
        }
    }

    // MARK: - Physics

    private var physicsGroup: some View {
        let p = container.effectivePhysics
        return groupSection("Physics for speed and distance") {
            VStack(spacing: 0) {
                settingRow(title: "Rider and bike mass", note: "Total system mass (rider, bike, and kit)") {
                    OdStepper(
                        valueText: String(format: "%.0f kg", p.systemMassKg),
                        canDecrement: container.settings.systemMassKg > 40,
                        canIncrement: container.settings.systemMassKg < 190,
                        onDecrement: { container.updateSettings { $0.systemMassKg -= 1 } },
                        onIncrement: { container.updateSettings { $0.systemMassKg += 1 } }
                    )
                }
                OdDivider()
                settingRow(title: "Aerodynamic drag (CdA)", note: "Frontal area times drag coefficient") {
                    OdStepper(
                        valueText: String(format: "%.2f m\u{00B2}", p.cdA),
                        canDecrement: container.settings.cdA > 0.16,
                        canIncrement: container.settings.cdA < 0.79,
                        onDecrement: { container.updateSettings { $0.cdA -= 0.01 } },
                        onIncrement: { container.updateSettings { $0.cdA += 0.01 } }
                    )
                }
                OdDivider()
                settingRow(title: "Rolling resistance (Crr)", note: "Road tyre rolling resistance") {
                    OdStepper(
                        valueText: String(format: "%.4f", p.crr),
                        canDecrement: container.settings.crr > 0.0015,
                        canIncrement: container.settings.crr < 0.0195,
                        onDecrement: { container.updateSettings { $0.crr -= 0.0005 } },
                        onIncrement: { container.updateSettings { $0.crr += 0.0005 } }
                    )
                }
            }
        }
    }

    // MARK: - Sync

    private var syncGroup: some View {
        groupSection("Sync") {
            VStack(spacing: 0) {
                VStack(alignment: .leading, spacing: OverdriveDimens.spaceS2) {
                    Text("Service address").odStyle(OverdriveTypography.bodyStrong, color: OverdriveColors.textPrimary)
                    Text("Where finished rides are sent and planned workouts come from")
                        .odStyle(OverdriveTypography.label, color: OverdriveColors.textSecondary)
                    TextField("https://overdrive.example.com", text: $syncUrlDraft)
                        .textFieldStyle(.plain)
                        .padding(.horizontal, OverdriveDimens.spaceS3)
                        .frame(height: 40)
                        .background(OverdriveColors.surface2)
                        .overlay(RoundedRectangle(cornerRadius: OverdriveDimens.radiusMd).stroke(OverdriveColors.border, lineWidth: 1))
                        .clipShape(RoundedRectangle(cornerRadius: OverdriveDimens.radiusMd))
                        .onChange(of: syncUrlDraft) { _, newVal in
                            container.setSyncUrl(newVal)
                        }
                    if let err = container.syncUrlError {
                        Text(err).odStyle(OverdriveTypography.label, color: OverdriveColors.textPrimary)
                    } else if let warn = container.syncUrlWarning {
                        Text(warn).odStyle(OverdriveTypography.label, color: OverdriveColors.textPrimary)
                    }
                }
                .padding(OverdriveDimens.spaceS4)

                OdDivider()

                VStack(alignment: .leading, spacing: OverdriveDimens.spaceS2) {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Token").odStyle(OverdriveTypography.bodyStrong, color: OverdriveColors.textPrimary)
                            Text("Stored in macOS Keychain \u{00B7} sent only to this address")
                                .odStyle(OverdriveTypography.label, color: OverdriveColors.textSecondary)
                        }
                        Spacer()
                        OdButton(showToken ? "Hide" : "Show", variant: .ghost, height: 32) {
                            showToken.toggle()
                        }
                    }
                    Group {
                        if showToken {
                            TextField("Device bearer token", text: $syncTokenDraft)
                        } else {
                            SecureField("Device bearer token", text: $syncTokenDraft)
                        }
                    }
                    .textFieldStyle(.plain)
                    .padding(.horizontal, OverdriveDimens.spaceS3)
                    .frame(height: 40)
                    .background(OverdriveColors.surface2)
                    .overlay(RoundedRectangle(cornerRadius: OverdriveDimens.radiusMd).stroke(OverdriveColors.border, lineWidth: 1))
                    .clipShape(RoundedRectangle(cornerRadius: OverdriveDimens.radiusMd))
                    .onChange(of: syncTokenDraft) { _, newVal in
                        container.setSyncToken(newVal)
                    }
                }
                .padding(OverdriveDimens.spaceS4)

                OdDivider()
                settingRow(title: "Waiting to upload", note: container.syncStatusLine ?? "All finished rides queue automatically") {
                    Text("\(container.syncPendingCount) rides")
                        .odStyle(OverdriveTypography.numXS, color: OverdriveColors.textPrimary)
                }

                OdDivider()
                settingRow(title: "Last upload", note: lastUploadText) {
                    OdButton("Send now", variant: .secondary, height: 36, enabled: container.syncPendingCount > 0) {
                        container.sendSyncNow()
                    }
                }
            }
        }
    }

    // MARK: - Developer

    private var developerGroup: some View {
        groupSection("Developer") {
            VStack(spacing: 0) {
                settingRow(title: "Devices", note: "Use real Bluetooth hardware or built-in bike simulation") {
                    Picker("", selection: Binding(
                        get: { container.settings.deviceSource },
                        set: { src in container.updateSettings { $0.deviceSource = src } }
                    )) {
                        Text("Bluetooth").tag(DeviceSourceMode.bluetooth)
                        Text("Simulated").tag(DeviceSourceMode.simulated)
                    }
                    .pickerStyle(.segmented)
                    .frame(width: 190)
                }
                OdDivider()
                toggleRow(
                    title: "Connect in the background",
                    note: "Keeps a pending CoreBluetooth connect so a sleeping Zwift Click wakes immediately",
                    isOn: Binding(get: { container.settings.connectInBackground }, set: { v in container.updateSettings { $0.connectInBackground = v } })
                )
                OdDivider()
                toggleRow(
                    title: "Record Bluetooth sessions",
                    note: "Redacted raw GATT logs for diagnosing trainer or Click issues",
                    isOn: Binding(get: { container.settings.recordBleSessions }, set: { v in container.updateSettings { $0.recordBleSessions = v } })
                )
                OdDivider()
                settingRow(
                    title: "Export Bluetooth logs",
                    note: container.bleLogsShareMessage ?? (container.bleLogsSummary.count == 0
                        ? "No logs yet"
                        : "\(container.bleLogsSummary.count) files \u{00B7} \(UiFormat.fileSize(container.bleLogsSummary.totalBytes))")
                ) {
                    OdButton("Export .zip", icon: .share2, variant: .secondary, height: 36, enabled: container.bleLogsSummary.count > 0) {
                        container.exportBleLogsZip()
                    }
                }
                OdDivider()
                settingRow(title: "Demo speed (simulated)", note: "Fast-forward simulated rides for testing") {
                    Picker("", selection: Binding(
                        get: { container.settings.demoSpeedMultiplier },
                        set: { m in container.updateSettings { $0.demoSpeedMultiplier = m } }
                    )) {
                        Text("1x").tag(1)
                        Text("5x").tag(5)
                        Text("10x").tag(10)
                        Text("30x").tag(30)
                    }
                    .pickerStyle(.segmented)
                    .frame(width: 180)
                }
                OdDivider()
                toggleRow(
                    title: "Scripted dropouts (simulated)",
                    note: "Simulate strap and trainer disconnects mid-ride",
                    isOn: Binding(get: { container.settings.scriptedDropouts }, set: { v in container.updateSettings { $0.scriptedDropouts = v } })
                )
                OdDivider()
                settingRow(title: "Click hold threshold", note: "Press-and-hold time for interval skip and ride pause") {
                    OdStepper(
                        valueText: "\(container.settings.clickHoldThresholdMs) ms",
                        canDecrement: container.settings.clickHoldThresholdMs > 250,
                        canIncrement: container.settings.clickHoldThresholdMs < 1000,
                        onDecrement: { container.updateSettings { $0.clickHoldThresholdMs -= 50 } },
                        onIncrement: { container.updateSettings { $0.clickHoldThresholdMs += 50 } }
                    )
                }
            }
        }
    }

    // MARK: - Helpers

    private func groupSection<Content: View>(_ title: String, @ViewBuilder content: @escaping () -> Content) -> some View {
        VStack(alignment: .leading, spacing: OverdriveDimens.spaceS2) {
            Text(title).odStyle(OverdriveTypography.label, color: OverdriveColors.textTertiary)
            OdCard(padding: 0, content: content)
        }
    }

    private func settingRow<Control: View>(title: String, note: String, @ViewBuilder control: () -> Control) -> some View {
        HStack(spacing: OverdriveDimens.spaceS4) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).odStyle(OverdriveTypography.bodyStrong, color: OverdriveColors.textPrimary)
                Text(note).odStyle(OverdriveTypography.label, color: OverdriveColors.textSecondary)
            }
            Spacer()
            control()
        }
        .padding(.horizontal, OverdriveDimens.spaceS4)
        .padding(.vertical, OverdriveDimens.spaceS3)
        .frame(minHeight: 60)
    }

    private func toggleRow(title: String, note: String, isOn: Binding<Bool>) -> some View {
        settingRow(title: title, note: note) {
            Toggle("", isOn: isOn)
                .labelsHidden()
                .toggleStyle(.switch)
        }
    }

    private var lastUploadText: String {
        guard let millis = container.lastUploadMillis else { return "Never" }
        let df = DateFormatter()
        df.dateStyle = .medium
        df.timeStyle = .short
        return df.string(from: Date(timeIntervalSince1970: TimeInterval(millis) / 1000.0))
    }
}
