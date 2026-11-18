package app.overdrive.ride.shots

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
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
    private fun size(content: @androidx.compose.runtime.Composable () -> Unit): Pair<Dp, Dp> {
        rule.setContent { OverdriveTheme(content = content) }
        rule.waitForIdle()
        val bounds = rule.onRoot().getUnclippedBoundsInRoot()
        return bounds.width to bounds.height
    }

    @Test
    fun debugBounds() {
        rule.setContent { OverdriveTheme { OverlayCompact(PreviewSamples.overlayErg, OverlaySettings()) } }
        rule.waitForIdle()
        for (text in listOf("Power", "223", "Heart", "148", "Time", "32:17")) {
            val b = rule.onNodeWithText(text).getUnclippedBoundsInRoot()
            println("GEOM $text top=${b.top} bottom=${b.bottom} h=${b.height} left=${b.left} w=${b.width}")
        }
        println("GEOM root ${rule.onRoot().getUnclippedBoundsInRoot()}")
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
