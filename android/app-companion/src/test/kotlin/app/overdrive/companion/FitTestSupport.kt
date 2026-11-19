package app.overdrive.companion

import android.net.Uri
import app.overdrive.companion.share.SharedFile
import app.overdrive.companion.share.SharedFileReader
import app.overdrive.companion.share.SharedRead
import app.overdrive.companion.work.BackgroundScheduler
import app.overdrive.core.sync.HttpAnswer
import app.overdrive.core.sync.HttpPostBytesRequest
import app.overdrive.core.sync.HttpPostBytesTransport
import app.overdrive.core.sync.TransportException
import app.overdrive.core.sync.TransportFailure
import app.overdrive.data.sync.UrlConnectionTransport
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic FIT files: the 12-byte header of the protocol and anything after it. No ride, heart rate or position of anybody. */
object SyntheticFit {
    /** A file of [size] bytes; [seed] makes it a different file. */
    fun bytes(
        seed: Int = 1,
        size: Int = 600,
    ): ByteArray {
        val data = size - 14
        val out = ByteArray(size) { ((it * 29 + seed) and 0x7F).toByte() }
        out[0] = 12
        out[1] = 0x20
        out[2] = 0xE6.toByte()
        out[3] = 0x52
        out[4] = (data and 0xFF).toByte()
        out[5] = ((data shr 8) and 0xFF).toByte()
        out[6] = ((data shr 16) and 0xFF).toByte()
        out[7] = ((data shr 24) and 0xFF).toByte()
        ".FIT".encodeToByteArray().copyInto(out, 8)
        return out
    }
}

/** A reader that answers from a map by the URI's last segment, for the container tests that do not go through a content provider. */
class FakeSharedFiles : SharedFileReader {
    val files = HashMap<String, SharedRead>()
    val reads = AtomicInteger(0)

    fun put(
        name: String,
        bytes: ByteArray,
    ): SharedFile {
        files[name] = SharedRead.Bytes(bytes)
        return SharedFile(Uri.parse("content://app.overdrive.test/$name"), "application/octet-stream")
    }

    fun refuse(
        name: String,
        read: SharedRead,
    ): SharedFile {
        files[name] = read
        return SharedFile(Uri.parse("content://app.overdrive.test/$name"), "application/octet-stream")
    }

    override suspend fun read(file: SharedFile): SharedRead {
        reads.incrementAndGet()
        return files[file.uri.lastPathSegment] ?: SharedRead.Unreadable
    }
}

/** The real transport, or no network at all: what a phone does when the rider leaves Wi-Fi. */
class SwitchableTransport(
    private val real: HttpPostBytesTransport = UrlConnectionTransport(),
) : HttpPostBytesTransport {
    @Volatile
    var offline = false

    override suspend fun postBytes(request: HttpPostBytesRequest): HttpAnswer {
        if (offline) throw TransportException(TransportFailure.NoNetwork)
        return real.postBytes(request)
    }
}

/** A scheduler that only counts: the container tests drive the work themselves. */
class CountingScheduler : BackgroundScheduler {
    val fitFlushes = AtomicInteger(0)

    override fun scheduleDaily() = Unit

    override fun startFirstRead(): Boolean = false

    override fun scheduleFitFlush() {
        fitFlushes.incrementAndGet()
    }

    override fun cancelAll() = Unit
}
