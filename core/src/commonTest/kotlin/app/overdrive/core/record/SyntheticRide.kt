package app.overdrive.core.record

/** 2026-11-18T10:00:00Z, the start time of the worked example in `docs/protocols/fit.md` section 6. */
internal const val SYNTHETIC_START_MILLIS = 1_794_996_000_000L

/**
 * A made-up ride of five minutes for the record, log and FIT tests and for the contract fixture
 * `contracts/fixtures/rides/valid/synthetic-5min.json`. It is nobody's data: every value is a formula of the second.
 *
 * Four workout steps (a warm-up and a hard step in ERG, a recovery, and a free ride with gears) at FTP 250 W, and every
 * kind of gap the engine produces: a manual pause (power and cadence read 0), a trainer outage (paused, power and cadence
 * null), a heart-rate dropout, and a power dropout that does not pause. RR intervals come in ones, twos and threes, and
 * not at all while the strap is silent.
 */
internal object SyntheticRide {
    const val DURATION = 300
    const val FTP = 250

    val header =
        RideHeader(
            id = "synthetic-5min",
            startEpochMillis = SYNTHETIC_START_MILLIS,
            workoutId = "synthetic-steps",
            ftp = FTP,
            devices = listOf("Test trainer", "Test strap"),
        )

    /** Steps: 0 is seconds 1 to 60, 1 is 61 to 150, 2 is 151 to 210, 3 is 211 to 300. */
    fun stepAt(second: Int): Int =
        when {
            second <= 60 -> 0
            second <= 150 -> 1
            second <= 210 -> 2
            else -> 3
        }

    private fun isManualPause(second: Int) = second in 121..130

    private fun isTrainerOutage(second: Int) = second in 171..175

    private fun hasHeartRate(second: Int) = second !in 141..145

    private fun hasPowerGap(second: Int) = second in 191..193

    fun heartRateAt(second: Int): Int = 100 + second / 5 + second % 3

    fun samples(): List<RideSample> {
        var distance = 0.0
        return (1..DURATION).map { second ->
            val step = stepAt(second)
            val target =
                when (step) {
                    0 -> 120
                    1 -> 220
                    2 -> 125
                    else -> null
                }
            val paused = isManualPause(second) || isTrainerOutage(second)
            val power =
                when {
                    isTrainerOutage(second) || hasPowerGap(second) -> null
                    isManualPause(second) -> 0
                    target != null -> target + (second * 7) % 11 - 5
                    else -> 150 + (second * 13) % 21
                }
            val cadence =
                when {
                    isTrainerOutage(second) -> null
                    isManualPause(second) -> 0
                    else -> 85 + (second * 3) % 12
                }
            val speedKmh = if (paused || power == null) 0.0 else 18.0 + power / 8.0
            distance += speedKmh / 3.6
            RideSample(
                timeOffsetSeconds = second,
                power = power,
                heartRate = if (hasHeartRate(second)) heartRateAt(second) else null,
                cadence = cadence,
                speedKmh = speedKmh,
                distanceMeters = distance,
                gear = if (step == 3) 10 + (second / 30) % 3 else null,
                targetPower = target,
                stepIndex = step,
                paused = paused,
            )
        }
    }

    /** RR intervals: one a second, two when the second is a multiple of 7, three at 11, none every fifth second. */
    fun rrIntervals(): List<RrInterval> =
        (1..DURATION).flatMap { second ->
            if (!hasHeartRate(second) || second % 5 == 0) {
                emptyList()
            } else {
                val count =
                    if (second % 11 == 0) {
                        3
                    } else if (second % 7 == 0) {
                        2
                    } else {
                        1
                    }
                List(count) { index -> RrInterval(second, 60_000 / heartRateAt(second) + index * 3 - 3) }
            }
        }

    fun record(): RideRecord =
        RideRecordBuilder.recover(
            header,
            samples(),
            rrIntervals(),
            SYNTHETIC_START_MILLIS + DURATION * 1000L,
        )
}

/**
 * The three seconds of the worked example: 120/121/122 bpm, 90/91/92 rpm, 200/210/220 W, 8.0/8.1/8.2 m/s and
 * 0/8.00/16.10 m. The same ride is the contract fixture `contracts/fixtures/rides/valid/fit-golden-3s.json`.
 */
internal object FitGoldenRide {
    fun record(): RideRecord {
        val header = RideHeader(id = "fit-golden", startEpochMillis = SYNTHETIC_START_MILLIS, ftp = 250)
        val speeds = listOf(8.0, 8.1, 8.2)
        val distances = listOf(0.0, 8.0, 16.1)
        val samples =
            (1..3).map {
                RideSample(
                    timeOffsetSeconds = it,
                    power = 190 + 10 * it,
                    heartRate = 119 + it,
                    cadence = 89 + it,
                    speedKmh = speeds[it - 1] * 3.6,
                    distanceMeters = distances[it - 1],
                )
            }
        return RideRecordBuilder.recover(header, samples, endEpochMillis = SYNTHETIC_START_MILLIS + 3_000)
    }
}
