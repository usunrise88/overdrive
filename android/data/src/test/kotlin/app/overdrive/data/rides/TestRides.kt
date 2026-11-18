package app.overdrive.data.rides

import app.overdrive.core.record.RideHeader
import app.overdrive.core.record.RideRecord
import app.overdrive.core.record.RideRecordBuilder
import app.overdrive.core.record.RideSample
import app.overdrive.core.record.RrInterval
import java.io.File

/** Made-up rides for the storage tests: every value is a formula of the second, nobody's data. */
object TestRides {
    /** 2026-11-18T10:00:00Z. */
    const val START = 1_794_996_000_000L

    fun header(
        id: String = "ride-1",
        start: Long = START,
        workoutId: String? = null,
    ) = RideHeader(
        id = id,
        startEpochMillis = start,
        workoutId = workoutId,
        ftp = 250,
        devices = listOf("Test trainer", "Test strap"),
    )

    fun sample(second: Int) =
        RideSample(
            timeOffsetSeconds = second,
            power = 150 + second % 40,
            heartRate = 110 + second % 30,
            cadence = 85 + second % 10,
            speedKmh = 30.0 + second % 3,
            distanceMeters = second * 8.3,
            paused = second % 50 == 0,
        )

    fun rr(second: Int) = RrInterval(second, 700 + second % 90)

    fun samples(seconds: Int) = (1..seconds).map(::sample)

    fun record(
        seconds: Int,
        id: String = "ride-1",
        start: Long = START,
        workoutId: String? = null,
    ): RideRecord =
        RideRecordBuilder.recover(
            header(id, start, workoutId),
            samples(seconds),
            (1..seconds step 3).map(::rr),
            start + seconds * 1000L,
        )

    /**
     * Writes a ride into [store] the way the app does, through [RideLogWriter]: [seconds] samples, and the finished line
     * if [finished]. Returns the record the same ride would have in memory.
     */
    fun writeRide(
        store: RideStore,
        seconds: Int,
        finished: Boolean,
        id: String = "ride-1",
        start: Long = START,
        title: String = "Test ride",
        workoutId: String? = null,
    ): RideRecord {
        val writer = RideLogWriter(store, title)
        val record = record(seconds, id, start, workoutId)
        writer.onRideStarted(record.ride.copy(endEpochMillis = null))
        val rr = record.rrIntervals.groupBy { it.timeOffsetSeconds }
        for (sample in record.samples) {
            writer.onSample(sample)
            rr[sample.timeOffsetSeconds]?.let(writer::onRrIntervals)
        }
        if (finished) writer.onRideFinished(start + seconds * 1000L)
        return record
    }

    /** A new, empty directory for one test. */
    fun tempDirectory(): File =
        java.nio.file.Files
            .createTempDirectory("overdrive-rides")
            .toFile()
            .also { it.deleteOnExit() }
}

/**
 * A tiny independent check of a FIT file's framing (fit.md section 1): the 14-byte header with its CRC, the data size
 * and the CRC-16 of the whole file. The algorithm is Garmin's published nibble-table one, written out again here so the
 * test does not use the code it checks.
 */
object FitFileCheck {
    private val table =
        intArrayOf(
            0x0000,
            0xCC01,
            0xD801,
            0x1400,
            0xF001,
            0x3C00,
            0x2800,
            0xE401,
            0xA001,
            0x6C00,
            0x7800,
            0xB401,
            0x5000,
            0x9C01,
            0x8801,
            0x4400,
        )

    fun crc16(
        bytes: ByteArray,
        from: Int,
        to: Int,
    ): Int {
        var crc = 0
        for (i in from until to) {
            val byte = bytes[i].toInt() and 0xFF
            var tmp = table[crc and 0xF]
            crc = (crc shr 4) and 0x0FFF
            crc = crc xor tmp xor table[byte and 0xF]
            tmp = table[crc and 0xF]
            crc = (crc shr 4) and 0x0FFF
            crc = crc xor tmp xor table[(byte shr 4) and 0xF]
        }
        return crc
    }

    private fun le16(
        bytes: ByteArray,
        at: Int,
    ) = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    private fun le32(
        bytes: ByteArray,
        at: Int,
    ) = le16(bytes, at).toLong() or (le16(bytes, at + 2).toLong() shl 16)

    /** Throws [AssertionError] with the first thing wrong, returns the data size otherwise. */
    fun verify(bytes: ByteArray): Long {
        check(bytes.size > 16) { "Too short for a FIT file: ${bytes.size} bytes" }
        check(bytes[0].toInt() == 14) { "Header size ${bytes[0]}" }
        check(String(bytes, 8, 4, Charsets.US_ASCII) == ".FIT") { "No .FIT signature" }
        val dataSize = le32(bytes, 4)
        check(dataSize == (bytes.size - 16).toLong()) { "Data size $dataSize for a file of ${bytes.size} bytes" }
        check(le16(bytes, 12) == crc16(bytes, 0, 12)) { "Header CRC mismatch" }
        check(le16(bytes, bytes.size - 2) == crc16(bytes, 0, bytes.size - 2)) { "File CRC mismatch" }
        check(crc16(bytes, 0, bytes.size) == 0) { "CRC over the whole file is not 0" }
        return dataSize
    }
}
