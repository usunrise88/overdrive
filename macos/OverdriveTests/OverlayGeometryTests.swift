import XCTest
@testable import Overdrive

final class OverlayGeometryTests: XCTestCase {
    // Simulated 14" MacBook Pro logical screen (1512 x 982) with a 38 pt notch safe-area inset at the top.
    private let macBookNotchArea = OverlayRect(left: 0, top: 38, right: 1512, bottom: 982)
    private let compactSize = OverlaySize(width: 464, height: 84)
    private let detailedSize = OverlaySize(width: 464, height: 282)

    func testDefaultPositionClearsMacBookNotch() {
        let pos = OverlayPlacement.defaultPosition(size: compactSize, area: macBookNotchArea)
        XCTAssertEqual(pos.x, 1512 - 16 - 464, accuracy: 0.5)
        XCTAssertEqual(pos.y, 38 + 16, accuracy: 0.5)
    }

    func testSnapMovesToNearerHorizontalEdgeWithMargin() {
        let leftRelease = OverlayPlacement.snap(
            position: OverlayPoint(x: 200, y: 200),
            size: compactSize,
            area: macBookNotchArea
        )
        XCTAssertEqual(leftRelease.x, 16, accuracy: 0.5)
        XCTAssertEqual(leftRelease.y, 200, accuracy: 0.5)

        let rightRelease = OverlayPlacement.snap(
            position: OverlayPoint(x: 900, y: 200),
            size: compactSize,
            area: macBookNotchArea
        )
        XCTAssertEqual(rightRelease.x, 1512 - 16 - 464, accuracy: 0.5)
    }

    func testBottomAnchoredResizeGrowsUpward() {
        let bottomY = macBookNotchArea.bottom - 16 - compactSize.height
        let resized = OverlayPlacement.keepEdgeOnResize(
            current: OverlayPoint(x: 16, y: bottomY),
            oldSize: compactSize,
            newSize: detailedSize,
            area: macBookNotchArea
        )
        XCTAssertEqual(resized.y + detailedSize.height, macBookNotchArea.bottom - 16, accuracy: 0.5)
    }

    func testFractionsRoundTripPreservesEdge() {
        let original = OverlayPoint(x: 1512 - 16 - 464, y: 400)
        let (fx, fy) = OverlayPlacement.toFractions(position: original, area: macBookNotchArea)
        let restored = OverlayPlacement.fromFractions(x: fx, y: fy, size: compactSize, area: macBookNotchArea)
        XCTAssertEqual(restored.x, original.x, accuracy: 1.0)
        XCTAssertEqual(restored.y, original.y, accuracy: 2.0)
    }

    func testGestureClassifierTapDragMuteAndLongPress() {
        let classifier = OverlayGestureClassifier()

        // Single click toggles compact/detailed
        classifier.mouseDown(at: OverlayPoint(x: 100, y: 100), timeMs: 1000, inMuteControl: false)
        XCTAssertEqual(classifier.mouseUp(timeMs: 1120, clickCount: 1), .toggleMode)

        // Click inside mute control mutes coach without toggling mode
        classifier.mouseDown(at: OverlayPoint(x: 50, y: 120), timeMs: 2000, inMuteControl: true)
        XCTAssertEqual(classifier.mouseUp(timeMs: 2100, clickCount: 1), .muteCoach)

        // Drag past 8 pt slop starts dragging and suppresses tap on release
        classifier.mouseDown(at: OverlayPoint(x: 100, y: 100), timeMs: 3000, inMuteControl: false)
        XCTAssertEqual(classifier.mouseDragged(to: OverlayPoint(x: 104, y: 102)), .none)
        XCTAssertEqual(classifier.mouseDragged(to: OverlayPoint(x: 112, y: 100)), .dragStarted)
        XCTAssertEqual(classifier.mouseUp(timeMs: 3200, clickCount: 1), .dragEnded)

        // Long press 500 ms opens main window
        classifier.mouseDown(at: OverlayPoint(x: 100, y: 100), timeMs: 4000, inMuteControl: false)
        XCTAssertEqual(classifier.longPressTick(nowMs: 4500), .openMainApp)
        XCTAssertEqual(classifier.mouseUp(timeMs: 4600, clickCount: 1), .none)
    }

    func testRiderActionBypassesOneHzThrottle() {
        let base = OverlayUiState.sample
        let sameControlDifferentPower = OverlayUiState(
            power: 225, zone: 3, heartRate: 153, cadence: 90,
            elapsedSeconds: 1938, speedKmh: 34.7, distanceMeters: 18430,
            paused: false, fifthCell: base.fifthCell
        )
        XCTAssertFalse(OverlayPolicy.isRiderAction(previous: base, next: sameControlDifferentPower))

        let changedTarget = OverlayUiState(
            power: 222, zone: 3, heartRate: 152, cadence: 89,
            elapsedSeconds: 1937, speedKmh: 34.6, distanceMeters: 18420,
            paused: false, fifthCell: .target(watts: 230, intensityPercent: 105)
        )
        XCTAssertTrue(OverlayPolicy.isRiderAction(previous: base, next: changedTarget))
    }
}
