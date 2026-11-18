package app.overdrive.ride.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

/** Tap, long press and drag on plain numbers: slop 8 dp at 2x is 16 px, the long-press timeout is 500 ms. */
class OverlayGesturesTest {
    private val events = mutableListOf<String>()
    private val config = GestureConfig(slopPx = 16f, longPressMillis = 500)
    private val detector =
        OverlayGestureDetector(
            config,
            object : OverlayGestureDetector.Output {
                override fun onDrag(
                    totalDx: Float,
                    totalDy: Float,
                ) {
                    events += "drag ${totalDx.toInt()},${totalDy.toInt()}"
                }

                override fun onDragEnd() {
                    events += "end"
                }

                override fun onTap() {
                    events += "tap"
                }

                override fun onLongPress() {
                    events += "long"
                }
            },
        )

    @Test
    fun aQuickReleaseInPlaceIsATap() {
        detector.down(100f, 100f, 0)
        detector.up(100f, 100f, 120)
        assertEquals(listOf("tap"), events)
    }

    @Test
    fun aReleaseJustBeforeTheTimeoutIsStillATap() {
        detector.down(100f, 100f, 1000)
        detector.up(100f, 100f, 1499)
        assertEquals(listOf("tap"), events)
    }

    @Test
    fun jitterInsideTheSlopStillCountsAsATap() {
        detector.down(100f, 100f, 0)
        detector.move(108f, 108f, 40) // 11 px away, inside 16 px
        detector.up(110f, 105f, 90)
        assertEquals(listOf("tap"), events)
    }

    @Test
    fun holdingForTheTimeoutIsALongPressAndTheReleaseAfterItDoesNothing() {
        detector.down(100f, 100f, 0)
        detector.timeout(499)
        assertEquals(emptyList<String>(), events)
        detector.timeout(500)
        detector.up(100f, 100f, 800)
        assertEquals(listOf("long"), events)
    }

    @Test
    fun aTimerThatFiresTwiceReportsOneLongPress() {
        detector.down(0f, 0f, 0)
        detector.timeout(500)
        detector.timeout(520)
        assertEquals(listOf("long"), events)
    }

    @Test
    fun aReleaseAfterTheTimeoutIsALongPressEvenIfTheTimerWasLate() {
        detector.down(100f, 100f, 0)
        detector.up(100f, 100f, 650)
        assertEquals(listOf("long"), events)
    }

    @Test
    fun aMoveInsideTheSlopAfterTheTimeoutReportsTheLongPress() {
        detector.down(100f, 100f, 0)
        detector.move(103f, 100f, 520)
        assertEquals(listOf("long"), events)
    }

    @Test
    fun movingPastTheSlopStartsADragWithTheTotalOffset() {
        detector.down(100f, 100f, 0)
        detector.move(110f, 100f, 16) // inside the slop: nothing yet
        assertEquals(emptyList<String>(), events)
        detector.move(130f, 140f, 32)
        detector.move(200f, 300f, 48)
        detector.up(210f, 310f, 64)
        assertEquals(listOf("drag 30,40", "drag 100,200", "drag 110,210", "end"), events)
    }

    @Test
    fun theSlopIsMeasuredAsADistanceNotPerAxis() {
        detector.down(0f, 0f, 0)
        detector.move(12f, 12f, 10) // 17 px diagonal, past 16
        assertEquals(listOf("drag 12,12"), events)
    }

    @Test
    fun aDragNeverBecomesATapOrALongPress() {
        detector.down(0f, 0f, 0)
        detector.move(50f, 0f, 20)
        detector.timeout(600)
        detector.move(0f, 0f, 700) // back at the start
        detector.up(0f, 0f, 800)
        // The release repeats the last position before it ends the drag, so the window never stops short of the finger.
        assertEquals(listOf("drag 50,0", "drag 0,0", "drag 0,0", "end"), events)
    }

    @Test
    fun aDragEvenAfterAHoldIsADragNotALongPress() {
        detector.down(0f, 0f, 0)
        detector.move(40f, 0f, 700) // slow start: the slop is checked first
        detector.up(40f, 0f, 750)
        assertEquals(listOf("drag 40,0", "drag 40,0", "end"), events)
    }

    @Test
    fun cancelEndsADragWhereItIsAndForgetsAPress() {
        detector.down(0f, 0f, 0)
        detector.move(60f, 0f, 10)
        detector.cancel()
        detector.up(60f, 0f, 20) // a stray up after the cancel does nothing
        assertEquals(listOf("drag 60,0", "end"), events)

        events.clear()
        detector.down(0f, 0f, 100)
        detector.cancel()
        detector.timeout(700)
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun eventsWithoutAPressAreIgnored() {
        detector.move(10f, 10f, 5)
        detector.up(10f, 10f, 10)
        detector.timeout(1000)
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun aNewPressAfterAGestureStartsFresh() {
        detector.down(0f, 0f, 0)
        detector.up(0f, 0f, 50)
        detector.down(300f, 300f, 1000)
        detector.up(300f, 300f, 1050)
        assertEquals(listOf("tap", "tap"), events)
    }

    @Test
    fun theDesignThresholdsAreEightDpAndHalfASecond() {
        val real = GestureConfig.forDensity(2f)
        assertEquals(16f, real.slopPx, 0f)
        assertEquals(500L, real.longPressMillis)
    }
}
