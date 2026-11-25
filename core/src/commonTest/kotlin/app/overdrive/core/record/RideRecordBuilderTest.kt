package app.overdrive.core.record

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RideRecordBuilderTest {
    private val header =
        RideHeader(id = "r1", startEpochMillis = 1_000, workoutId = "w", ftp = 200, devices = listOf("Trainer"))

    private fun sample(
        offset: Int,
        step: Int? = null,
        power: Int? = 200,
        heartRate: Int? = 140,
        paused: Boolean = false,
    ) = RideSample(offset, power = power, heartRate = heartRate, stepIndex = step, paused = paused)

    @Test
    fun `laps tile the ride and average over their own seconds`() {
        val builder = RideRecordBuilder(header)
        // Step 0: seconds 1-3 at 100 W; step 1: seconds 4-8 at 200 W with a pause in 7-8 that must not count.
        for (second in 1..3) builder.addSample(sample(second, step = 0, power = 100, heartRate = 120))
        for (second in 4..6) builder.addSample(sample(second, step = 1, power = 200, heartRate = 150))
        for (second in 7..8) builder.addSample(sample(second, step = 1, power = 0, heartRate = 180, paused = true))
        val record = builder.build()
        assertEquals(
            listOf(
                Lap(stepIndex = 0, startSeconds = 0, endSeconds = 3, averagePower = 100.0, averageHeartRate = 120.0),
                Lap(stepIndex = 1, startSeconds = 3, endSeconds = 8, averagePower = 200.0, averageHeartRate = 150.0),
            ),
            record.laps,
        )
        assertEquals(listOf(3, 5), record.laps.map { it.durationSeconds })
        assertEquals(8, record.samples.size)
    }

    @Test
    fun `going back to an earlier step starts a new lap and a skipped step has none`() {
        val builder = RideRecordBuilder(header)
        listOf(0, 0, 1, 1, 1, 3, 3, 2, 2).forEachIndexed {
            index,
            step,
            ->
            builder.addSample(sample(index + 1, step = step))
        }
        assertEquals(listOf(0, 1, 3, 2), builder.build().laps.map { it.stepIndex })
        assertEquals(
            listOf(0 to 2, 2 to 5, 5 to 7, 7 to 9),
            builder.build().laps.map { it.startSeconds to it.endSeconds },
        )
    }

    @Test
    fun `a ride without a workout is one lap`() {
        val builder = RideRecordBuilder(header)
        for (second in 1..5) builder.addSample(sample(second, step = null, power = second * 10))
        val laps = builder.build().laps
        assertEquals(1, laps.size)
        assertNull(laps.single().stepIndex)
        assertEquals(0, laps.single().startSeconds)
        assertEquals(5, laps.single().endSeconds)
        assertEquals(30.0, laps.single().averagePower)
    }

    @Test
    fun `a lap with nothing measured has no averages`() {
        val builder = RideRecordBuilder(header)
        builder.addSample(sample(1, step = 0, power = null, heartRate = null))
        val lap = builder.build().laps.single()
        assertNull(lap.averagePower)
        assertNull(lap.averageHeartRate)
    }

    @Test
    fun `an empty ride has no laps and an empty summary`() {
        val record = RideRecordBuilder(header).build()
        assertTrue(record.laps.isEmpty())
        assertTrue(record.samples.isEmpty())
        assertEquals(0, record.summary.durationSeconds)
    }

    @Test
    fun `the record can be built at any time and keeps growing`() {
        val builder = RideRecordBuilder(header)
        builder.addSample(sample(1, step = 0))
        val early = builder.build()
        builder.addSample(sample(2, step = 0))
        builder.addSample(sample(3, step = 1))
        val later = builder.build()
        assertEquals(1, early.samples.size)
        assertEquals(1, early.laps.size)
        assertEquals(3, later.samples.size)
        assertEquals(2, later.laps.size)
        assertEquals(3, builder.sampleCount)
        assertEquals(3, later.summary.durationSeconds)
    }

    @Test
    fun `samples must come one for every second`() {
        val builder = RideRecordBuilder(header)
        assertFailsWith<IllegalArgumentException> { builder.addSample(sample(2)) }
        builder.addSample(sample(1))
        assertFailsWith<IllegalArgumentException> { builder.addSample(sample(1)) }
        assertFailsWith<IllegalArgumentException> { builder.addSample(sample(3)) }
        builder.addSample(sample(2))
    }

    @Test
    fun `rr intervals are kept in order`() {
        val builder = RideRecordBuilder(header)
        builder.addRrIntervals(listOf(RrInterval(1, 900), RrInterval(1, 910)))
        builder.addRrIntervals(listOf(RrInterval(2, 905)))
        assertEquals(listOf(900, 910, 905), builder.build().rrIntervals.map { it.intervalMs })
    }

    @Test
    fun `the end time comes from the builder call else from the header`() {
        val builder = RideRecordBuilder(header)
        assertNull(builder.build().ride.endEpochMillis)
        assertEquals(9_000, builder.build(endEpochMillis = 9_000).ride.endEpochMillis)
        assertEquals(5_000, RideRecordBuilder(header.copy(endEpochMillis = 5_000)).build().ride.endEpochMillis)
    }

    @Test
    fun `a recovered record equals the one built live`() {
        val live = RideRecordBuilder(header)
        val samples = (1..40).map { sample(it, step = it / 15, power = 150 + it, paused = it in 20..22) }
        val rr = listOf(RrInterval(3, 880), RrInterval(4, 870))
        samples.forEach(live::addSample)
        live.addRrIntervals(rr)
        assertEquals(
            live.build(endEpochMillis = 41_000),
            RideRecordBuilder.recover(header, samples, rr, endEpochMillis = 41_000),
        )
    }

    @Test
    fun `a recovered ride can be truncated anywhere`() {
        val samples = (1..25).map { sample(it, step = it / 10) }
        for (keep in listOf(0, 1, 9, 10, 11, 25)) {
            val record = RideRecordBuilder.recover(header, samples.take(keep))
            assertEquals(keep, record.samples.size)
            assertEquals(keep, record.summary.durationSeconds)
            assertEquals(keep, record.laps.sumOf { it.durationSeconds })
        }
    }

    @Test
    fun `the in-memory recorder follows the calls`() {
        val recorder = InMemoryRideRecorder()
        assertNull(recorder.record)
        assertFailsWith<IllegalStateException> { recorder.onSample(sample(1)) }

        recorder.onRideStarted(header)
        recorder.onSample(sample(1, step = 0))
        recorder.onRrIntervals(listOf(RrInterval(1, 800)))
        assertFalse(recorder.isFinished)
        assertEquals(1, recorder.record!!.samples.size)
        assertNull(recorder.record!!.ride.endEpochMillis)

        recorder.onRideFinished(7_000)
        assertTrue(recorder.isFinished)
        assertEquals(7_000, recorder.record!!.ride.endEpochMillis)
        assertEquals(1, recorder.record!!.rrIntervals.size)
        assertFailsWith<IllegalStateException> { recorder.onRideStarted(header) }
    }

    @Test
    fun `the records validate their fields`() {
        assertFailsWith<IllegalArgumentException> { RideHeader(id = "", startEpochMillis = 0, ftp = 200) }
        assertFailsWith<IllegalArgumentException> { RideHeader(id = "x", startEpochMillis = 0, ftp = 0) }
        assertFailsWith<IllegalArgumentException> { RideSample(timeOffsetSeconds = 0) }
        assertFailsWith<IllegalArgumentException> { RideSample(timeOffsetSeconds = 1, power = -1) }
        assertFailsWith<IllegalArgumentException> { RrInterval(timeOffsetSeconds = 1, intervalMs = 0) }
        assertFailsWith<IllegalArgumentException> {
            Lap(null, startSeconds = 5, endSeconds = 5, averagePower = null, averageHeartRate = null)
        }
    }
}
