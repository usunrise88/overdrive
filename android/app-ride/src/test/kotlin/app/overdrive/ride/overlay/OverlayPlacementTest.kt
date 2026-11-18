package app.overdrive.ride.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The snapping, clamping and persistence maths of the overlay window (design/system.md §5.5), on plain numbers. */
class OverlayPlacementTest {
    // The tablet at 2x: 1280 x 800 dp is 2560 x 1600 px. The overlay is 464 dp wide (928 px); compact is 84 dp (168 px).
    private val screen = PxRect(0, 0, 2560, 1600)
    private val compact = PxSize(928, 168)
    private val detailed = PxSize(928, 564)
    private val margin = 32 // 16 dp

    @Test
    fun clampKeepsTheWindowInsideTheScreen() {
        assertEquals(PxPoint(0, 0), OverlayPlacement.clamp(PxPoint(-50, -80), compact, screen))
        assertEquals(PxPoint(2560 - 928, 1600 - 168), OverlayPlacement.clamp(PxPoint(9000, 9000), compact, screen))
        assertEquals(PxPoint(700, 300), OverlayPlacement.clamp(PxPoint(700, 300), compact, screen))
    }

    @Test
    fun clampWithMarginKeepsTheMargin() {
        assertEquals(PxPoint(32, 32), OverlayPlacement.clamp(PxPoint(0, 0), compact, screen, margin))
    }

    @Test
    fun clampWithAWindowLargerThanTheRoomDoesNotThrowAndAlignsToTheStart() {
        val tooWide = PxSize(3000, 168)
        assertEquals(PxPoint(0, 100), OverlayPlacement.clamp(PxPoint(500, 100), tooWide, screen))
    }

    @Test
    fun releaseLeftOfCentreSnapsToTheLeftEdge() {
        val snapped = OverlayPlacement.snap(PxPoint(400, 600), compact, screen, margin)
        assertEquals(PxPoint(margin, 600), snapped)
    }

    @Test
    fun releaseRightOfCentreSnapsToTheRightEdge() {
        val snapped = OverlayPlacement.snap(PxPoint(1200, 600), compact, screen, margin)
        assertEquals(PxPoint(2560 - margin - 928, 600), snapped)
    }

    @Test
    fun theSideIsDecidedByTheWindowsCentre() {
        // A 928 px window centred exactly on the screen's middle goes right (centre is not left of the middle).
        val centred = OverlayPlacement.snap(PxPoint(1280 - 464, 600), compact, screen, margin)
        assertEquals(2560 - margin - 928, centred.x)
        // One pixel to the left goes left.
        val justLeft = OverlayPlacement.snap(PxPoint(1280 - 464 - 1, 600), compact, screen, margin)
        assertEquals(margin, justLeft.x)
    }

    @Test
    fun releaseClampsVerticallyWithTheMarginOnBothEnds() {
        assertEquals(margin, OverlayPlacement.snap(PxPoint(0, -200), compact, screen, margin).y)
        assertEquals(1600 - margin - 168, OverlayPlacement.snap(PxPoint(0, 5000), compact, screen, margin).y)
    }

    @Test
    fun withoutASavedPositionTheOverlayStartsTopRight() {
        assertEquals(PxPoint(2560 - margin - 928, margin), OverlayPlacement.defaultPosition(compact, screen, margin))
        assertEquals(
            OverlayPlacement.defaultPosition(compact, screen, margin),
            OverlayPlacement.fromFractions(null, null, compact, screen, margin),
        )
        assertEquals(
            OverlayPlacement.defaultPosition(compact, screen, margin),
            OverlayPlacement.fromFractions(0.3f, null, compact, screen, margin),
        )
    }

    @Test
    fun fractionsAreTheTopLeftCornerOverTheScreen() {
        val (fx, fy) = OverlayPlacement.toFractions(PxPoint(640, 400), screen)
        assertEquals(0.25f, fx, 1e-6f)
        assertEquals(0.25f, fy, 1e-6f)
    }

    @Test
    fun aSnappedPositionSurvivesTheRoundTripThroughFractions() {
        val right = OverlayPlacement.snap(PxPoint(1500, 700), compact, screen, margin)
        val (fx, fy) = OverlayPlacement.toFractions(right, screen)
        assertEquals(right, OverlayPlacement.fromFractions(fx, fy, compact, screen, margin))
        val left = OverlayPlacement.snap(PxPoint(100, 90), compact, screen, margin)
        val (lx, ly) = OverlayPlacement.toFractions(left, screen)
        assertEquals(left, OverlayPlacement.fromFractions(lx, ly, compact, screen, margin))
    }

    @Test
    fun savedFractionsOutsideTheRangeOrNotFiniteAreHandled() {
        val topLeft = OverlayPlacement.fromFractions(-3f, -3f, compact, screen, margin)
        assertEquals(PxPoint(margin, margin), topLeft)
        val bottomRight = OverlayPlacement.fromFractions(9f, 9f, compact, screen, margin)
        assertEquals(PxPoint(2560 - margin - 928, 1600 - margin - 168), bottomRight)
        assertEquals(
            OverlayPlacement.defaultPosition(compact, screen, margin),
            OverlayPlacement.fromFractions(Float.NaN, 0.5f, compact, screen, margin),
        )
    }

    @Test
    fun aRotationToPortraitKeepsTheWindowOnTheScreenAndOnTheSavedSide() {
        val portrait = PxRect(0, 0, 1600, 2560)
        // Saved at the right edge of the landscape screen.
        val (fx, fy) = OverlayPlacement.toFractions(PxPoint(2560 - margin - 928, 200), screen)
        val moved = OverlayPlacement.fromFractions(fx, fy, compact, portrait, margin)
        assertEquals(1600 - margin - 928, moved.x)
        assertTrue(moved.y in margin..(2560 - margin - 168))
    }

    @Test
    fun aWindowAtTheBottomStaysAtTheBottomWhenItGrows() {
        val atBottom = PxPoint(2560 - margin - 928, 1600 - margin - 168)
        val grown =
            OverlayPlacement.keepEdgeOnResize(
                atBottom,
                oldHeight = 168,
                newSize = detailed,
                area = screen,
                margin = margin,
                tolerance = 16,
            )
        assertEquals(1600 - margin - 564, grown.y)
        // And shrinking again puts it back at the bottom.
        val shrunk =
            OverlayPlacement.keepEdgeOnResize(
                grown,
                oldHeight = 564,
                newSize = compact,
                area = screen,
                margin = margin,
                tolerance = 16,
            )
        assertEquals(atBottom.y, shrunk.y)
    }

    @Test
    fun aWindowNotAtTheBottomKeepsItsTopWhenItGrows() {
        val grown =
            OverlayPlacement.keepEdgeOnResize(
                PxPoint(margin, 300),
                oldHeight = 168,
                newSize = detailed,
                area = screen,
                margin = margin,
                tolerance = 16,
            )
        assertEquals(300, grown.y)
    }

    @Test
    fun aGrowingWindowNearTheBottomIsPulledBackIntoTheScreen() {
        val grown =
            OverlayPlacement.keepEdgeOnResize(
                PxPoint(margin, 1200),
                oldHeight = 168,
                newSize = detailed,
                area = screen,
                margin = margin,
                tolerance = 16,
            )
        assertEquals(1600 - margin - 564, grown.y)
    }

    @Test
    fun theSnapSlideDeceleratesAndEndsExactlyOnTheTarget() {
        val slide = SnapAnimation(fromX = 1000, toX = 32, durationMillis = 180)
        assertEquals(1000, slide.xAt(0))
        assertEquals(32, slide.xAt(180))
        assertEquals(32, slide.xAt(500))
        // Eased out: more than half the way after half the time.
        val half = slide.xAt(90)
        assertTrue("x after half the time: $half", half < (1000 + 32) / 2)
        // Monotonic.
        var previous = slide.xAt(0)
        for (t in 1..180L) {
            val x = slide.xAt(t)
            assertTrue(x <= previous)
            previous = x
        }
        assertTrue(!slide.isFinished(179))
        assertTrue(slide.isFinished(180))
    }
}
