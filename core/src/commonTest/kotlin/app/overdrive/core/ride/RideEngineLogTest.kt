package app.overdrive.core.ride

import app.overdrive.core.fit.FitFacts
import app.overdrive.core.fit.FitReader
import app.overdrive.core.fit.FitWriter
import app.overdrive.core.record.InMemoryByteStore
import app.overdrive.core.record.RideLog
import app.overdrive.core.record.RideLogRecorder
import app.overdrive.core.record.RideLogStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The real engine writing the crash-safe log (spec §4.2, acceptance criterion 5): a ride with a workout, a pause, a
 * trainer outage and RR intervals is recovered from the log exactly as the engine recorded it, whether it was finished
 * or the app was killed, and the recovered ride exports to a FIT file the independent reader accepts.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RideEngineLogTest {
    /** Seconds [from] to [to] of a ride that follows the trainer, with a pause, an outage and RR intervals. */
    private fun EngineRig.ride(
        to: Int,
        from: Int = 1,
    ) {
        for (second in from..to) {
            when (second) {
                in 30..34 -> {
                    second(power = 0, cadence = 0, bpm = 150, rr = listOf(400))
                }

                // cadence 0: auto-pause
                in 50..52 -> {
                    second(power = null, cadence = null, bpm = null, trainerSilent = true, strapSilent = true)
                }

                else -> {
                    second(
                        power = trainer.targetPowerWatts ?: 110,
                        cadence = 90,
                        bpm = 120 + second / 10,
                        rr = if (second % 3 == 0) listOf(600 + second, 610 + second) else emptyList(),
                    )
                }
            }
        }
    }

    @Test
    fun `a finished ride is recovered exactly as the engine recorded it`() =
        runTest {
            val log = InMemoryByteStore()
            val rig = EngineRig(this, workout = abcWorkout, extraRecorder = RideLogRecorder(log))
            rig.start()
            // The workout is 180 s of riding; the auto-pause and the outage add to the ride, so it ends a little later.
            var second = 0
            while (rig.now.state != RideState.FINISHED && second < 400) rig.ride(from = ++second, to = second)
            assertEquals(RideState.FINISHED, rig.now.state)

            val recovered = assertNotNull(RideLog.recover(log))
            assertEquals(RideLogStatus.FINISHED, recovered.status)
            assertEquals(rig.record, recovered.toRecord())
            assertEquals(second, recovered.samples.size)
            assertTrue(second > 180)
            assertTrue(recovered.samples.any { it.paused }, "the ride really had a pause")
            assertTrue(recovered.rrIntervals.isNotEmpty())
            assertTrue(log.isClosed)

            // And the file the ride exports to is a sound FIT file with a lap per step.
            val fit = FitReader.parse(FitWriter().write(recovered.toRecord()))
            assertEquals(emptyList(), FitFacts.problems(fit))
            assertEquals(second, fit.messages("record").size)
            assertEquals(3, fit.messages("lap").size)
        }

    @Test
    fun `a ride cut off by a kill is recovered up to its last second`() =
        runTest {
            val log = InMemoryByteStore()
            val rig = EngineRig(this, workout = abcWorkout, extraRecorder = RideLogRecorder(log))
            rig.start()
            rig.ride(to = 100) // the app is killed here: no more calls, no finish
            val live = rig.record

            val recovered = assertNotNull(RideLog.recover(log))
            assertEquals(RideLogStatus.INTERRUPTED, recovered.status)
            val record = recovered.toRecord()
            assertEquals(live.samples, record.samples)
            assertEquals(live.rrIntervals, record.rrIntervals)
            assertEquals(live.laps, record.laps)
            assertEquals(live.summary, record.summary)
            assertEquals(live.ride.copy(endEpochMillis = live.ride.startEpochMillis + 100_000), record.ride)

            // Killed in the middle of writing second 101's line: the same ride comes back, minus the stub.
            val stub = """3fa9c2d1 sample {"timeOffsetSeconds":101,"pow""".encodeToByteArray()
            val tornAtSecond101 = assertNotNull(RideLog.recover(log.readAll() + stub))
            assertEquals(record, tornAtSecond101.toRecord())
            assertEquals(stub.size, tornAtSecond101.discardedBytes)

            // Killed one byte short of second 100's line feed: that second is lost, the 99 before it are not.
            val short = assertNotNull(RideLog.recover(log.readAll().let { it.copyOf(it.size - 1) }))
            assertEquals(live.samples.take(99), short.samples)
        }
}
