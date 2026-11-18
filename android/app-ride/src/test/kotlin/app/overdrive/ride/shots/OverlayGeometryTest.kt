package app.overdrive.ride.shots

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
    fun detailedFreeRideIs159High() {
        val (w, h) = size { OverlayDetailed(PreviewSamples.overlayFreeRide, OverlaySettings()) }
        assertEquals(464f, w.value, 0.5f)
        assertEquals(159f, h.value, 0.5f)
    }
}
