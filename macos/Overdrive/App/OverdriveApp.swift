import AppKit
import SwiftUI
import UniformTypeIdentifiers

@main
public struct OverdriveMacApp: App {
    @NSApplicationDelegateAdaptor(OverdriveAppDelegate.self) private var delegate
    @StateObject private var container = AppContainer(
        startSubsystems: ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] == nil
    )

    public init() {}

    public var body: some Scene {
        WindowGroup("Overdrive") {
            MainWindowView(container: container)
                .frame(minWidth: 1024, minHeight: 680)
                .preferredColorScheme(.dark)
                .background(WindowVisibilityObserver { visible in
                    container.mainWindowVisible = visible
                    container.updateOverlayVisibility()
                    if visible {
                        container.bleStack.refreshAvailability()
                        container.accessibilityTrusted = MacMediaController.isAccessibilityTrusted
                    }
                })
                .onOpenURL { url in
                    container.importWorkoutFile(url: url)
                }
        }
        .defaultSize(width: 1280, height: 800)
        .windowStyle(.hiddenTitleBar)
        .commands {
            CommandGroup(replacing: .newItem) {
                Button("Open Workout File…") {
                    openWorkoutPicker()
                }
                .keyboardShortcut("o", modifiers: .command)
            }
            CommandGroup(replacing: .appSettings) {
                Button("Settings…") {
                    container.screen = .settings
                }
                .keyboardShortcut(",", modifiers: .command)
            }
            CommandMenu("Ride") {
                Button(container.snapshot.state == .paused ? "Resume Ride" : "Pause Ride") {
                    container.dispatchAction(.toggleRidePause)
                }
                .keyboardShortcut(.space, modifiers: [])
                .disabled(!container.isRideLive)

                Divider()

                Button("Gear / Intensity Up") {
                    container.dispatchAction(.gearUp)
                }
                .keyboardShortcut(.upArrow, modifiers: [])
                .disabled(!container.isRideLive)

                Button("Gear / Intensity Down") {
                    container.dispatchAction(.gearDown)
                }
                .keyboardShortcut(.downArrow, modifiers: [])
                .disabled(!container.isRideLive)

                Button("Next Interval") {
                    container.dispatchAction(.nextInterval)
                }
                .keyboardShortcut(.rightArrow, modifiers: [])
                .disabled(!container.isRideLive)

                Button("Previous Interval") {
                    container.dispatchAction(.previousInterval)
                }
                .keyboardShortcut(.leftArrow, modifiers: [])
                .disabled(!container.isRideLive)

                Divider()

                Button("Mute Live Coach") {
                    container.dispatchAction(.muteCoach)
                }
                .keyboardShortcut("m", modifiers: [])
                .disabled(!container.isRideLive)

                Button("Always Show Overlay") {
                    container.updateSettings { $0.overlayAlwaysShow.toggle() }
                }
                .keyboardShortcut("o", modifiers: [.command, .shift])
            }
        }
    }

    @MainActor
    private func openWorkoutPicker() {
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [UTType.json, UTType(filenameExtension: "zwo") ?? .xml]
        panel.allowsMultipleSelection = false
        if panel.runModal() == .OK, let url = panel.url {
            container.importWorkoutFile(url: url)
        }
    }
}

public final class OverdriveAppDelegate: NSObject, NSApplicationDelegate {
    public func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        false
    }
}

/// Observes the main `NSWindow` occlusion, miniaturization, and application hide/deactivate state
/// so the floating HUD overlay appears automatically when Overdrive is behind full-screen video.
private struct WindowVisibilityObserver: NSViewRepresentable {
    let onChange: (Bool) -> Void

    func makeNSView(context: Context) -> ObserverView {
        let view = ObserverView()
        view.onChange = onChange
        return view
    }

    func updateNSView(_ nsView: ObserverView, context: Context) {
        nsView.onChange = onChange
    }

    final class ObserverView: NSView {
        var onChange: ((Bool) -> Void)?
        private var tokens: [NSObjectProtocol] = []

        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            tokens.forEach { NotificationCenter.default.removeObserver($0) }
            tokens.removeAll()
            guard let window else { return }

            window.backgroundColor = NSColor(OverdriveColors.bg)
            window.titlebarAppearsTransparent = true
            window.isMovableByWindowBackground = false

            let nc = NotificationCenter.default
            let events: [NSNotification.Name] = [
                NSWindow.didBecomeKeyNotification,
                NSWindow.didResignKeyNotification,
                NSWindow.didMiniaturizeNotification,
                NSWindow.didDeminiaturizeNotification,
                NSWindow.didChangeOcclusionStateNotification,
                NSApplication.didHideNotification,
                NSApplication.didUnhideNotification,
                NSApplication.didBecomeActiveNotification,
                NSApplication.didResignActiveNotification,
            ]
            for name in events {
                tokens.append(
                    nc.addObserver(forName: name, object: nil, queue: .main) { [weak self, weak window] _ in
                        guard let self, let window else { return }
                        let visible = !NSApp.isHidden
                            && !window.isMiniaturized
                            && window.occlusionState.contains(.visible)
                            && NSApp.isActive
                        self.onChange?(visible)
                    }
                )
            }
        }

        deinit {
            tokens.forEach { NotificationCenter.default.removeObserver($0) }
        }
    }
}
