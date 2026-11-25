import AppKit
import Foundation

/// Prevents MacBook display/system sleep and App Nap throttling while a ride is live (ADR 0044).
@MainActor
public final class SleepAssertion {
    private var token: NSObjectProtocol?

    public init() {}

    public func setActive(_ active: Bool) {
        if active {
            guard token == nil else { return }
            token = ProcessInfo.processInfo.beginActivity(
                options: [
                    .userInitiated,
                    .idleSystemSleepDisabled,
                    .idleDisplaySleepDisabled,
                    .latencyCritical,
                ],
                reason: "Active Overdrive indoor cycling ride"
            )
        } else if let current = token {
            ProcessInfo.processInfo.endActivity(current)
            token = nil
        }
    }
}

/// Compact macOS menu bar extra (`NSStatusItem`) showing live power while riding and quick controls (ADR 0044).
@MainActor
public final class MenuBarController: NSObject {
    private let statusItem: NSStatusItem
    private let menu = NSMenu()
    private let toggleOverlayItem: NSMenuItem
    private let pauseResumeItem: NSMenuItem
    private let muteCoachItem: NSMenuItem

    public var onShowApp: (() -> Void)?
    public var onToggleOverlay: (() -> Void)?
    public var onTogglePause: (() -> Void)?
    public var onMuteCoach: (() -> Void)?
    public var onQuit: (() -> Void)?

    public override init() {
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)
        toggleOverlayItem = NSMenuItem(title: "Always Show Overlay", action: nil, keyEquivalent: "")
        pauseResumeItem = NSMenuItem(title: "Pause Ride", action: nil, keyEquivalent: "")
        muteCoachItem = NSMenuItem(title: "Mute Live Coach", action: nil, keyEquivalent: "")
        super.init()

        if let button = statusItem.button {
            button.image = NSImage(systemSymbolName: "bicycle", accessibilityDescription: "Overdrive")
            button.imagePosition = .imageLeading
        }

        let openItem = NSMenuItem(title: "Open Overdrive", action: #selector(handleShowApp), keyEquivalent: "")
        openItem.target = self
        menu.addItem(openItem)

        toggleOverlayItem.target = self
        toggleOverlayItem.action = #selector(handleToggleOverlay)
        menu.addItem(toggleOverlayItem)

        menu.addItem(.separator())

        pauseResumeItem.target = self
        pauseResumeItem.action = #selector(handleTogglePause)
        pauseResumeItem.isEnabled = false
        menu.addItem(pauseResumeItem)

        muteCoachItem.target = self
        muteCoachItem.action = #selector(handleMuteCoach)
        muteCoachItem.isEnabled = false
        menu.addItem(muteCoachItem)

        menu.addItem(.separator())

        let quitItem = NSMenuItem(title: "Quit Overdrive", action: #selector(handleQuit), keyEquivalent: "q")
        quitItem.target = self
        menu.addItem(quitItem)

        statusItem.menu = menu
    }

    public func update(
        isLive: Bool,
        isPaused: Bool,
        powerWatts: Int?,
        alwaysShowOverlay: Bool,
        coachActive: Bool,
        coachMuted: Bool
    ) {
        if let button = statusItem.button {
            if isLive {
                let text = isPaused ? " Paused" : (powerWatts.map { " \($0) W" } ?? " —")
                button.title = text
            } else {
                button.title = ""
            }
        }
        toggleOverlayItem.state = alwaysShowOverlay ? .on : .off
        pauseResumeItem.isEnabled = isLive
        pauseResumeItem.title = isPaused ? "Resume Ride" : "Pause Ride"
        muteCoachItem.isEnabled = coachActive && !coachMuted
        muteCoachItem.title = coachMuted ? "Live Coach Muted" : "Mute Live Coach"
    }

    @objc private func handleShowApp() { onShowApp?() }
    @objc private func handleToggleOverlay() { onToggleOverlay?() }
    @objc private func handleTogglePause() { onTogglePause?() }
    @objc private func handleMuteCoach() { onMuteCoach?() }
    @objc private func handleQuit() { onQuit?() }
}
