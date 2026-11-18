package app.overdrive.core.record

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * The crash-safe log of a ride in progress (spec §4.2 "Crash safety", ADR 0007): an append-only file with one line
 * per record, written by [RideLogRecorder] and read back by [RideLog.recover].
 *
 * ```
 * 3fa9c2d1 header {"schema":1,"ride":{"id":"ride-1","startEpochMillis":1794996000000,"ftp":250,"devices":["Trainer"]}}
 * 91bb04aa sample {"timeOffsetSeconds":1,"power":180,"heartRate":121,"speedKmh":31.2,"distanceMeters":8.7,"paused":false}
 * c0ffee11 rr [{"timeOffsetSeconds":1,"intervalMs":812}]
 * 5d41402a finished {"endEpochMillis":1794996600000}
 * ```
 *
 * Each line is `<crc> <kind> <json>` and ends with a line feed. `crc` is eight lower-case hex digits: the CRC-32 of
 * every byte from the `kind` up to, not including, the line feed. The JSON is exactly the one of the wire format
 * ([RideRecordJson]) for that part, on one line. So `cut -d' ' -f3-` turns the file into JSON Lines for `jq`.
 *
 * **A record counts only if its line is complete**: terminated by a line feed, a well-formed prefix and a checksum
 * that matches. A power cut or a kill in the middle of a write leaves a last line that fails one of the three, and
 * so does a file system that extended the file but never wrote the data (zeros). The first record that fails ends
 * the log: everything before it is returned, everything from it on is dropped.
 */
internal object RideLogFormat {
    /** The `schema` number in the header record. */
    const val SCHEMA_VERSION = 1

    const val KIND_HEADER = "header"
    const val KIND_SAMPLE = "sample"
    const val KIND_RR = "rr"
    const val KIND_FINISHED = "finished"

    private const val CRC_DIGITS = 8
    private const val LINE_FEED = '\n'.code.toByte()
    private const val SPACE = ' '.code.toByte()
    private const val HEX_RADIX = 16
    private val json = RideRecordJson.json

    @Serializable
    class Header(
        val schema: Int,
        val ride: RideHeader,
    )

    @Serializable
    class Finished(
        val endEpochMillis: Long,
    )

    fun header(ride: RideHeader): ByteArray =
        line(KIND_HEADER, json.encodeToString(Header.serializer(), Header(SCHEMA_VERSION, ride)))

    fun sample(sample: RideSample): ByteArray = line(KIND_SAMPLE, json.encodeToString(RideSample.serializer(), sample))

    fun rr(intervals: List<RrInterval>): ByteArray =
        line(KIND_RR, json.encodeToString(ListSerializer(RrInterval.serializer()), intervals))

    fun finished(endEpochMillis: Long): ByteArray =
        line(KIND_FINISHED, json.encodeToString(Finished.serializer(), Finished(endEpochMillis)))

    private fun line(
        kind: String,
        payload: String,
    ): ByteArray {
        val body = "$kind $payload".encodeToByteArray()
        val crc = Crc32.of(body).toString(HEX_RADIX).padStart(CRC_DIGITS, '0')
        val out = ByteArray(CRC_DIGITS + 1 + body.size + 1)
        crc.encodeToByteArray().copyInto(out)
        out[CRC_DIGITS] = SPACE
        body.copyInto(out, CRC_DIGITS + 1)
        out[out.size - 1] = LINE_FEED
        return out
    }

    /** One parsed line. */
    class Entry(
        val kind: String,
        val payload: String,
    )

    /**
     * The line `bytes[from, to)` (without its line feed), or null if it is not intact: too short, a malformed
     * checksum field, a checksum that does not match, or no kind.
     */
    fun parse(
        bytes: ByteArray,
        from: Int,
        to: Int,
    ): Entry? {
        val bodyStart = from + CRC_DIGITS + 1
        if (to - bodyStart < 1 || bytes[from + CRC_DIGITS] != SPACE) return null
        var stored = 0L
        for (i in from until from + CRC_DIGITS) {
            val digit = hexDigit(bytes[i])
            if (digit < 0) return null
            stored = stored * HEX_RADIX + digit
        }
        if (stored != Crc32.of(bytes, bodyStart, to)) return null
        val body = bytes.decodeToString(bodyStart, to)
        val space = body.indexOf(' ')
        if (space <= 0) return null
        return Entry(body.substring(0, space), body.substring(space + 1))
    }

    fun decodeHeader(payload: String): RideHeader {
        val header = json.decodeFromString(Header.serializer(), payload)
        require(header.schema == SCHEMA_VERSION) { "Unsupported ride log schema ${header.schema}" }
        return header.ride
    }

    fun decodeSample(payload: String): RideSample = json.decodeFromString(RideSample.serializer(), payload)

    fun decodeRr(payload: String): List<RrInterval> =
        json.decodeFromString(ListSerializer(RrInterval.serializer()), payload)

    fun decodeFinished(payload: String): Long = json.decodeFromString(Finished.serializer(), payload).endEpochMillis

    fun isLineFeed(byte: Byte): Boolean = byte == LINE_FEED

    private fun hexDigit(byte: Byte): Int =
        when (val c = byte.toInt()) {
            in '0'.code..'9'.code -> c - '0'.code
            in 'a'.code..'f'.code -> c - 'a'.code + 10
            else -> -1
        }
}

/**
 * Writes a ride to a [ByteSink] as it happens, in the format of [RideLogFormat]: the header when the ride starts, a
 * `sample` line every second, an `rr` line whenever the strap sent intervals, and a `finished` line at the end. Hand it
 * to the ride engine as its `recorder`; to keep the live [RideRecord] as well, combine it with an
 * [InMemoryRideRecorder] in a [CompositeRideRecorder].
 *
 * **Every call is one `append` of one complete line**, so a process kill loses at most the line being written, and
 * [RideLog.recover] drops it. **Power loss** is covered by [ByteSink.sync], called after the header, after the
 * `finished` line and then every [syncEverySamples] samples (default 5, so a power cut loses at most five seconds):
 * a sync per second would be safe too, but each one is a flash round trip on the engine's thread.
 *
 * **A failing sink never ends the ride.** An exception from the sink (disk full, storage gone) is caught, kept in
 * [failure] and stops further writing, which leaves the file a clean prefix of the ride; the engine carries on and the
 * rider can still finish and export from memory. The app can look at [failure] and warn.
 *
 * Calls come from the engine's coroutine, once a second; each is a small write, so the engine's background dispatcher
 * is the place for it. Not thread-safe.
 */
public class RideLogRecorder(
    private val sink: ByteSink,
    private val syncEverySamples: Int = DEFAULT_SYNC_EVERY_SAMPLES,
) : RideRecorder {
    private var started = false
    private var finished = false
    private var samplesSinceSync = 0

    /** The exception that stopped the log from being written, or null while all is well. */
    public var failure: Exception? = null
        private set

    init {
        require(syncEverySamples >= 1) { "Sync every at least 1 sample, got $syncEverySamples" }
    }

    override fun onRideStarted(header: RideHeader) {
        check(!started) { "The ride was already started" }
        started = true
        guarded {
            sink.append(RideLogFormat.header(header))
            sink.sync()
        }
    }

    override fun onSample(sample: RideSample) {
        checkRunning()
        guarded {
            sink.append(RideLogFormat.sample(sample))
            if (++samplesSinceSync >= syncEverySamples) {
                sink.sync()
                samplesSinceSync = 0
            }
        }
    }

    override fun onRrIntervals(intervals: List<RrInterval>) {
        checkRunning()
        if (intervals.isEmpty()) return
        guarded { sink.append(RideLogFormat.rr(intervals)) }
    }

    override fun onRideFinished(endEpochMillis: Long) {
        check(started) { "The ride has not started" }
        if (finished) return
        finished = true
        guarded {
            sink.append(RideLogFormat.finished(endEpochMillis))
            sink.sync()
        }
        try {
            sink.close()
        } catch (e: Exception) {
            if (failure == null) failure = e
        }
    }

    private fun checkRunning() {
        check(started) { "The ride has not started" }
        check(!finished) { "The ride is over" }
    }

    private inline fun guarded(action: () -> Unit) {
        if (failure != null) return
        try {
            action()
        } catch (e: Exception) {
            failure = e
        }
    }

    public companion object {
        /** Samples between two [ByteSink.sync] calls, unless told otherwise. */
        public const val DEFAULT_SYNC_EVERY_SAMPLES: Int = 5
    }
}

/** How a ride log ended. */
public enum class RideLogStatus {
    /** The log has its `finished` line: the rider ended the ride, or the workout did. */
    FINISHED,

    /** The log stops without one: the app was killed, crashed or lost power during the ride. */
    INTERRUPTED,
}

/**
 * What [RideLog.recover] read from a ride log: the intact beginning of it. [samples] are the seconds 1, 2, 3, …
 * up to the last one whose line was complete.
 */
public data class RecoveredRide(
    val header: RideHeader,
    val samples: List<RideSample>,
    val rrIntervals: List<RrInterval>,
    /** The end time from the `finished` line, or null if the ride was interrupted. */
    val endEpochMillis: Long?,
    /** Bytes of the log that were read, which is the length of its intact part. */
    val validBytes: Int,
    /** Bytes after the intact part that were dropped: a torn last line, or damage and what followed it. */
    val discardedBytes: Int,
) {
    public val status: RideLogStatus get() =
        if (endEpochMillis !=
            null
        ) {
            RideLogStatus.FINISHED
        } else {
            RideLogStatus.INTERRUPTED
        }

    public val isFinished: Boolean get() = status == RideLogStatus.FINISHED

    /**
     * The ride as a [RideRecord], with laps and summary computed by the same code as in a live ride. A ride that was
     * interrupted has no recorded end; its end is the start plus one second for every sample, which is exact up to
     * the second in progress when it stopped.
     */
    public fun toRecord(): RideRecord =
        RideRecordBuilder.recover(
            header = header,
            samples = samples,
            rrIntervals = rrIntervals,
            endEpochMillis = endEpochMillis ?: (header.startEpochMillis + samples.size * MILLIS_PER_SECOND),
        )

    private companion object {
        const val MILLIS_PER_SECOND = 1000L
    }
}

/** Reads a ride log back (spec §4.2: "An interrupted ride is recovered on the next launch"). */
public object RideLog {
    /** [recover] over the bytes of [source]; throws only if [source] itself cannot be read. */
    public fun recover(source: ByteSource): RecoveredRide? = recover(source.readAll())

    /**
     * Reads the intact beginning of a ride log. Never throws on damaged input: a log cut off at any byte, with a
     * torn or garbled last line, zero-filled or with a flipped bit in the middle gives back every record before the
     * first bad one, whole and in order.
     *
     * The log ends at the first record that is not intact (see [RideLogFormat]), is not the kind that may follow
     * (the first must be the header, a sample must be exactly the next second) or comes after the `finished` line.
     *
     * @return the ride, or null when there is nothing to recover: the file is empty or its first line is not a valid
     * header, which means the ride never got as far as being written.
     */
    public fun recover(bytes: ByteArray): RecoveredRide? {
        var header: RideHeader? = null
        val samples = mutableListOf<RideSample>()
        val rrIntervals = mutableListOf<RrInterval>()
        var endEpochMillis: Long? = null
        var position = 0
        var validEnd = 0
        while (position < bytes.size && endEpochMillis == null) {
            var lineEnd = position
            while (lineEnd < bytes.size && !RideLogFormat.isLineFeed(bytes[lineEnd])) lineEnd++
            if (lineEnd == bytes.size) break // no line feed: the line was cut off
            val entry = RideLogFormat.parse(bytes, position, lineEnd) ?: break
            try {
                when {
                    header == null -> {
                        if (entry.kind != RideLogFormat.KIND_HEADER) break
                        header = RideLogFormat.decodeHeader(entry.payload)
                    }

                    entry.kind == RideLogFormat.KIND_SAMPLE -> {
                        val sample = RideLogFormat.decodeSample(entry.payload)
                        if (sample.timeOffsetSeconds != samples.size + 1) break
                        samples += sample
                    }

                    entry.kind == RideLogFormat.KIND_RR -> {
                        rrIntervals += RideLogFormat.decodeRr(entry.payload)
                    }

                    entry.kind == RideLogFormat.KIND_FINISHED -> {
                        endEpochMillis = RideLogFormat.decodeFinished(entry.payload)
                    }

                    else -> {
                        break
                    }
                }
            } catch (e: Exception) {
                // The checksum matched but the JSON is not of the schema (another version, a bug): the end of the log.
                // SerializationException and IllegalArgumentException from the models' checks land here.
                break
            }
            position = lineEnd + 1
            validEnd = position
        }
        val ride = header ?: return null
        return RecoveredRide(ride, samples, rrIntervals, endEpochMillis, validEnd, bytes.size - validEnd)
    }
}
