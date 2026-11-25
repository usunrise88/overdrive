import OverdriveCore
import SwiftUI

/// Devices & pairing screen (design/system.md §6.5).
public struct DevicesView: View {
    @ObservedObject public var container: AppContainer

    public init(container: AppContainer) {
        self.container = container
    }

    public var body: some View {
        ScrollView {
            HStack(alignment: .top, spacing: OverdriveDimens.spaceS6) {
                // Left column: Paired rows + Scan panel
                VStack(alignment: .leading, spacing: OverdriveDimens.spaceS4) {
                    Text(container.settings.deviceSource == .simulated
                         ? "These are simulated devices. Switch to Bluetooth under Settings, Developer."
                         : "Paired devices connect on their own when the app starts.")
                        .odStyle(OverdriveTypography.body, color: OverdriveColors.textSecondary)

                    if container.settings.deviceSource == .bluetooth && container.bleAvailability != .ready {
                        OdNotice(
                            icon: .bluetoothOff,
                            title: container.bleAvailability == .permissionDenied ? "Allow Bluetooth access" : "Bluetooth is off",
                            message: "Open System Settings to enable Bluetooth for Overdrive.",
                            primaryAction: ("Open Settings", { container.bleStack.openBluetoothSettings() })
                        )
                    }

                    if let locked = container.clickLockedMessage {
                        OdNotice(icon: .triangleAlert, title: "Click is locked", message: locked)
                    }

                    if container.rememberedDevices.click != nil && !container.accessibilityTrusted {
                        OdNotice(
                            icon: .info,
                            title: "Allow Accessibility for video Play/Pause",
                            message: "Volume, gears, intervals, and ride pause already work. Accessibility is only needed so the Click can play/pause video in Chrome, Safari, or IINA.",
                            primaryAction: ("Open Settings", { MacMediaController.openAccessibilitySettings() })
                        )
                    }

                    OdCard(padding: 0) {
                        VStack(spacing: 0) {
                            deviceRow(role: .trainer, icon: .bike, status: container.trainerStatusText, connected: container.trainerConnected)
                            OdDivider()
                            deviceRow(role: .strap, icon: .heart, status: container.strapStatusText, connected: container.strapConnected)
                            OdDivider()
                            deviceRow(role: .click, icon: .gamepad2, status: container.clickStatusText, connected: container.clickConnected)
                        }
                    }

                    if !container.canChangeDevices {
                        Text("Devices cannot be changed during a ride.")
                            .odStyle(OverdriveTypography.label, color: OverdriveColors.textTertiary)
                    } else if container.settings.deviceSource == .bluetooth && container.bleAvailability == .ready {
                        scanPanel
                    }
                }
                .frame(maxWidth: .infinity)

                // Right column: Click button mapping & Learn buttons
                VStack(alignment: .leading, spacing: OverdriveDimens.spaceS4) {
                    clickButtonsCard
                }
                .frame(width: 440)
            }
            .padding(OverdriveDimens.spaceS6)
        }
        .onDisappear { container.stopScan() }
    }

    private func deviceRow(role: MacDeviceRole, icon: OverdriveIcon, status: String, connected: Bool) -> some View {
        let paired = container.rememberedDevices[role]
        return HStack(spacing: OverdriveDimens.spaceS4) {
            ZStack {
                RoundedRectangle(cornerRadius: OverdriveDimens.radiusMd)
                    .fill(OverdriveColors.surface2)
                    .frame(width: 40, height: 40)
                OdIcon(icon, size: 20, color: paired == nil ? OverdriveColors.textTertiary : OverdriveColors.textPrimary)
            }
            VStack(alignment: .leading, spacing: 2) {
                Text(role.title).odStyle(OverdriveTypography.bodyStrong, color: OverdriveColors.textPrimary)
                Text(container.settings.deviceSource == .simulated ? "Simulated" : status)
                    .odStyle(OverdriveTypography.label, color: OverdriveColors.textSecondary)
            }
            Spacer()
            if container.settings.deviceSource == .bluetooth {
                if connected {
                    HStack(spacing: 4) {
                        OdIcon(.check, size: 16, color: OverdriveColors.textSecondary)
                        Text("Connected").odStyle(OverdriveTypography.label, color: OverdriveColors.textSecondary)
                    }
                } else if paired != nil {
                    OdButton("Retry now", variant: .secondary, height: 36) {
                        container.retryDevice(role: role)
                    }
                }
                if paired != nil && container.canChangeDevices {
                    OdButton("Forget", variant: .ghost, height: 36) {
                        container.forgetDevice(role: role)
                    }
                }
            }
        }
        .padding(.horizontal, OverdriveDimens.spaceS5)
        .frame(minHeight: OverdriveDimens.sizeDeviceRow)
    }

    private var scanPanel: some View {
        VStack(alignment: .leading, spacing: OverdriveDimens.spaceS3) {
            HStack(spacing: OverdriveDimens.spaceS3) {
                if container.isScanning {
                    OdButton("Stop", variant: .secondary, height: 44) { container.stopScan() }
                    OdSpinner(size: 18)
                    Text("Looking for devices\u{2026}").odStyle(OverdriveTypography.label, color: OverdriveColors.textSecondary)
                } else {
                    OdButton(container.discoveredDevices.isEmpty ? "Scan for devices" : "Scan again", icon: .bluetooth, variant: .primary, height: 44) {
                        container.startScan()
                    }
                }
            }

            if let err = container.scanError {
                OdNotice(icon: .triangleAlert, title: "Scan stopped", message: err)
            }

            if !container.discoveredDevices.isEmpty {
                OdCard(padding: OverdriveDimens.spaceS3) {
                    VStack(alignment: .leading, spacing: OverdriveDimens.spaceS3) {
                        discoveredGroup(title: "Trainers", role: .trainer)
                        discoveredGroup(title: "Heart-rate straps", role: .strap)
                        discoveredGroup(title: "Handlebar controllers", role: .click)
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func discoveredGroup(title: String, role: MacDeviceRole) -> some View {
        let items = container.discoveredDevices.filter { $0.role == role }
        if !items.isEmpty {
            VStack(alignment: .leading, spacing: OverdriveDimens.spaceS1) {
                Text(title)
                    .odStyle(OverdriveTypography.label, color: OverdriveColors.textTertiary)
                    .padding(.horizontal, OverdriveDimens.spaceS2)
                ForEach(items) { item in
                    let isPaired = container.rememberedDevices[role]?.id == item.id
                    Button {
                        container.pairDevice(item)
                    } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(item.name).odStyle(OverdriveTypography.bodyStrong, color: OverdriveColors.textPrimary)
                                Text((isPaired ? "Paired \u{00B7} " : "") + UiFormat.signalLabel(rssi: item.rssi))
                                    .odStyle(OverdriveTypography.label, color: OverdriveColors.textSecondary)
                            }
                            Spacer()
                            if isPaired {
                                OdIcon(.check, size: 18, color: OverdriveColors.textPrimary)
                            }
                        }
                        .padding(.horizontal, OverdriveDimens.spaceS3)
                        .padding(.vertical, OverdriveDimens.spaceS2)
                        .background(isPaired ? OverdriveColors.surface2 : Color.clear)
                        .clipShape(RoundedRectangle(cornerRadius: OverdriveDimens.radiusMd))
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    private var clickButtonsCard: some View {
        let mapping = container.settings.toButtonMapping()
        let rows: [(String, String, String, String)] = [
            ("Plus", ClickBits.shared.labelOf(mask: mapping.maskFor(button: .plus)), "Gear up", "Next interval"),
            ("Button 2", ClickBits.shared.labelOf(mask: mapping.maskFor(button: .button2)), "Gear down", "Previous interval"),
            ("Button 3", ClickBits.shared.labelOf(mask: mapping.maskFor(button: .button3)), "Play / pause media", "Pause / resume ride"),
            ("Button 4", ClickBits.shared.labelOf(mask: mapping.maskFor(button: .button4)), "Volume up", "Volume up (repeat)"),
            ("Button 5", ClickBits.shared.labelOf(mask: mapping.maskFor(button: .button5)), "Volume down", "Volume down (repeat)"),
        ]

        return VStack(alignment: .leading, spacing: OverdriveDimens.spaceS4) {
            OdCard(padding: 0) {
                VStack(alignment: .leading, spacing: 0) {
                    Text("Click buttons")
                        .odStyle(OverdriveTypography.heading, color: OverdriveColors.textPrimary)
                        .padding(OverdriveDimens.spaceS4)
                    OdDivider()
                    ForEach(Array(rows.enumerated()), id: \.offset) { idx, row in
                        if idx > 0 { OdDivider() }
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(row.0).odStyle(OverdriveTypography.bodyStrong, color: OverdriveColors.textPrimary)
                                Text(row.1).odStyle(OverdriveTypography.caption, color: OverdriveColors.textTertiary)
                            }
                            Spacer()
                            VStack(alignment: .trailing, spacing: 2) {
                                Text(row.2).odStyle(OverdriveTypography.label, color: OverdriveColors.textPrimary)
                                Text("Hold: \(row.3)").odStyle(OverdriveTypography.caption, color: OverdriveColors.textSecondary)
                            }
                        }
                        .padding(.horizontal, OverdriveDimens.spaceS4)
                        .padding(.vertical, OverdriveDimens.spaceS3)
                    }
                    OdDivider()
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Mute live coach").odStyle(OverdriveTypography.bodyStrong, color: OverdriveColors.textPrimary)
                            Text(mapping.coachMuteMask != 0 ? ClickBits.shared.labelOf(mask: mapping.coachMuteMask) : "Not assigned")
                                .odStyle(OverdriveTypography.caption, color: OverdriveColors.textTertiary)
                        }
                        Spacer()
                        OdButton("Set", variant: .secondary, height: 34, enabled: container.clickConnected && container.canChangeDevices) {
                            container.startButtonLearning(coachMuteOnly: true)
                        }
                    }
                    .padding(.horizontal, OverdriveDimens.spaceS4)
                    .padding(.vertical, OverdriveDimens.spaceS3)
                }
            }

            Text("In ERG steps, the two gear buttons adjust workout intensity by 5% (or target HR by 2 bpm in heart-rate steps).")
                .odStyle(OverdriveTypography.label, color: OverdriveColors.textSecondary)

            HStack(spacing: OverdriveDimens.spaceS3) {
                OdButton("Learn buttons", variant: .primary, height: 44, enabled: container.clickConnected && container.canChangeDevices) {
                    container.startButtonLearning(coachMuteOnly: false)
                }
                OdButton("Reset to default", variant: .ghost, height: 44, enabled: !container.settings.buttonMappingSerialized.isEmpty) {
                    container.resetButtonMapping()
                }
            }
            if !container.clickConnected {
                Text("Connect the Click to learn its buttons.")
                    .odStyle(OverdriveTypography.label, color: OverdriveColors.textTertiary)
            }
        }
    }
}

/// Learn buttons screen (design/system.md §6.5b).
public struct LearnView: View {
    @ObservedObject public var container: AppContainer

    private let roles: [(String, String)] = [
        ("Plus", "Gear up \u{00B7} hold for next interval"),
        ("Button 2", "Gear down \u{00B7} hold for previous interval"),
        ("Button 3", "Play or pause media \u{00B7} hold for pause or resume ride"),
        ("Button 4", "Volume up \u{00B7} hold to repeat"),
        ("Button 5", "Volume down \u{00B7} hold to repeat"),
    ]

    public init(container: AppContainer) {
        self.container = container
    }

    public var body: some View {
        HStack(alignment: .top, spacing: OverdriveDimens.spaceS8) {
            VStack(alignment: .leading, spacing: OverdriveDimens.spaceS4) {
                if container.learningDone {
                    Text("Saved").odStyle(OverdriveTypography.label, color: OverdriveColors.textTertiary)
                    Text("The Click has its new buttons").odStyle(OverdriveTypography.title, color: OverdriveColors.textPrimary)
                    Text("They are saved. The Click does nothing until you press Done.")
                        .odStyle(OverdriveTypography.body, color: OverdriveColors.textSecondary)
                } else if container.learningCoachMute {
                    Text("Live coach").odStyle(OverdriveTypography.label, color: OverdriveColors.textTertiary)
                    Text("Press a free button to mute the live coach").odStyle(OverdriveTypography.title, color: OverdriveColors.textPrimary)
                    Text("Press and release one button that is not used by the five main roles.")
                        .odStyle(OverdriveTypography.body, color: OverdriveColors.textSecondary)
                } else {
                    let idx = min(roles.count - 1, max(0, container.learningStepIndex))
                    Text("Step \(idx + 1) of \(roles.count)").odStyle(OverdriveTypography.label, color: OverdriveColors.textTertiary)
                    Text("Press the button you want as \(roles[idx].0)").odStyle(OverdriveTypography.title, color: OverdriveColors.textPrimary)
                    Text(roles[idx].1).odStyle(OverdriveTypography.body, color: OverdriveColors.textSecondary)
                    Text("Press and release one button, then wait.").odStyle(OverdriveTypography.label, color: OverdriveColors.textTertiary)
                }

                if let err = container.learningError {
                    OdNotice(icon: .triangleAlert, title: "Try again", message: err)
                }

                if !container.clickConnected {
                    Text("The Click is not connected. Reconnect it to go on.")
                        .odStyle(OverdriveTypography.bodyStrong, color: OverdriveColors.textPrimary)
                }

                HStack(spacing: OverdriveDimens.spaceS3) {
                    OdButton(container.learningDone ? "Done" : "Cancel", variant: container.learningDone ? .primary : .secondary, height: 44) {
                        container.cancelButtonLearning()
                    }
                    OdButton("Reset to default", variant: .ghost, height: 44) {
                        container.resetButtonMapping()
                        container.cancelButtonLearning()
                    }
                }
                .padding(.top, OverdriveDimens.spaceS2)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            if !container.learningCoachMute {
                OdCard(padding: OverdriveDimens.spaceS3) {
                    VStack(spacing: OverdriveDimens.spaceS1) {
                        ForEach(Array(roles.enumerated()), id: \.offset) { idx, role in
                            let done = container.learningDone || idx < container.learningStepIndex
                            let active = !container.learningDone && idx == container.learningStepIndex
                            HStack(spacing: OverdriveDimens.spaceS3) {
                                if done {
                                    OdIcon(.check, size: 16, color: OverdriveColors.textPrimary)
                                } else {
                                    Circle()
                                        .fill(active ? OverdriveColors.textPrimary : OverdriveColors.surface3)
                                        .frame(width: 10, height: 10)
                                }
                                VStack(alignment: .leading, spacing: 2) {
                                    Text(role.0).odStyle(OverdriveTypography.bodyStrong, color: OverdriveColors.textPrimary)
                                    Text(role.1).odStyle(OverdriveTypography.caption, color: OverdriveColors.textSecondary)
                                }
                                Spacer()
                                Text(active ? "Press now" : (done ? "Set" : UiFormat.emptyDash))
                                    .odStyle(OverdriveTypography.label, color: active ? OverdriveColors.textPrimary : OverdriveColors.textTertiary)
                            }
                            .padding(.horizontal, OverdriveDimens.spaceS3)
                            .padding(.vertical, OverdriveDimens.spaceS2)
                            .background(active ? OverdriveColors.surface2 : Color.clear)
                            .clipShape(RoundedRectangle(cornerRadius: OverdriveDimens.radiusMd))
                        }
                    }
                }
                .frame(width: 440)
            }
        }
        .padding(OverdriveDimens.spaceS6)
    }
}
