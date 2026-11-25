package app.overdrive.core.record

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Summary maths on series worked out by hand or by an independent brute-force script (every window summed from
 * scratch, no running totals), so the engine's streaming computation is checked against something else.
 */
class RideSummaryTest {
    private val tolerance = 1e-9

    private fun samples(
        powers: List<Int?>,
        heartRates: List<Int?> = emptyList(),
        paused: Set<Int> = emptySet(),
    ) = powers.mapIndexed { index, power ->
        RideSample(
            timeOffsetSeconds = index + 1,
            power = power,
            heartRate = heartRates.getOrNull(index),
            paused = (index + 1) in paused,
            distanceMeters = (index + 1) * 8.0,
        )
    }

    private fun summaryOf(
        powers: List<Int?>,
        ftp: Int,
        heartRates: List<Int?> = emptyList(),
        paused: Set<Int> = emptySet(),
    ) = RideSummary.of(samples(powers, heartRates, paused), ftp)

    @Test
    fun `a constant 200 W for a minute at FTP 250`() {
        val summary = summaryOf(List(60) { 200 }, ftp = 250)
        assertEquals(60, summary.durationSeconds)
        assertEquals(60, summary.movingSeconds)
        assertEquals(200.0, summary.averagePower!!, tolerance)
        assertEquals(200.0, summary.normalizedPower!!, tolerance)
        assertEquals(200, summary.maxPower)
        assertEquals(12.0, summary.workKilojoules, tolerance) // 200 J/s for 60 s
        assertEquals(0.8, summary.intensityFactor!!, tolerance)
        // TSS = 60 s * 200 W * 0.8 / (250 W * 3600 s) * 100 = 9600 / 9000
        assertEquals(60 * 200 * 0.8 / (250 * 3600) * 100, summary.trainingStressScore!!, tolerance)
        assertEquals(480.0, summary.distanceMeters, tolerance)
    }

    @Test
    fun `an hour at FTP scores 100`() {
        val summary = summaryOf(List(3600) { 250 }, ftp = 250)
        assertEquals(250.0, summary.normalizedPower!!, tolerance)
        assertEquals(1.0, summary.intensityFactor!!, tolerance)
        assertEquals(100.0, summary.trainingStressScore!!, 1e-9)
        assertEquals(900.0, summary.workKilojoules, tolerance)
    }

    @Test
    fun `normalised power weights hard efforts worked out on 30 s at 100 W then 30 s at 300 W`() {
        // 31 complete windows; window i holds 30-i seconds of 100 W and i of 300 W, so its mean is 100 + 20 i / 3.
        // The mean of their fourth powers, taken to the fourth root, is 223.0694887793096 W (brute force).
        val summary = summaryOf(List(30) { 100 } + List(30) { 300 }, ftp = 200)
        assertEquals(200.0, summary.averagePower!!, tolerance)
        assertEquals(223.0694887793096, summary.normalizedPower!!, 1e-9)
        assertEquals(1.115347443896548, summary.intensityFactor!!, 1e-12)
        assertEquals(2.073333201010939, summary.trainingStressScore!!, 1e-9)
        assertEquals(12.0, summary.workKilojoules, tolerance)
    }

    @Test
    fun `alternating 0 and 400 W averages 200 W in every window so NP is exactly 200 despite the 4th power`() {
        // 45 samples exercise the ring buffer wrapping: 16 windows of 15 zeros and 15 values of 400 W each.
        val summary = summaryOf(List(45) { if (it % 2 == 0) 0 else 400 }, ftp = 250)
        assertEquals(200.0, summary.normalizedPower!!, tolerance)
        assertEquals(8800.0 / 45, summary.averagePower!!, tolerance) // 22 x 400 W over 45 s
        assertEquals(400, summary.maxPower)
    }

    @Test
    fun `an interval session 15 min at 150 W then 5 at 250 W then 5 at 50 W`() {
        val powers = List(300) { 150 } + List(300) { 250 } + List(300) { 50 }
        val summary = summaryOf(powers, ftp = 250)
        assertEquals(150.0, summary.averagePower!!, tolerance)
        assertEquals(195.66672625492666, summary.normalizedPower!!, 1e-9)
        assertEquals(250, summary.maxPower)
    }

    @Test
    fun `a window is only complete at 30 values so a short ride has no normalised power`() {
        val short = summaryOf(List(29) { 200 }, ftp = 250)
        assertEquals(200.0, short.averagePower!!, tolerance)
        assertNull(short.normalizedPower)
        assertNull(short.intensityFactor)
        assertNull(short.trainingStressScore)

        val minimal = summaryOf(List(30) { 200 }, ftp = 250)
        assertEquals(200.0, minimal.normalizedPower!!, tolerance)

        // 29 x 100 W, one 400 W, one 200 W: two windows, means 110 and 113.33..; their 4th-power mean's root.
        val two = summaryOf(List(29) { 100 } + listOf(400, 200), ftp = 250)
        assertEquals(111.70396279561588, two.normalizedPower!!, 1e-9)
    }

    @Test
    fun `paused seconds are left out of everything but the duration and the distance`() {
        val powers = List(40) { 200 }.toMutableList()
        powers[9] = 1000 // paused, must not count
        powers[10] = 1000
        val paused = setOf(10, 11, 12)
        val heartRates = List(40) { 140 }.toMutableList().also { it[9] = 190 }
        val summary = summaryOf(powers, ftp = 250, heartRates = heartRates, paused = paused)
        assertEquals(40, summary.durationSeconds)
        assertEquals(37, summary.movingSeconds)
        assertEquals(200, summary.maxPower)
        assertEquals(200.0, summary.averagePower!!, tolerance)
        assertEquals(200.0, summary.normalizedPower!!, tolerance)
        assertEquals(37 * 200 / 1000.0, summary.workKilojoules, tolerance)
        assertEquals(140.0, summary.averageHeartRate!!, tolerance)
        assertEquals(140, summary.maxHeartRate)
        assertEquals(320.0, summary.distanceMeters, tolerance)
    }

    @Test
    fun `seconds without a reading leave that quantity out of the series`() {
        val withGaps = List(15) { 200 } + listOf(null, null, null) + List(15) { 200 }
        val summary = summaryOf(withGaps, ftp = 250, heartRates = List(33) { if (it < 10) null else 150 })
        assertEquals(33, summary.durationSeconds)
        assertEquals(33, summary.movingSeconds)
        assertEquals(200.0, summary.averagePower!!, tolerance)
        assertEquals(200.0, summary.normalizedPower!!, tolerance) // 30 values, so one window
        assertEquals(6.0, summary.workKilojoules, tolerance)
        assertEquals(150.0, summary.averageHeartRate!!, tolerance)
        // The load counts the 30 s of the power series, not the 33 s of the ride.
        assertEquals(30 * 200 * 0.8 / (250 * 3600) * 100, summary.trainingStressScore!!, tolerance)
    }

    @Test
    fun `heart rate average and maximum`() {
        val summary = summaryOf(List(4) { 100 }, ftp = 200, heartRates = listOf(120, 130, null, 160))
        assertEquals(410.0 / 3, summary.averageHeartRate!!, tolerance)
        assertEquals(160, summary.maxHeartRate)
    }

    @Test
    fun `a ride with nothing measured has nothing to average`() {
        val empty = RideSummary.of(emptyList(), ftp = 200)
        assertEquals(0, empty.durationSeconds)
        assertEquals(0, empty.movingSeconds)
        assertNull(empty.averagePower)
        assertNull(empty.maxPower)
        assertNull(empty.averageHeartRate)
        assertNull(empty.maxHeartRate)
        assertNull(empty.normalizedPower)
        assertEquals(0.0, empty.workKilojoules)

        val blank = RideSummary.of(samples(List(10) { null }), ftp = 200)
        assertEquals(10, blank.durationSeconds)
        assertEquals(10, blank.movingSeconds)
        assertNull(blank.averagePower)
        assertEquals(0.0, blank.workKilojoules)
    }

    @Test
    fun `a zero-watt coast still counts as a power reading`() {
        val summary = summaryOf(List(30) { if (it < 15) 300 else 0 }, ftp = 200)
        assertEquals(150.0, summary.averagePower!!, tolerance)
        assertEquals(0, RideSummary.of(samples(listOf(0, 0)), 200).maxPower)
    }
}
