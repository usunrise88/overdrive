import Foundation

public struct OverlayPoint: Equatable, Sendable {
    public var x: CGFloat
    public var y: CGFloat

    public init(x: CGFloat, y: CGFloat) {
        self.x = x
        self.y = y
    }
}

public struct OverlaySize: Equatable, Sendable {
    public var width: CGFloat
    public var height: CGFloat

    public init(width: CGFloat, height: CGFloat) {
        self.width = width
        self.height = height
    }
}

/// Top-left-origin screen bounds in points (already inset from the MacBook notch / safe area top).
public struct OverlayRect: Equatable, Sendable {
    public var left: CGFloat
    public var top: CGFloat
    public var right: CGFloat
    public var bottom: CGFloat

    public var width: CGFloat { max(0, right - left) }
    public var height: CGFloat { max(0, bottom - top) }

    public init(left: CGFloat, top: CGFloat, right: CGFloat, bottom: CGFloat) {
        self.left = left
        self.top = top
        self.right = right
        self.bottom = bottom
    }

    public init(cgRect: CGRect, safeTopInset: CGFloat = 0) {
        self.left = cgRect.minX
        self.top = cgRect.minY + safeTopInset
        self.right = cgRect.maxX
        self.bottom = cgRect.maxY
    }
}

/// Pure overlay placement math (design/system.md §5.5, ADR 0006, ADR 0044).
/// Coordinates use top-left origin so fractions are screen-size and notch-independent.
public enum OverlayPlacement {
    public static let defaultMargin: CGFloat = OverdriveDimens.overlayEdgeSnapMargin
    public static let bottomTolerance: CGFloat = 8.0

    public static func clamp(
        position: OverlayPoint,
        size: OverlaySize,
        area: OverlayRect,
        margin: CGFloat = 0
    ) -> OverlayPoint {
        let minX = area.left + margin
        let maxX = max(minX, area.right - margin - size.width)
        let minY = area.top + margin
        let maxY = max(minY, area.bottom - margin - size.height)
        return OverlayPoint(
            x: min(max(position.x, minX), maxX),
            y: min(max(position.y, minY), maxY)
        )
    }

    public static func snap(
        position: OverlayPoint,
        size: OverlaySize,
        area: OverlayRect,
        margin: CGFloat = defaultMargin
    ) -> OverlayPoint {
        let centerX = position.x + size.width / 2.0
        let areaMidX = (area.left + area.right) / 2.0
        let snappedX = centerX < areaMidX ? (area.left + margin) : (area.right - margin - size.width)
        return clamp(position: OverlayPoint(x: snappedX, y: position.y), size: size, area: area, margin: margin)
    }

    public static func defaultPosition(
        size: OverlaySize,
        area: OverlayRect,
        margin: CGFloat = defaultMargin
    ) -> OverlayPoint {
        clamp(
            position: OverlayPoint(x: area.right - margin - size.width, y: area.top + margin),
            size: size,
            area: area,
            margin: margin
        )
    }

    public static func fromFractions(
        x: Double?,
        y: Double?,
        size: OverlaySize,
        area: OverlayRect,
        margin: CGFloat = defaultMargin
    ) -> OverlayPoint {
        guard let fx = x, fx.isFinite, let fy = y, fy.isFinite else {
            return defaultPosition(size: size, area: area, margin: margin)
        }
        let cx = area.left + CGFloat(min(max(fx, 0.0), 1.0)) * area.width
        let cy = area.top + CGFloat(min(max(fy, 0.0), 1.0)) * area.height
        return snap(position: OverlayPoint(x: cx, y: cy), size: size, area: area, margin: margin)
    }

    public static func toFractions(position: OverlayPoint, area: OverlayRect) -> (Double, Double) {
        let fx = Double((position.x - area.left) / max(1.0, area.width))
        let fy = Double((position.y - area.top) / max(1.0, area.height))
        return (min(max(fx, 0.0), 1.0), min(max(fy, 0.0), 1.0))
    }

    /// Keeps a bottom-anchored overlay anchored to the bottom when switching between compact and detailed mode.
    public static func keepEdgeOnResize(
        current: OverlayPoint,
        oldSize: OverlaySize,
        newSize: OverlaySize,
        area: OverlayRect,
        margin: CGFloat = defaultMargin
    ) -> OverlayPoint {
        let bottomLimit = area.bottom - margin
        let currentBottom = current.y + oldSize.height
        let anchoredBottom = abs(bottomLimit - currentBottom) <= bottomTolerance
        let targetY = anchoredBottom ? (bottomLimit - newSize.height) : current.y
        return clamp(position: OverlayPoint(x: current.x, y: targetY), size: newSize, area: area, margin: margin)
    }
}

/// Overlay lifecycle & throttling rules (spec §4.6, ADR 0006, ADR 0044).
public enum OverlayPolicy {
    public static let showDelayMs: UInt64 = 300
    public static let minUpdateIntervalMs: Int64 = 1_000
    public static let dragSlop: CGFloat = 8.0
    public static let longPressMs: Int64 = 500
    public static let snapDurationSeconds: TimeInterval = Double(OverdriveMotion.overlaySnapDurationMs) / 1000.0

    public static func windowWanted(isLive: Bool, mainWindowVisible: Bool, alwaysShow: Bool) -> Bool {
        guard isLive else { return false }
        return alwaysShow || !mainWindowVisible
    }

    /// Rider-initiated actions bypass the 1 Hz rest throttle so the overlay responds immediately to Click presses.
    public static func isRiderAction(previous: OverlayUiState, next: OverlayUiState) -> Bool {
        previous.fifthCell != next.fifthCell ||
            previous.paused != next.paused ||
            previous.hold?.targetBpm != next.hold?.targetBpm
    }
}

/// Pure pointer gesture classifier for the non-activating overlay panel (click, double-click, long-press, drag, mute hit).
public final class OverlayGestureClassifier {
    public enum Event: Equatable {
        case none
        case dragStarted
        case dragged(dx: CGFloat, dy: CGFloat)
        case dragEnded
        case toggleMode
        case muteCoach
        case openMainApp
    }

    private var downPoint: OverlayPoint?
    private var downTimeMs: Int64 = 0
    private var downInMuteControl = false
    private var isDragging = false
    private var longPressFired = false
    private var lastTapTimeMs: Int64 = -10_000

    public init() {}

    public func mouseDown(at point: OverlayPoint, timeMs: Int64, inMuteControl: Bool) {
        downPoint = point
        downTimeMs = timeMs
        downInMuteControl = inMuteControl
        isDragging = false
        longPressFired = false
    }

    public func mouseDragged(to point: OverlayPoint) -> Event {
        guard let origin = downPoint, !longPressFired else { return .none }
        let dx = point.x - origin.x
        let dy = point.y - origin.y
        if !isDragging {
            if hypot(dx, dy) >= OverlayPolicy.dragSlop {
                isDragging = true
                return .dragStarted
            }
            return .none
        }
        return .dragged(dx: dx, dy: dy)
    }

    public func longPressTick(nowMs: Int64) -> Event {
        guard downPoint != nil, !isDragging, !longPressFired else { return .none }
        if nowMs - downTimeMs >= OverlayPolicy.longPressMs {
            longPressFired = true
            return .openMainApp
        }
        return .none
    }

    public func mouseUp(timeMs: Int64, clickCount: Int = 1) -> Event {
        defer {
            downPoint = nil
            downInMuteControl = false
            isDragging = false
            longPressFired = false
        }
        guard downPoint != nil else { return .none }
        if longPressFired { return .none }
        if isDragging { return .dragEnded }
        if timeMs - downTimeMs >= OverlayPolicy.longPressMs {
            return .openMainApp
        }
        if downInMuteControl {
            return .muteCoach
        }
        if clickCount >= 2 || (timeMs - lastTapTimeMs) <= 320 {
            lastTapTimeMs = -10_000
            return .openMainApp
        }
        lastTapTimeMs = timeMs
        return .toggleMode
    }

    public func cancel() {
        downPoint = nil
        downInMuteControl = false
        isDragging = false
        longPressFired = false
    }
}
