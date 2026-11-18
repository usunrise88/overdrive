package app.overdrive.core.record

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The crash-safety property of the ride log (spec §4.2, acceptance criterion 5): however the file ends or is damaged,
 * [RideLog.recover] never throws and gives back exactly the records before the first bad line, as a prefix of what was
 * written. A ride log cut at *every* byte offset, zero-filled from every offset (a file system that extended the file
 * but lost the data), given a garbage tail, and with every byte flipped in turn.
 */
class RideLogFuzzTest {
    /** A ride of 60 seconds with every kind of line: gaps, a pause, RR intervals in ones and threes, and an end. */
    private val written: RideRecord =
        run {
            val samples = SyntheticRide.samples()
            val picked =
                (1..60).map { second ->
                    // Seconds 1 to 60 of the synthetic ride, with a pause and gaps added so that short ride has them too.
                    val base = samples[second - 1]
                    when (second) {
                        in 10..12 -> base.copy(power = null, cadence = null, paused = true, speedKmh = 0.0)
                        in 20..22 -> base.copy(heartRate = null)
                        else -> base
                    }
                }
            val rr = SyntheticRide.rrIntervals().filter { it.timeOffsetSeconds <= 60 }
            RideRecordBuilder.recover(SyntheticRide.header, picked, rr, SYNTHETIC_START_MILLIS + 60_000)
        }

    private val bytes: ByteArray =
        run {
            val store = InMemoryByteStore()
            val recorder = RideLogRecorder(store)
            recorder.onRideStarted(written.ride.copy(endEpochMillis = null))
            val rrBySecond = written.rrIntervals.groupBy { it.timeOffsetSeconds }
            for (sample in written.samples) {
                recorder.onSample(sample)
                rrBySecond[sample.timeOffsetSeconds]?.let(recorder::onRrIntervals)
            }
            recorder.onRideFinished(written.ride.endEpochMillis!!)
            store.readAll()
        }

    /** The kind of each line and the offset just after its line feed. */
    private class Line(
        val kind: String,
        val end: Int,
    )

    private val lines: List<Line> =
        run {
            val out = mutableListOf<Line>()
            var start = 0
            for (i in bytes.indices) {
                if (bytes[i] == '\n'.code.toByte()) {
                    val body = bytes.decodeToString(start + 9, i)
                    out += Line(body.substringBefore(' '), i + 1)
                    start = i + 1
                }
            }
            out
        }

    /** What recovery must give for a log whose first [intact] lines are undamaged and whose next line is bad. */
    private fun assertRecovered(
        recovered: RecoveredRide?,
        intact: Int,
        context: String,
    ) {
        if (intact == 0) {
            assertNull(recovered, context)
            return
        }
        recovered ?: error("$context: expected a ride")
        val kept = lines.take(intact)
        val sampleCount = kept.count { it.kind == "sample" }
        assertEquals(written.samples.take(sampleCount), recovered.samples, "$context: samples")
        // The RR intervals of the whole ride that came before the cut: the same number of rr lines, in order.
        val rrLines = kept.count { it.kind == "rr" }
        val expectedRr =
            written.rrIntervals
                .groupBy { it.timeOffsetSeconds }
                .entries
                .sortedBy { it.key }
                .take(
                    rrLines,
                ).flatMap { it.value }
        assertEquals(expectedRr, recovered.rrIntervals, "$context: rr")
        assertEquals(written.ride.copy(endEpochMillis = null), recovered.header, "$context: header")
        val finished = kept.last().kind == "finished"
        assertEquals(finished, recovered.isFinished, "$context: finished")
        assertEquals(if (finished) written.ride.endEpochMillis else null, recovered.endEpochMillis, "$context: end")
        assertEquals(kept.last().end, recovered.validBytes, "$context: valid bytes")
    }

    private fun intactLinesBefore(offset: Int): Int = lines.count { it.end <= offset }

    @Test
    fun `the written log has the shape the fuzz expects`() {
        assertEquals("header", lines.first().kind)
        assertEquals("finished", lines.last().kind)
        assertEquals(60, lines.count { it.kind == "sample" })
        assertTrue(lines.count { it.kind == "rr" } > 30)
        assertTrue(bytes.size > 8_000)
        assertRecovered(RideLog.recover(bytes), lines.size, "unharmed")
    }

    @Test
    fun `cut at every byte offset, recovery gives the records before the cut`() {
        for (length in 0..bytes.size) {
            val recovered = RideLog.recover(bytes.copyOf(length))
            assertRecovered(recovered, intactLinesBefore(length), "cut at $length of ${bytes.size}")
        }
    }

    @Test
    fun `cut anywhere, the recovered samples are a prefix of the written ones and never shrink as the file grows`() {
        var previous = 0
        for (length in 0..bytes.size) {
            val samples = RideLog.recover(bytes.copyOf(length))?.samples.orEmpty()
            assertEquals(written.samples.take(samples.size), samples, "cut at $length")
            assertTrue(samples.size >= previous, "cut at $length lost samples")
            previous = samples.size
        }
        assertEquals(60, previous)
    }

    @Test
    fun `a file extended with zeros from any offset, as after a power cut, recovers the same`() {
        for (cut in 0..bytes.size step 1) {
            val damaged = bytes.copyOf(cut) + ByteArray(minOf(bytes.size - cut, 400))
            assertRecovered(RideLog.recover(damaged), intactLinesBefore(cut), "zeros from $cut")
        }
    }

    @Test
    fun `a garbage tail after a cut anywhere is dropped`() {
        val random = Random(1_118)
        for (cut in 0..bytes.size) {
            val garbage = random.nextBytes(random.nextInt(1, 120))
            val damaged = bytes.copyOf(cut) + garbage
            // Random bytes that happen to complete a line with a valid checksum are as unlikely as 2^-32.
            assertRecovered(RideLog.recover(damaged), intactLinesBefore(cut), "garbage after $cut")
        }
    }

    @Test
    fun `a flipped byte anywhere ends the log at its line, never earlier and never later`() {
        for (index in bytes.indices) {
            val damaged = bytes.copyOf().also { it[index] = (it[index].toInt() xor 0x01).toByte() }
            // The damaged line is the one containing the byte; everything before it is intact.
            assertRecovered(RideLog.recover(damaged), intactLinesBefore(index), "flip at $index")
        }
    }

    @Test
    fun `a whole line rewritten into another line breaks the checksum, not the log`() {
        // Swap the lines of samples 11 and 12: every checksum is intact, the order is not.
        val sampleLines = lines.withIndex().filter { it.value.kind == "sample" }
        val a = sampleLines[10].index
        val b = sampleLines[11].index
        val starts = listOf(0) + lines.map { it.end }

        fun line(i: Int) = bytes.copyOfRange(starts[i], starts[i + 1])
        val swapped =
            lines.indices
                .map {
                    if (it == a) {
                        line(b)
                    } else if (it == b) {
                        line(a)
                    } else {
                        line(it)
                    }
                }.fold(ByteArray(0)) { acc, part -> acc + part }
        val recovered = assertNotNull(RideLog.recover(swapped))
        assertEquals(10, recovered.samples.size)
    }

    @Test
    fun `the full ride of the synthetic generator survives cuts at the line boundaries`() {
        val ride = SyntheticRide.record()
        val store = InMemoryByteStore()
        val recorder = RideLogRecorder(store)
        recorder.onRideStarted(ride.ride.copy(endEpochMillis = null))
        val rr = ride.rrIntervals.groupBy { it.timeOffsetSeconds }
        for (sample in ride.samples) {
            recorder.onSample(sample)
            rr[sample.timeOffsetSeconds]?.let(recorder::onRrIntervals)
        }
        val all = store.readAll()
        for (cut in all.indices step 997) {
            val samples = RideLog.recover(all.copyOf(cut))?.samples.orEmpty()
            assertEquals(ride.samples.take(samples.size), samples)
        }
        assertEquals(300, RideLog.recover(all)!!.samples.size)
    }
}
