package app.overdrive.ride.shots

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import app.overdrive.core.live.LiveCoachState
import app.overdrive.ride.live.LiveCoachUi
import app.overdrive.ride.overlay.toOverlayCoach
import app.overdrive.uikit.OverdriveTheme
import app.overdrive.uikit.overlay.OverlayCoach
import app.overdrive.uikit.overlay.OverlayCoachControl
import app.overdrive.uikit.overlay.OverlayCompact
import app.overdrive.uikit.overlay.OverlayContent
import app.overdrive.uikit.overlay.OverlayDetailed
import app.overdrive.uikit.overlay.OverlayMode
import app.overdrive.uikit.overlay.OverlaySettings
import app.overdrive.uikit.preview.PreviewSamples
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live coach under the overlay (design/system.md §5.7): the panel is exactly what it was, with nothing of the coach or with all of it; the control is 132 by 40 dp 6 dp below the panel; the plate is 464 dp wide and one or
 * two lines high, below the control, so the control never moves when a cue comes and goes; the notice is the control's height. Measured, and a picture of each state in `build/shots/`.
 */
class OverlayCoachGeometryTest : ShotTest() {
    private val on = OverlayCoach(control = OverlayCoachControl.On)
    private val oneLine = "Ten minutes at 247 watts."
    private val twoLines = "Ten minutes at 247 watts. Smooth first two minutes, then settle in and hold it steady."

    /** Draws every variant in one column, each under a tag, and measures each: the compose rule takes one `setContent` a test. */
    private fun measure(vararg variants: Pair<String, @Composable () -> Unit>): Map<String, Pair<Dp, Dp>> {
        rule.setContent {
            OverdriveTheme {
                Column(Modifier, Arrangement.spacedBy(8.dp)) {
                    for ((tag, content) in variants) Box(Modifier.testTag(tag)) { content() }
                }
            }
        }
        rule.waitForIdle()
        return variants.associate { (tag, _) ->
            val bounds = rule.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            tag to (bounds.width to bounds.height)
        }
    }

    private fun settings(mode: OverlayMode = OverlayMode.Compact) = OverlaySettings(mode = mode)

    @Test
    fun withNothingOfTheCoachTheOverlayIsExactlyThePanel() {
        val sizes =
            measure(
                "none" to { OverlayContent(PreviewSamples.overlayErg, settings(), coach = OverlayCoach.None) },
                "panel" to { OverlayCompact(PreviewSamples.overlayErg, settings()) },
            )
        assertEquals(464f, sizes.getValue("none").first.value, 0.5f)
        assertEquals(84f, sizes.getValue("none").second.value, 0.5f)
        assertEquals(sizes.getValue("panel"), sizes.getValue("none"))
    }

    @Test
    fun theControlIsOneRowOf40DpSixBelowThePanelInBothModes() {
        val sizes =
            measure(
                "compact" to { OverlayContent(PreviewSamples.overlayErg, settings(), coach = on) },
                "detailed" to { OverlayContent(PreviewSamples.overlayErg, settings(OverlayMode.Detailed), coach = on) },
                "panel" to { OverlayDetailed(PreviewSamples.overlayErg, settings(OverlayMode.Detailed)) },
            )
        assertEquals(464f, sizes.getValue("compact").first.value, 0.5f)
        assertEquals(84f + 6f + 40f, sizes.getValue("compact").second.value, 0.5f)
        assertEquals(sizes.getValue("panel").second.value + 6f + 40f, sizes.getValue("detailed").second.value, 0.5f)
        assertEquals(282f + 46f, sizes.getValue("detailed").second.value, 0.5f)
    }

    @Test
    fun aPlateOfOneLineAndOneOfTwoSitBelowTheControlAndNothingGoesBeyondTwo() {
        val sizes =
            measure(
                "one" to { OverlayContent(PreviewSamples.overlayErg, settings(), coach = on.copy(plate = oneLine)) },
                "two" to { OverlayContent(PreviewSamples.overlayErg, settings(), coach = on.copy(plate = twoLines)) },
                "long" to
                    {
                        OverlayContent(
                            PreviewSamples.overlayErg,
                            settings(),
                            coach = on.copy(plate = twoLines.repeat(3)),
                        )
                    },
            )
        assertEquals(84f + 6f + 40f + 6f + 46f, sizes.getValue("one").second.value, 0.5f)
        assertEquals(464f, sizes.getValue("two").first.value, 0.5f)
        assertEquals(84f + 6f + 40f + 6f + 68f, sizes.getValue("two").second.value, 0.5f)
        assertEquals("cut at two lines", sizes.getValue("two").second.value, sizes.getValue("long").second.value, 0.5f)
    }

    @Test
    fun theNoticeTakesTheControlsPlaceAtTheControlsHeight() {
        val sizes =
            measure(
                "notice" to {
                    OverlayContent(
                        PreviewSamples.overlayErg,
                        settings(),
                        coach = OverlayCoach(notice = OverlayCoach.UNAVAILABLE),
                    )
                },
            )
        assertEquals(84f + 6f + 40f, sizes.getValue("notice").second.value, 0.5f)
    }

    @Test
    fun theControlDoesNotMoveWhenThePlateComesAndGoesOrTheStateChanges() {
        var bounds: Rect? = null
        var coach by mutableStateOf(on)
        rule.setContent {
            OverdriveTheme {
                OverlayContent(PreviewSamples.overlayErg, settings(), coach = coach, onControlBounds = { bounds = it })
            }
        }
        val seen = mutableListOf<Rect>()
        val states =
            listOf(
                on,
                on.copy(plate = oneLine),
                on.copy(plate = twoLines),
                OverlayCoach(OverlayCoachControl.Muted, plate = oneLine),
            )
        for (state in states) {
            coach = state
            rule.waitForIdle()
            seen += checkNotNull(bounds)
        }
        assertTrue("one place for all four: $seen", seen.all { it == seen.first() })
        val first = seen.first()
        val density = 2f // the tablet's 2x
        assertEquals(132f * density, first.width, 1f)
        assertEquals(40f * density, first.height, 1f)
        assertEquals("under the panel and 6 dp from it", (84f + 6f) * density, first.top, 1f)
        assertEquals("under the panel's left edge", 0f, first.left, 1f)
    }

    @Test
    fun theControlIsReportedGoneWhenTheSessionEnds() {
        var bounds: Rect? = Rect(1f, 1f, 2f, 2f)
        var coach by mutableStateOf(on)
        rule.setContent {
            OverdriveTheme {
                OverlayContent(PreviewSamples.overlayErg, settings(), coach = coach, onControlBounds = { bounds = it })
            }
        }
        rule.waitForIdle()
        assertTrue(bounds != null && bounds != Rect(1f, 1f, 2f, 2f))
        coach = OverlayCoach.None
        rule.waitForIdle()
        assertNull(bounds)
    }

    @Test
    fun theLiveCoachStateIsMappedToWhatTheOverlayShows() {
        assertEquals(OverlayCoach.None, LiveCoachUi.None.toOverlayCoach())
        assertEquals(OverlayCoach.None, LiveCoachUi(LiveCoachState.Off, plate = "left over").toOverlayCoach())
        assertEquals(
            OverlayCoach(notice = "Live coach unavailable"),
            LiveCoachUi(LiveCoachState.Unavailable).toOverlayCoach(),
        )
        assertEquals(on, LiveCoachUi(LiveCoachState.On(muted = false)).toOverlayCoach())
        assertEquals(
            OverlayCoach(control = OverlayCoachControl.Muted),
            LiveCoachUi(LiveCoachState.On(muted = true)).toOverlayCoach(),
        )
        assertEquals(on.copy(plate = "Hold it."), LiveCoachUi(LiveCoachState.On(false), "Hold it.").toOverlayCoach())
        assertEquals("Coach on", OverlayCoachControl.On.words)
        assertEquals("Coach muted", OverlayCoachControl.Muted.words)
    }

    @Test
    fun picturesOfTheStates() {
        shot("overlay--coach-states") {
            OverdriveTheme {
                Box(Modifier.size(960.dp, 760.dp)) {
                    Column(Modifier, Arrangement.spacedBy(16.dp)) {
                        OverlayContent(PreviewSamples.overlayErg, settings(), coach = on)
                        OverlayContent(PreviewSamples.overlayErg, settings(), coach = on.copy(plate = twoLines))
                        OverlayContent(
                            PreviewSamples.overlayErg,
                            settings(),
                            coach = OverlayCoach(control = OverlayCoachControl.Muted),
                        )
                        OverlayContent(
                            PreviewSamples.overlayErg,
                            settings(),
                            coach = OverlayCoach(notice = OverlayCoach.UNAVAILABLE),
                        )
                    }
                }
            }
        }
    }
}
