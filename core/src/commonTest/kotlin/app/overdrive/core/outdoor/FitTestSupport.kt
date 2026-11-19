package app.overdrive.core.outdoor

import app.overdrive.core.remote.ADDRESS
import app.overdrive.core.remote.TOKEN
import app.overdrive.core.sync.HttpAnswer
import app.overdrive.core.sync.HttpPostBytesRequest
import app.overdrive.core.sync.HttpPostBytesTransport

/** Synthetic FIT files: a real header and anything after it. No ride, no heart rate, no position of anybody. */
internal object FitSamples {
    /** A file with the 12-byte header of the FIT protocol and [size] bytes in all; [seed] makes it a different file. */
    fun bytes(
        seed: Int = 1,
        size: Int = 64,
    ): ByteArray {
        val data = size - 14
        val out = ByteArray(size) { ((it * 31 + seed) and 0x7F).toByte() }
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

    private fun summary(
        start: String = "2026-11-15T10:00:00Z",
        end: String = "2026-11-15T11:12:04Z",
        duration: Int = 4324,
        load: Double? = 74.0,
    ) = ActivityImportSummary(
        name = "Outdoor ride",
        start = start,
        end = end,
        startDate = start.take(10),
        durationSeconds = duration,
        distanceMeters = 31_400.0,
        elevationGainMeters = 212.0,
        averageHeartRate = 142.4,
        maxHeartRate = 171,
        load = load,
        loadApproximate = load != null,
    )

    fun imported(id: String = "fit-0123456789abcdef") =
        ActivityImportJson.encode(
            ActivityImportResponse(ActivityImportStatus.IMPORTED, id, ActivityImportKind.ACTIVITY, null, summary()),
        )

    fun duplicate(
        reason: ActivityImportReason = ActivityImportReason.SAME_FILE,
        id: String = "fit-0123456789abcdef",
    ) = ActivityImportJson.encode(
        ActivityImportResponse(
            ActivityImportStatus.DUPLICATE,
            id,
            ActivityImportKind.ACTIVITY,
            reason,
            summary(load = null),
        ),
    )

    fun error(
        status: Int,
        code: String,
    ) = HttpAnswer(status, """{"error":{"code":"$code","message":"The service says no"}}""")
}

/** A transport for the file: answers from a script (an [HttpAnswer] or a [Throwable]; the last entry repeats) and keeps what it was sent. */
internal class ScriptedFitTransport(
    vararg script: Any,
) : HttpPostBytesTransport {
    private val script = script.toMutableList()
    private var last: Any? = null
    val posts = mutableListOf<HttpPostBytesRequest>()

    /** Runs when a request arrives, before it is answered: a test uses it to look at the outbox, or to hold the request open. */
    var onRequest: suspend (HttpPostBytesRequest) -> Unit = {}

    fun then(vararg more: Any) {
        script.addAll(more)
    }

    override suspend fun postBytes(request: HttpPostBytesRequest): HttpAnswer {
        posts += request
        onRequest(request)
        val entry = if (script.isNotEmpty()) script.removeAt(0).also { last = it } else checkNotNull(last)
        return when (entry) {
            is HttpAnswer -> entry
            is Throwable -> throw entry
            else -> error("bad script entry")
        }
    }
}

internal fun fitUrl() = "$ADDRESS/v1/activities/fit"

internal fun secretToken() = TOKEN
