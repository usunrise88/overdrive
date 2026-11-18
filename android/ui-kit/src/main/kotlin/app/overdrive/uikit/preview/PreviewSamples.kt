package app.overdrive.uikit.preview

import app.overdrive.uikit.chart.ProfileData
import app.overdrive.uikit.chart.ProfileStep
import app.overdrive.uikit.chart.TracePoint
import app.overdrive.uikit.overlay.OverlayControl
import app.overdrive.uikit.overlay.OverlayInterval
import app.overdrive.uikit.overlay.OverlayUiState

/** Synthetic data for previews and screenshot tests. Nothing here is a real ride. */
object PreviewSamples {
    const val FTP: Int = 250

    /** The spec §8.1 example, Sweet spot 3×10: 65 minutes. */
    val sweetSpot: ProfileData =
        ProfileData(
            buildList {
                var start = 0

                fun add(
                    duration: Int,
                    from: Float?,
                    to: Float?,
                ) {
                    add(ProfileStep(start, duration, from, to))
                    start += duration
                }
                add(600, 0.45f, 0.70f)
                repeat(3) {
                    add(600, 0.88f, 0.88f)
                    add(300, 0.50f, 0.50f)
                }
                add(300, null, null)
                add(300, 0.55f, 0.40f)
            },
        )

    /** 32:17 into the workout, in the second sweet-spot effort with 2:43 left. */
    const val NOW_SECONDS: Int = 1937

    /** A synthetic power trace that follows the workout up to [NOW_SECONDS], one point per 10 s. */
    val trace: List<TracePoint> =
        buildList {
            var seed = 3L

            fun noise(): Float {
                seed = (seed * 1664525L + 1013904223L) % 4294967296L
                return seed / 4294967296f - 0.5f
            }
            var t = 0
            while (t <= NOW_SECONDS) {
                val step = sweetSpot.steps.first { t >= it.startSeconds && t < it.startSeconds + it.durationSeconds }
                val level =
                    if (step.from == null || step.to == null) {
                        0.62f
                    } else {
                        step.from + (step.to - step.from) * (t - step.startSeconds) / step.durationSeconds
                    }
                add(TracePoint(t, level + noise() * 0.07f))
                t += 10
            }
        }

    /** ERG step, compact: power 223 W in Z3, target 220 W at 100%. */
    val overlayErg =
        OverlayUiState(
            power = 223,
            zone = 3,
            heartRate = 148,
            cadence = 91,
            elapsedSeconds = 1937,
            paused = false,
            control = OverlayControl.Target(watts = 220, intensityPercent = 100),
            speedKmh = 34.6,
            distanceMeters = 18_420.0,
            interval =
                OverlayInterval(
                    name = "Sweet spot",
                    repeatText = "2 of 3",
                    remainingSeconds = 163,
                    next = "Recover · 5:00 · 125 W",
                    profile = sweetSpot,
                    nowSeconds = NOW_SECONDS,
                ),
        )

    val overlayFreeRide =
        overlayErg.copy(
            power = 182,
            heartRate = 139,
            cadence = 87,
            elapsedSeconds = 3849,
            control = OverlayControl.Gear(12),
            speedKmh = 31.9,
            distanceMeters = 35_070.0,
            interval = null,
        )

    val overlayPaused = overlayErg.copy(paused = true)

    val overlayNoHeartRate = overlayErg.copy(power = 214, heartRate = null)

    val overlayErgUp =
        overlayErg.copy(power = 233, zone = 4, control = OverlayControl.Target(watts = 231, intensityPercent = 105))
}
