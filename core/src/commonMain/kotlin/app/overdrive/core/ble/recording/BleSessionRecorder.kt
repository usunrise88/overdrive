package app.overdrive.core.ble.recording

import app.overdrive.core.ble.GattUuid
import app.overdrive.core.ble.codec.Hex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Where a recorder's lines go: a file on the tablet, or a list in a test. Called from one coroutine, in order. */
public fun interface LineSink {
    /** Appends [line] (no line break) to the session file. May throw if the disk is full; the recorder then stops. */
    public fun writeLine(line: String)
}

/**
 * Writes a BLE session as text, one line per event (docs/protocols/ftms-hr.md §7.1, format in [BleSessionFormat]).
 *
 * It sees only what the transport sees (see [recording]) and writes what [BleRecordingPolicy] allows: no address, no
 * unit's name, no serial, no strap identity. It is a queue in front of a [LineSink]: [record] returns at once and one
 * coroutine in [scope] writes, so a slow disk never delays a BLE callback or the ride (spec §7.3), and a sink that throws
 * ends the recording without touching anything else ([failure]).
 *
 * @param nanoClock the **transport's** monotonic clock in nanoseconds: event times are made relative to the moment the
 * recorder was created, and notifications carry the transport's own stamps, so both must come from the same clock.
 * @param model the advertised model name; it is sanitised.
 */
public class BleSessionRecorder(
    scope: CoroutineScope,
    private val sink: LineSink,
    private val nanoClock: () -> Long,
    model: String? = null,
) {
    private val origin = nanoClock()
    private val queue = Channel<String>(Channel.UNLIMITED)
    private val writer: Job

    /** The first failure of the sink, or null. After a failure nothing more is written. */
    public var failure: Throwable? = null
        private set

    init {
        writer =
            scope.launch {
                try {
                    BleSessionFormat.headerLines(BleRecordingPolicy.sanitizeModel(model)).forEach { sink.writeLine(it) }
                    for (line in queue) sink.writeLine(line)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failure = e
                    queue.close()
                }
            }
    }

    /**
     * Records one event now. Redacted characteristics keep their line and lose their value. Never throws, never blocks.
     *
     * @param timeNanos the time on the transport's clock, by default now.
     */
    public fun record(
        direction: BleDirection,
        characteristic: GattUuid?,
        status: Int?,
        data: String,
        timeNanos: Long = nanoClock(),
    ) {
        val safe =
            if (characteristic != null &&
                BleRecordingPolicy.isRedacted(characteristic)
            ) {
                BleSessionEvent.REDACTED
            } else {
                data
            }
        val event = BleSessionEvent((timeNanos - origin).coerceAtLeast(0), direction, characteristic, status, safe)
        queue.trySend(BleSessionFormat.format(event))
    }

    /** Records an event whose value is bytes: [bytes] as hex, `-` when empty. */
    public fun recordBytes(
        direction: BleDirection,
        characteristic: GattUuid?,
        status: Int?,
        bytes: ByteArray?,
        timeNanos: Long = nanoClock(),
    ) {
        val data = if (bytes == null || bytes.isEmpty()) BleSessionEvent.NONE else Hex.encode(bytes)
        record(direction, characteristic, status, data, timeNanos)
    }

    /** Stops accepting events; the writer finishes what is queued. Idempotent. */
    public fun close() {
        queue.close()
    }

    /** Waits until everything recorded so far has been written, after [close]. */
    public suspend fun join() {
        writer.join()
    }
}
