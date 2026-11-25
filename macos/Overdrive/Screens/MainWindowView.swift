import SwiftUI

/// Root window shell with the 64 pt top bar (design/system.md §6) and screen router.
public struct MainWindowView: View {
    @ObservedObject public var container: AppContainer

    public init(container: AppContainer) {
        self.container = container
    }

    public var body: some View {
        VStack(spacing: 0) {
            topBar
            OdDivider()
            content
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .background(OverdriveColors.bg)
    }

    private var topBar: some View {
        HStack(spacing: OverdriveDimens.spaceS3) {
            // Leading margin clears macOS traffic lights when using hiddenTitleBar.
            Spacer().frame(width: 68)

            if container.screen != .ride {
                OdButton(icon: .chevronLeft, variant: .ghost, height: 40) {
                    if container.screen == .learnButtons {
                        container.cancelButtonLearning()
                    } else {
                        container.screen = .ride
                    }
                }
            }

            Text(topBarTitle)
                .odStyle(OverdriveTypography.heading, color: OverdriveColors.textPrimary)

            if container.screen == .ride {
                stateChip
            }

            Spacer()

            // Device status shortcuts
            Button {
                container.screen = .devices
            } label: {
                HStack(spacing: OverdriveDimens.spaceS3) {
                    OdIcon(.bike, size: 16, color: deviceIconColor(connected: container.trainerConnected || container.settings.deviceSource == .simulated))
                    OdIcon(.heart, size: 16, color: deviceIconColor(connected: container.strapConnected || container.settings.deviceSource == .simulated))
                    OdIcon(.gamepad2, size: 16, color: deviceIconColor(connected: container.clickConnected))
                }
                .padding(.horizontal, OverdriveDimens.spaceS3)
                .frame(height: 36)
                .background(OverdriveColors.surface2)
                .clipShape(RoundedRectangle(cornerRadius: OverdriveDimens.radiusMd))
            }
            .buttonStyle(.plain)
            .help("Devices")

            OdButton(
                icon: .pictureInPicture2,
                variant: container.settings.overlayAlwaysShow ? .secondary : .ghost,
                height: 40
            ) {
                container.updateSettings { $0.overlayAlwaysShow.toggle() }
            }
            .help("Toggle floating overlay")

            OdButton(
                icon: .settings,
                variant: container.screen == .settings ? .secondary : .ghost,
                height: 40
            ) {
                container.screen = container.screen == .settings ? .ride : .settings
            }
            .help("Settings")
        }
        .padding(.trailing, OverdriveDimens.spaceS6)
        .frame(height: OverdriveDimens.sizeTopBar)
        .background(OverdriveColors.bg)
    }

    private var topBarTitle: String {
        switch container.screen {
        case .devices: return "Devices"
        case .learnButtons: return "Learn buttons"
        case .settings: return "Settings"
        case .ride:
            if let summary = container.finishedSummary {
                if case .saved = summary.saveState { return "Ride saved" }
                return "Ride finished"
            }
            return "Overdrive"
        }
    }

    @ViewBuilder
    private var stateChip: some View {
        if container.finishedSummary != nil {
            OdChip("Finished")
        } else {
            switch container.snapshot.state {
            case .riding:
                OdChip("Riding")
            case .paused:
                OdChip("Paused", icon: .pause, inverted: true)
            case .ready:
                OdChip("Ready")
            case .idle:
                if container.settings.deviceSource == .bluetooth && container.rememberedDevices.trainer == nil {
                    OdChip("No trainer")
                } else {
                    OdChip("Waiting for trainer")
                }
            default:
                EmptyView()
            }
        }
    }

    private func deviceIconColor(connected: Bool) -> Color {
        connected ? OverdriveColors.textPrimary : OverdriveColors.textTertiary
    }

    @ViewBuilder
    private var content: some View {
        switch container.screen {
        case .devices:
            DevicesView(container: container)
        case .learnButtons:
            LearnView(container: container)
        case .settings:
            SettingsView(container: container)
        case .ride:
            if let finished = container.finishedSummary {
                FinishedView(container: container, summary: finished)
            } else if container.isRideLive {
                RidingView(container: container)
            } else {
                ReadyView(container: container)
            }
        }
    }
}
