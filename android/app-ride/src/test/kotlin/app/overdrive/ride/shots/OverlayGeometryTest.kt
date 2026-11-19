package app.overdrive.ride.shots

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import app.overdrive.uikit.OverdriveTheme
import app.overdrive.uikit.overlay.OverlayCompact
import app.overdrive.uikit.overlay.OverlayDetailed
import app.overdrive.uikit.overlay.OverlaySettings
import app.overdrive.uikit.preview.PreviewSamples
import org.junit.Assert.assertEquals
import org.junit.Test

/** The overlay's measured size must be the numbers of design/system.md §5.1 (84, 282, 159 dp by 464 dp). */
class OverlayGeometryTest : ShotTest() {
    private fun size(content: @Composable () -> Unit): Pair<Dp, Dp> {
        rule.setContent { OverdriveTheme(content = content) }
        rule.waitForIdle()
        val bounds = rule.onRoot().getUnclippedBoundsInRoot()
        return bounds.width to bounds.height
    }

    @Test
    fun compactIs464By84() {
        val (w, h) = size { OverlayCompact(PreviewSamples.overlayErg, OverlaySettings()) }
        assertEquals(464f, w.value, 0.5f)
        assertEquals(84f, h.value, 0.5f)
    }

    @Test
    fun detailedWithWorkoutIs282High() {
        val (w, h) = size { OverlayDetailed(PreviewSamples.overlayErg, OverlaySettings()) }
        assertEquals(464f, w.value, 0.5f)
        assertEquals(282f, h.value, 0.5f)
    }

    @Test
    fun theGuardChangesTheFifthCellAndNotTheHeight() {
        val (w, h) = size { OverlayCompact(PreviewSamples.overlayGuard, OverlaySettings()) }
        assertEquals(464f, w.value, 0.5f)
        assertEquals(84f, h.value, 0.5f)
    }

    @Test
    fun theHeartRateLineAddsOneLineToCompact() {
        // 2 dp label-to-value gap, 4 dp, one 18 dp line (label style).
        val (w, h) = size { OverlayCompact(PreviewSamples.overlayHeartRate, OverlaySettings()) }
        assertEquals(464f, w.value, 0.5f)
        assertEquals(84f + 24f, h.value, 0.5f)
    }

    @Test
    fun theHeartRateLineAddsTheSameLineToDetailed() {
        val (w, h) = size { OverlayDetailed(PreviewSamples.overlayHeartRate, OverlaySettings()) }
        assertEquals(464f, w.value, 0.5f)
        assertEquals(282f + 24f, h.value, 0.5f)
    }

    @Test
    fun everyHeartRateStateIsTheSameHeight() {
        val states =
            listOf(
                PreviewSamples.overlayHeartRate,
                PreviewSamples.overlayHeartRateAbove,
                PreviewSamples.overlayHeartRateBelow,
                PreviewSamples.overlayHeartRateEasingIn,
                PreviewSamples.overlayStrapLost,
                PreviewSamples.overlayStrapDropped,
            )
        // One column of all six: the line is one line in each, so no state grows the overlay.
        val (_, h) =
            size {
                Column { for (state in states) OverlayCompact(state, OverlaySettings()) }
            }
        assertEquals(states.size * (84f + 24f), h.value, 1f)
    }

    @Test
    fun thePreviousAttemptLineAddsOneLineToTheIntervalSection() {
        val (w, h) = size { OverlayDetailed(PreviewSamples.overlayPreviousAttempt, OverlaySettings()) }
        assertEquals(464f, w.value, 0.5f)
        assertEquals(282f + 22f, h.value, 0.5f)
    }

    @Test
    fun detailedFreeRideIs159High() {
        val (w, h) = size { OverlayDetailed(PreviewSamples.overlayFreeRide, OverlaySettings()) }
        assertEquals(464f, w.value, 0.5f)
        assertEquals(159f, h.value, 0.5f)
    }
}
