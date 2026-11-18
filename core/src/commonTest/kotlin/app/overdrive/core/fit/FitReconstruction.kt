package app.overdrive.core.fit

import app.overdrive.core.record.Lap
import app.overdrive.core.record.RideHeader
import app.overdrive.core.record.RideRecord
import app.overdrive.core.record.RideSample
import app.overdrive.core.record.RideSummary
import app.overdrive.core.record.RrInterval

private const val KMH_PER_MS = 3.6

/**
 * Reads a FIT file back into the parts of a [RideRecord] that FIT can carry, to compare with the record it was written
 * from. What FIT has no field for (gear, target, step, the paused flag, FTP, device names) comes back as the default.
 * Laps come from the `lap` messages, the RR intervals from the `hrv` messages in stream order, each stamped with the
 * second of the record before it.
 */
internal fun rideFromFit(file: FitFile): RideRecord {
    val session = file.messages("session").single()
    val startUnix = FitReader.unixSeconds(checkNotNull(session.raw("start_time")))
    val samples = mutableListOf<RideSample>()
    val intervals = mutableListOf<RrInterval>()
    for (message in file.messages) {
        when (message.name) {
            "record" -> {
                samples +=
                    RideSample(
                        timeOffsetSeconds = samples.size + 1,
                        power = message.raw("power")?.toInt(),
                        heartRate = message.raw("heart_rate")?.toInt(),
                        cadence = message.raw("cadence")?.toInt(),
                        speedKmh = checkNotNull(message.scaled("speed")) * KMH_PER_MS,
                        distanceMeters = checkNotNull(message.scaled("distance")),
                    )
            }

            "hrv" -> {
                intervals +=
                    message.field("time")!!.elements.map { RrInterval(samples.size, it.toInt()) }
            }
        }
    }
    val laps =
        file.messages("lap").map { lap ->
            val start = (FitReader.unixSeconds(checkNotNull(lap.raw("start_time"))) - startUnix).toInt()
            val seconds = (checkNotNull(lap.raw("total_elapsed_time")) / 1000).toInt()
            Lap(
                stepIndex = checkNotNull(lap.raw("message_index")).toInt(),
                startSeconds = start,
                endSeconds = start + seconds,
                averagePower = lap.raw("avg_power")?.toDouble(),
                averageHeartRate = lap.raw("avg_heart_rate")?.toDouble(),
            )
        }
    val elapsedMillis = checkNotNull(file.messages("activity").single().raw("total_timer_time"))
    return RideRecord(
        ride =
            RideHeader(
                id = "from-fit",
                startEpochMillis = startUnix * 1000,
                endEpochMillis =
                    startUnix * 1000 + elapsedMillis,
                ftp = 1,
            ),
        samples = samples,
        rrIntervals = intervals,
        laps = laps,
        summary = RideSummary.of(samples, ftp = 1),
    )
}
