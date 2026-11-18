package app.overdrive.uikit.preview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.overdrive.uikit.OverdriveTheme
import app.overdrive.uikit.overlay.OverlayCompact
import app.overdrive.uikit.overlay.OverlayDetailed
import app.overdrive.uikit.overlay.OverlayMode
import app.overdrive.uikit.overlay.OverlaySettings
import app.overdrive.uikit.overlay.OverlayUiState

/** The synthetic video frames of the mock-ups (design/mockups), for judging the overlay's legibility. */
enum class Backdrop {
    Dark,
    Bright,
    Busy,
}

/** A flat stand-in for a video frame. Previews only: gradients are fine here, they never ship in the UI. */
@Composable
fun VideoBackdrop(
    kind: Backdrop,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        when (kind) {
            Backdrop.Dark -> {
                drawRect(Brush.verticalGradient(listOf(Color(0xFF05070A), Color(0xFF0D1116), Color(0xFF151A12))))
            }

            Backdrop.Bright -> {
                drawRect(
                    Brush.verticalGradient(
                        listOf(Color(0xFFF4F8FC), Color(0xFFE6EEF6), Color(0xFFFDFDFB), Color(0xFFE9ECE4)),
                    ),
                )
            }

            Backdrop.Busy -> {
                drawRect(Color(0xFF888888))
                val stripe = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Butt)
                var x = -size.height
                var toggle = 0
                while (x < size.width) {
                    val colour =
                        when (toggle % 3) {
                            0 -> Color.White
                            1 -> Color(0xFF111111)
                            else -> Color(0xFF6A9A3C)
                        }
                    drawLine(colour, Offset(x, size.height), Offset(x + size.height, 0f), stripe.width)
                    x += 8.dp.toPx() * 1.4f
                    toggle++
                }
                drawCircle(Color(0xFFFF5A3C), 60.dp.toPx(), Offset(size.width * 0.18f, size.height * 0.30f))
                drawCircle(Color(0xFF2FD0FF), 90.dp.toPx(), Offset(size.width * 0.62f, size.height * 0.70f))
                drawCircle(Color(0xFFFFE14A), 50.dp.toPx(), Offset(size.width * 0.85f, size.height * 0.22f))
                drawCircle(Color.White, 70.dp.toPx(), Offset(size.width * 0.40f, size.height * 0.85f))
            }
        }
    }
}

/** The overlay states of the mock-up `overlay--states`: ERG, free ride, paused, no heart rate, ERG at 105%. */
@Composable
fun OverlayStatesSheet() {
    OverdriveTheme {
        Box(Modifier.size(1280.dp, 800.dp)) {
            VideoBackdrop(Backdrop.Busy, Modifier.fillMaxSize())
            val compact = OverlaySettings(mode = OverlayMode.Compact)
            val detailed = OverlaySettings(mode = OverlayMode.Detailed)
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                OverlayCompact(PreviewSamples.overlayFreeRide, compact)
                OverlayCompact(PreviewSamples.overlayPaused, compact)
                OverlayCompact(PreviewSamples.overlayNoHeartRate, compact)
                OverlayCompact(PreviewSamples.overlayErgUp, compact)
            }
            Column(
                Modifier.align(Alignment.TopEnd).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                OverlayDetailed(PreviewSamples.overlayFreeRide, detailed)
                OverlayDetailed(PreviewSamples.overlayPaused, detailed)
            }
        }
    }
}

/** Compact and detailed side by side over a [kind] frame, as in `overlay--dark`, `--bright` and `--busy`. */
@Composable
fun OverlayOverVideo(
    kind: Backdrop,
    state: OverlayUiState = PreviewSamples.overlayErg,
) {
    OverdriveTheme {
        Box(Modifier.size(1280.dp, 800.dp)) {
            VideoBackdrop(kind, Modifier.fillMaxSize())
            OverlayCompact(state, OverlaySettings(), Modifier.align(Alignment.TopStart).offset(16.dp, 56.dp))
            OverlayDetailed(state, OverlaySettings(), Modifier.align(Alignment.TopEnd).offset((-16).dp, 56.dp))
        }
    }
}

@Preview(widthDp = 1280, heightDp = 800)
@Composable
private fun OverlayStatesPreview() = OverlayStatesSheet()

@Preview(widthDp = 1280, heightDp = 800)
@Composable
private fun OverlayDarkPreview() = OverlayOverVideo(Backdrop.Dark)

@Preview(widthDp = 1280, heightDp = 800)
@Composable
private fun OverlayBrightPreview() = OverlayOverVideo(Backdrop.Bright)

@Preview(widthDp = 1280, heightDp = 800)
@Composable
private fun OverlayBusyPreview() = OverlayOverVideo(Backdrop.Busy)

@Preview(widthDp = 480, heightDp = 100, backgroundColor = 0xFF888888, showBackground = true)
@Composable
private fun OverlayCompactPreview() =
    OverdriveTheme {
        Box(Modifier.padding(8.dp)) { OverlayCompact(PreviewSamples.overlayErg, OverlaySettings()) }
    }
