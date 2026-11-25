import AppKit
import SwiftUI

/// Always-on-top, non-activating HUD panel that floats above native macOS Full-Screen Spaces (ADR 0044).
public final class OverlayPanel: NSPanel {
    public init(contentRect: NSRect) {
        super.init(
            contentRect: contentRect,
            styleMask: [.borderless, .nonactivatingPanel],
            backing: .buffered,
            defer: false
        )
        isFloatingPanel = true
        hidesOnDeactivate = false
        level = .statusBar
        collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary, .ignoresCycle]
        isOpaque = false
        backgroundColor = .clear
        hasShadow = false
        isMovable = false
        acceptsMouseMovedEvents = false
    }

    public override var canBecomeKey: Bool { false }
    public override var canBecomeMain: Bool { false }
}

/// Captures raw AppKit pointer events so dragging moves the panel 1:1 via `setFrameOrigin`
/// without triggering SwiftUI layout or recomposition.
private final class OverlayGestureHostView: NSView {
    var onMouseDown: ((NSPoint, NSEvent) -> Void)?
    var onMouseDragged: ((NSPoint) -> Void)?
    var onMouseUp: ((NSEvent) -> Void)?

    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }

    override func mouseDown(with event: NSEvent) {
        onMouseDown?(NSEvent.mouseLocation, event)
    }

    override func mouseDragged(with event: NSEvent) {
        onMouseDragged?(NSEvent.mouseLocation)
    }

    override func mouseUp(with event: NSEvent) {
        onMouseUp?(event)
    }
}

/// Owns the floating HUD panel lifecycle, drag/snap physics, notch-safe screen bounds, and multi-monitor persistence.
@MainActor
public final class OverlayWindowController {
    public let viewModel = OverlayViewModel()

    private var panel: OverlayPanel?
    private var hostingView: NSHostingView<AnyView>?
    private var visualEffectView: NSVisualEffectView?
    private let classifier = OverlayGestureClassifier()
    private var dragStartPanelOrigin: NSPoint = .zero
    private var dragStartScreenPoint: NSPoint = .zero
    private var longPressTimer: Timer?
    private var showTask: Task<Void, Never>?
    private var isShown = false
    private var savedFractionX: Double?
    private var savedFractionY: Double?
    private var muteFrameInRoot: CGRect = .zero

    public var onToggleMode: (() -> Void)?
    public var onOpenMainApp: (() -> Void)?
    public var onMuteCoach: (() -> Void)?
    public var onPositionSaved: ((Double, Double) -> Void)?

    public init() {}

    public func restorePosition(x: Double?, y: Double?) {
        savedFractionX = x
        savedFractionY = y
    }

    public func updateVisibility(wanted: Bool) {
        showTask?.cancel()
        if !wanted {
            hideImmediately()
            return
        }
        guard !isShown else { return }
        showTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: OverlayPolicy.showDelayMs * 1_000_000)
            guard !Task.isCancelled else { return }
            self?.showNow()
        }
    }

    public func updateLook(_ look: OverlayLook) {
        let oldSize = currentPanelSize()
        viewModel.look = look
        visualEffectView?.isHidden = !look.blurEnabled
        resizePanelPreservingEdge(oldSize: oldSize)
    }

    public func updateMetrics(_ metrics: OverlayUiState) {
        let oldSize = currentPanelSize()
        viewModel.metrics = metrics
        if isShown {
            resizePanelPreservingEdge(oldSize: oldSize)
        }
    }

    public func updateCoach(_ coach: OverlayCoachState) {
        let oldSize = currentPanelSize()
        viewModel.coach = coach
        if isShown {
            resizePanelPreservingEdge(oldSize: oldSize)
        }
    }

    public func resetPosition() {
        savedFractionX = nil
        savedFractionY = nil
        guard let panel else { return }
        let screen = targetScreen(for: panel)
        let area = notchSafeArea(for: screen)
        let size = OverlaySize(width: panel.frame.width, height: panel.frame.height)
        let topLeft = OverlayPlacement.defaultPosition(size: size, area: area)
        panel.setFrameOrigin(cocoaOrigin(fromTopLeft: topLeft, size: size, screen: screen))
    }

    public func showNow() {
        guard !isShown else { return }
        let screen = NSScreen.main ?? NSScreen.screens.first
        guard let screen else { return }

        let rootContent = AnyView(
            OverlayContentView(model: viewModel) { [weak self] rect in
                self?.muteFrameInRoot = rect
            }
            .coordinateSpace(name: "overlayRoot")
        )

        let host = NSHostingView(rootView: rootContent)
        host.translatesAutoresizingMaskIntoConstraints = false
        let fitting = host.fittingSize
        let size = OverlaySize(
            width: max(OverdriveDimens.overlayCompactWidth, fitting.width),
            height: max(OverdriveDimens.overlayCompactHeight, fitting.height)
        )

        let area = notchSafeArea(for: screen)
        let topLeft = OverlayPlacement.fromFractions(x: savedFractionX, y: savedFractionY, size: size, area: area)
        let origin = cocoaOrigin(fromTopLeft: topLeft, size: size, screen: screen)
        let panel = OverlayPanel(contentRect: NSRect(origin: origin, size: NSSize(width: size.width, height: size.height)))

        let container = OverlayGestureHostView(frame: NSRect(origin: .zero, size: NSSize(width: size.width, height: size.height)))
        container.wantsLayer = true
        container.layer?.backgroundColor = NSColor.clear.cgColor

        let blur = NSVisualEffectView(frame: container.bounds)
        blur.autoresizingMask = [.width, .height]
        blur.material = .hudWindow
        blur.blendingMode = .behindWindow
        blur.state = .active
        blur.wantsLayer = true
        blur.layer?.cornerRadius = OverdriveDimens.overlayRadius
        blur.layer?.masksToBounds = true
        blur.isHidden = !viewModel.look.blurEnabled

        container.addSubview(blur)
        container.addSubview(host)
        NSLayoutConstraint.activate([
            host.leadingAnchor.constraint(equalTo: container.leadingAnchor),
            host.trailingAnchor.constraint(equalTo: container.trailingAnchor),
            host.topAnchor.constraint(equalTo: container.topAnchor),
            host.bottomAnchor.constraint(equalTo: container.bottomAnchor),
        ])

        container.onMouseDown = { [weak self] screenPoint, event in
            self?.handleMouseDown(screenPoint: screenPoint, event: event)
        }
        container.onMouseDragged = { [weak self] screenPoint in
            self?.handleMouseDragged(screenPoint: screenPoint)
        }
        container.onMouseUp = { [weak self] event in
            self?.handleMouseUp(event: event)
        }

        panel.contentView = container
        panel.orderFrontRegardless()

        self.panel = panel
        self.hostingView = host
        self.visualEffectView = blur
        self.isShown = true
    }

    public func hideImmediately() {
        showTask?.cancel()
        longPressTimer?.invalidate()
        longPressTimer = nil
        classifier.cancel()
        panel?.orderOut(nil)
        panel = nil
        hostingView = nil
        visualEffectView = nil
        isShown = false
    }

    // MARK: - Pointer Handling

    private func handleMouseDown(screenPoint: NSPoint, event: NSEvent) {
        guard let panel else { return }
        dragStartPanelOrigin = panel.frame.origin
        dragStartScreenPoint = screenPoint
        let localPoint = panel.contentView?.convert(event.locationInWindow, from: nil) ?? .zero
        // Flip Y into SwiftUI top-left coordinates for hit-testing the mute badge.
        let swiftUIPoint = CGPoint(x: localPoint.x, y: panel.frame.height - localPoint.y)
        let inMute = !muteFrameInRoot.isEmpty && muteFrameInRoot.contains(swiftUIPoint)

        let nowMs = monotonicMillis()
        classifier.mouseDown(at: OverlayPoint(x: screenPoint.x, y: screenPoint.y), timeMs: nowMs, inMuteControl: inMute)

        longPressTimer?.invalidate()
        longPressTimer = Timer.scheduledTimer(withTimeInterval: Double(OverlayPolicy.longPressMs) / 1000.0, repeats: false) { [weak self] _ in
            Task { @MainActor in
                guard let self else { return }
                if self.classifier.longPressTick(nowMs: self.monotonicMillis()) == .openMainApp {
                    self.onOpenMainApp?()
                }
            }
        }
    }

    private func handleMouseDragged(screenPoint: NSPoint) {
        guard let panel else { return }
        let event = classifier.mouseDragged(to: OverlayPoint(x: screenPoint.x, y: screenPoint.y))
        switch event {
        case .dragStarted:
            longPressTimer?.invalidate()
            longPressTimer = nil
            movePanel(dx: screenPoint.x - dragStartScreenPoint.x, dy: screenPoint.y - dragStartScreenPoint.y, panel: panel)
        case .dragged(let dx, let dy):
            movePanel(dx: dx, dy: dy, panel: panel)
        default:
            break
        }
    }

    private func handleMouseUp(event: NSEvent) {
        longPressTimer?.invalidate()
        longPressTimer = nil
        let action = classifier.mouseUp(timeMs: monotonicMillis(), clickCount: event.clickCount)
        switch action {
        case .dragEnded:
            snapAndPersist()
        case .toggleMode:
            onToggleMode?()
        case .muteCoach:
            onMuteCoach?()
        case .openMainApp:
            onOpenMainApp?()
        default:
            break
        }
    }

    private func movePanel(dx: CGFloat, dy: CGFloat, panel: OverlayPanel) {
        let proposed = NSPoint(x: dragStartPanelOrigin.x + dx, y: dragStartPanelOrigin.y + dy)
        let center = NSPoint(x: proposed.x + panel.frame.width / 2, y: proposed.y + panel.frame.height / 2)
        let screen = NSScreen.screens.first(where: { $0.frame.contains(center) }) ?? targetScreen(for: panel)
        let area = notchSafeArea(for: screen)
        let size = OverlaySize(width: panel.frame.width, height: panel.frame.height)
        let topLeft = topLeftFromCocoa(origin: proposed, size: size, screen: screen)
        let clamped = OverlayPlacement.clamp(position: topLeft, size: size, area: area, margin: 0)
        panel.setFrameOrigin(cocoaOrigin(fromTopLeft: clamped, size: size, screen: screen))
    }

    private func snapAndPersist() {
        guard let panel else { return }
        let screen = targetScreen(for: panel)
        let area = notchSafeArea(for: screen)
        let size = OverlaySize(width: panel.frame.width, height: panel.frame.height)
        let currentTopLeft = topLeftFromCocoa(origin: panel.frame.origin, size: size, screen: screen)
        let snapped = OverlayPlacement.snap(position: currentTopLeft, size: size, area: area)
        let targetOrigin = cocoaOrigin(fromTopLeft: snapped, size: size, screen: screen)

        NSAnimationContext.runAnimationGroup { context in
            context.duration = OverlayPolicy.snapDurationSeconds
            context.timingFunction = CAMediaTimingFunction(controlPoints: 0.2, 0.0, 0.0, 1.0)
            panel.animator().setFrameOrigin(targetOrigin)
        }

        let (fx, fy) = OverlayPlacement.toFractions(position: snapped, area: area)
        savedFractionX = fx
        savedFractionY = fy
        onPositionSaved?(fx, fy)
    }

    private func resizePanelPreservingEdge(oldSize: OverlaySize) {
        guard let panel, let host = hostingView else { return }
        host.layoutSubtreeIfNeeded()
        let fit = host.fittingSize
        let newSize = OverlaySize(
            width: max(OverdriveDimens.overlayCompactWidth, fit.width),
            height: max(OverdriveDimens.overlayCompactHeight, fit.height)
        )
        guard abs(newSize.height - oldSize.height) > 0.5 || abs(newSize.width - oldSize.width) > 0.5 else { return }
        let screen = targetScreen(for: panel)
        let area = notchSafeArea(for: screen)
        let currentTopLeft = topLeftFromCocoa(origin: panel.frame.origin, size: oldSize, screen: screen)
        let adjusted = OverlayPlacement.keepEdgeOnResize(current: currentTopLeft, oldSize: oldSize, newSize: newSize, area: area)
        let newOrigin = cocoaOrigin(fromTopLeft: adjusted, size: newSize, screen: screen)
        panel.setFrame(NSRect(origin: newOrigin, size: NSSize(width: newSize.width, height: newSize.height)), display: true, animate: false)
    }

    // MARK: - Coordinate Conversion (Cocoa bottom-left <-> OverlayPlacement top-left)

    private func currentPanelSize() -> OverlaySize {
        guard let panel else {
            return OverlaySize(width: OverdriveDimens.overlayCompactWidth, height: OverdriveDimens.overlayCompactHeight)
        }
        return OverlaySize(width: panel.frame.width, height: panel.frame.height)
    }

    private func targetScreen(for panel: OverlayPanel) -> NSScreen {
        panel.screen ?? NSScreen.main ?? NSScreen.screens[0]
    }

    private func notchSafeArea(for screen: NSScreen) -> OverlayRect {
        let topInset = screen.safeAreaInsets.top
        return OverlayRect(
            left: screen.frame.minX,
            top: 0 + topInset,
            right: screen.frame.maxX,
            bottom: screen.frame.height
        )
    }

    private func topLeftFromCocoa(origin: NSPoint, size: OverlaySize, screen: NSScreen) -> OverlayPoint {
        let topY = screen.frame.maxY - (origin.y + size.height)
        return OverlayPoint(x: origin.x, y: topY)
    }

    private func cocoaOrigin(fromTopLeft topLeft: OverlayPoint, size: OverlaySize, screen: NSScreen) -> NSPoint {
        let cocoaY = screen.frame.maxY - topLeft.y - size.height
        return NSPoint(x: topLeft.x, y: cocoaY)
    }

    private func monotonicMillis() -> Int64 {
        Int64(ProcessInfo.processInfo.systemUptime * 1000.0)
    }
}
