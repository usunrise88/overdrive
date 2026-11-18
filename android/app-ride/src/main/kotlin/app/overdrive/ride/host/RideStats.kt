package app.overdrive.ride.host

import app.overdrive.core.ride.RideSnapshot
import app.overdrive.core.ride.RideState
import app.overdrive.uikit.chart.TracePoint
import kotlin.math.roundToInt

/** What the ride screen adds to the snapshot: running averages and the power trace. */
data class RideStats(
    val averagePower: Int? = null,
    val lapAveragePower: Int? = null,
    val trace: List<TracePoint> = emptyList(),
)

/**
 * Accumulates [RideStats] from the stream of snapshots, so the ride screen, the overlay and the finished summary all
 * agree and nothing is lost while the screen is not showing (the host feeds it, not the UI). One per ride.
 *
 * - **Average and lap average** count one reading per ride second (an `elapsedSeconds` value is counted once) while
 *   `RIDING`, skipping seconds without a power reading. A lap is the stretch in one workout step; the average restarts
 *   when the step changes, also when the rider skips back.
 * - **Trace** (design/system.md §8.2): the mean power as a fraction of FTP per 10 s of workout time, at the middle of its
 *   bucket. A bucket is published when the workout position leaves it, so the trace changes once per 10 s and never
 *   shows a half-filled point. Skipping jumps to the bucket of the new position, so the trace follows the profile.
 */
class RideStatsAccumulator(
    private val ftp: Int,
) {
    private class Bucket {
        var sum = 0L
        var count = 0
    }

    private var lastElapsed = -1
    private var sum = 0L
    private var count = 0
    private var lapStep = -1
    private var lapSum = 0L
    private var lapCount = 0
    private val buckets = sortedMapOf<Int, Bucket>()
    private var currentBucket = -1
    private var stats = RideStats()

    /** Feeds one snapshot and returns the statistics so far. The same instance comes back when nothing changed. */
    fun accept(snapshot: RideSnapshot): RideStats {
        if (snapshot.state != RideState.RIDING || snapshot.elapsedSeconds == lastElapsed) return stats
        lastElapsed = snapshot.elapsedSeconds
        val workout = snapshot.workout
        if (workout != null && workout.stepIndex != lapStep) {
            lapStep = workout.stepIndex
            lapSum = 0
            lapCount = 0
        }
        val power = snapshot.power
        var trace = stats.trace
        if (power != null) {
            sum += power
            count++
            lapSum += power
            lapCount++
            if (workout != null) {
                val id = workout.workoutElapsedSeconds / BUCKET_SECONDS
                if (id != currentBucket) {
                    currentBucket = id
                    trace = publishedTrace()
                }
                val bucket = buckets.getOrPut(id) { Bucket() }
                bucket.sum += power
                bucket.count++
            }
        }
        stats =
            RideStats(
                averagePower = if (count > 0) (sum.toDouble() / count).roundToInt() else null,
                lapAveragePower = if (lapCount > 0) (lapSum.toDouble() / lapCount).roundToInt() else null,
                trace = trace,
            )
        return stats
    }

    /** Every bucket except the one being filled, as trace points. */
    private fun publishedTrace(): List<TracePoint> =
        buckets
            .filterKeys { it != currentBucket }
            .map { (id, bucket) ->
                TracePoint(
                    id * BUCKET_SECONDS + BUCKET_SECONDS / 2,
                    (
                        bucket.sum.toDouble() /
                            bucket.count /
                            ftp
                    ).toFloat(),
                )
            }

    private companion object {
        const val BUCKET_SECONDS = 10
    }
}
