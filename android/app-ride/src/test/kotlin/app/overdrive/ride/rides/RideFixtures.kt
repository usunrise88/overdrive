package app.overdrive.ride.rides

import app.overdrive.core.record.RideHeader
import app.overdrive.core.record.RideRecord
import app.overdrive.core.record.RideRecordBuilder
import app.overdrive.core.record.RideSample
import app.overdrive.data.rides.RideLogWriter
import app.overdrive.data.rides.RideStore
import java.io.File
import java.nio.file.Files

/** Made-up rides on made-up disks for the library and host tests. Nobody's data. */
object RideFixtures {
    /** 2026-11-18T10:00:00Z. */
    const val START = 1_794_996_000_000L

    fun directory(): File = Files.createTempDirectory("overdrive-library").toFile().also { it.deleteOnExit() }

    fun header(
        id: String,
        start: Long,
        workoutId: String? = null,
    ) = RideHeader(
        id = id,
        startEpochMillis = start,
        workoutId = workoutId,
        ftp = 250,
        devices = listOf("Test trainer"),
    )

    fun sample(second: Int) =
        RideSample(
            timeOffsetSeconds = second,
            power = 150 + second % 40,
            heartRate = 120 + second % 20,
            cadence = 88,
            speedKmh = 30.0,
            distanceMeters = second * 8.3,
        )

    fun record(
        id: String,
        start: Long,
        seconds: Int,
        workoutId: String? = null,
    ): RideRecord =
        RideRecordBuilder.recover(
            header(id, start, workoutId),
            (1..seconds).map(::sample),
            endEpochMillis = start + seconds * 1000L,
        )

    /** Leaves a ride log in [store]'s folder as the app does while riding: complete up to [seconds], with the finish line if [finished]. */
    fun writeLog(
        store: RideStore,
        id: String,
        start: Long,
        seconds: Int,
        finished: Boolean,
        title: String = "Sweet spot 3×10",
        workoutId: String? = null,
    ) {
        val writer = RideLogWriter(store, title)
        writer.onRideStarted(header(id, start, workoutId))
        for (second in 1..seconds) writer.onSample(sample(second))
        if (finished) writer.onRideFinished(start + seconds * 1000L)
    }
}
