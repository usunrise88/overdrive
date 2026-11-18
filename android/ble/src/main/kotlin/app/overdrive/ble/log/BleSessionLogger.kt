package app.overdrive.ble.log

import app.overdrive.core.ble.DeviceId
import app.overdrive.core.ble.GattResult
import app.overdrive.core.ble.GattTransport
import app.overdrive.core.ble.GattTransportFactory
import app.overdrive.core.ble.recording.BleSessionRecorder
import app.overdrive.core.ble.recording.LineSink
import app.overdrive.core.ble.recording.RecordingGattTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * A text file that grows one line at a time and stops growing at [maxBytes]. It is created by its first line, so
 * nothing touches the disk on the thread that asked for it; [LineSink.writeLine] is called from one coroutine, in
 * order.
 */
internal class FileLineSink(
    private val open: () -> File,
    private val maxBytes: Long,
) : LineSink {
    private var stream: FileOutputStream? = null
    private var written = 0L

    /** Whether a line did not fit: the file is full and the session is rotated at the next chance. */
    @Volatile
    var full: Boolean = false
        private set

    @Throws(IOException::class)
    override fun writeLine(line: String) {
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        if (written + bytes.size > maxBytes) {
            full = true
            return
        }
        val out = stream ?: FileOutputStream(open(), true).also { stream = it }
        out.write(bytes)
        written += bytes.size
    }

    fun close() {
        try {
            stream?.close()
        } catch (_: IOException) {
            // Nothing left to lose: the lines were written as they came.
        }
        stream = null
    }
}

/**
 * Writes what each GATT connection of one device says to a file of its own (ADR 0010, `docs/protocols/ftms-hr.md`
 * §7.1), so the owner's first rides leave the data to fix the codecs without a bench machine. It wraps a transport
 * factory with core's [RecordingGattTransport], so the adapters know nothing about it, and the same file replays
 * through the fake.
 *
 * **What a file is.** One *session*: the failed attempts that led to a link, and that link until its transport is
 * closed. The next `open` after that starts a new file. Attempts that never connect (the trainer is off) keep appending
 * to the same file until it reaches the size limit, so a trainer left off for a day does not leave a day of one-line
 * files.
 *
 * **Nothing here can disturb the radio or the ride.** The recorder queues each line and a coroutine on [writeScope],
 * which should be a thread of its own, writes it; a disk that fails or fills ends the recording and nothing else. The
 * file name holds the date and the role. The content is redacted by core's `BleRecordingPolicy`: no address, the
 * advertised model cleaned, identity values replaced.
 *
 * @param bleScope runs the decorator's two relay coroutines (a few instructions per notification).
 * @param writeScope runs the writer: file creation, writes, pruning. One thread.
 * @param nanoClock the clock of the transports' notification stamps (`SystemClock.elapsedRealtimeNanos()`).
 * @param model the advertised model name for the file header; core sanitises it.
 * @param enabled the Developer setting "Record Bluetooth sessions", read at each `open`.
 */
public class BleSessionLogger(
    private val store: BleLogStore,
    private val role: BleLogRole,
    private val bleScope: CoroutineScope,
    private val writeScope: CoroutineScope,
    private val nanoClock: () -> Long,
    private val model: () -> String?,
    private val enabled: () -> Boolean,
) {
    private class Session(
        val device: DeviceId,
        val recorder: BleSessionRecorder,
        val sink: FileLineSink,
    ) {
        /** A connection was up in this session. */
        @Volatile
        var linked = false
    }

    private val lock = Any()
    private var current: Session? = null

    /** [factory]'s transports, recorded while the setting is on. */
    public fun wrap(factory: GattTransportFactory): GattTransportFactory =
        GattTransportFactory { device ->
            val transport = factory.open(device)
            if (enabled()) {
                val session = sessionFor(device)
                Tracked(RecordingGattTransport(transport, session.recorder, bleScope), session)
            } else {
                transport
            }
        }

    private fun sessionFor(device: DeviceId): Session =
        synchronized(lock) {
            val reusable = current?.takeIf { it.device == device && !it.sink.full }
            if (reusable != null) {
                reusable
            } else {
                current?.let(::end)
                start(device).also { current = it }
            }
        }

    private fun start(device: DeviceId): Session {
        val sink = FileLineSink({ store.newFile(role) }, store.limits.maxFileBytes)
        return Session(device, BleSessionRecorder(writeScope, sink, nanoClock, model()), sink)
    }

    private fun end(session: Session) {
        synchronized(lock) { if (current === session) current = null }
        session.recorder.close()
        writeScope.launch {
            session.recorder.join()
            session.sink.close()
            store.prune()
        }
    }

    /** A transport that tells its session when it has been up and when it is closed. */
    private inner class Tracked(
        private val delegate: GattTransport,
        private val session: Session,
    ) : GattTransport by delegate {
        override suspend fun connect(): GattResult<Unit> =
            delegate.connect().also { if (it is GattResult.Ok) session.linked = true }

        override fun close() {
            delegate.close()
            if (session.linked || session.sink.full) end(session)
        }
    }
}
