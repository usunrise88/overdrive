package app.overdrive.core.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Where to upload and who is asking: the service's address (`https://host[:port][/path]`, no trailing path of the API) and the
 * device's bearer token. Empty when not set. A secret: it prints without the token, so it can end up in a log line or an
 * exception message without leaking it.
 */
public class SyncConfig(
    public val baseUrl: String,
    public val token: String,
) {
    /** Whether there is somewhere to upload to and a way to ask: both an address and a token. */
    public val isConfigured: Boolean get() = baseUrl.isNotBlank() && token.isNotEmpty()

    override fun equals(other: Any?): Boolean = other is SyncConfig && baseUrl == other.baseUrl && token == other.token

    override fun hashCode(): Int = 31 * baseUrl.hashCode() + token.hashCode()

    override fun toString(): String = "SyncConfig(baseUrl=$baseUrl, token=${if (token.isEmpty()) "(none)" else "••••"})"
}

/** Receives the bytes of a body in pieces, so nothing needs the whole body in memory at once. */
public fun interface ChunkSink {
    public fun write(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    )
}

/** The body of a request: a known [length] and a way to stream it, as many times as a retry needs. */
public interface UploadBody {
    /** The exact number of bytes [writeTo] writes. */
    public val length: Long

    /** Streams the whole body to [sink], in pieces. May throw if the source cannot be read. */
    public fun writeTo(sink: ChunkSink)
}

/** A body that is already in memory (a test's, or a small one). */
public class ByteArrayBody(
    private val bytes: ByteArray,
) : UploadBody {
    override val length: Long get() = bytes.size.toLong()

    override fun writeTo(sink: ChunkSink) {
        sink.write(bytes, 0, bytes.size)
    }
}

/** One `PUT` of the upload. Its [toString] leaves out the token and the body. */
public class HttpRequest(
    public val url: String,
    public val contentType: String,
    /** Sent as `Authorization: Bearer <token>`. A secret: never logged. */
    public val bearerToken: String,
    public val body: UploadBody,
) {
    override fun toString(): String = "PUT $url ($contentType, ${body.length} bytes)"
}

/** What the server answered: the status line's code and the start of the body (an error message or the upload response). */
public class HttpAnswer(
    public val status: Int,
    public val body: String = "",
)

/**
 * Why a request got no answer at all. The kinds that say where the connection broke are separate on purpose: "Service
 * unreachable" alone cannot tell a missing name, a refused connection and the system blocking the app, and each wants a
 * different fix.
 */
public enum class TransportFailure {
    /** The address could not be turned into a machine's address (a DNS failure, or no network to ask). */
    NameNotFound,

    /** The system forbids the app to use the network (`EACCES`, `EPERM`: a firewall, a data saver, a policy). */
    NetworkBlocked,

    /** The tablet has no network to send through (`ENETUNREACH`, `ENETDOWN`). */
    NoNetwork,

    /** The machine answered that nothing listens at that address and port (`ECONNREFUSED`). */
    Refused,

    /** The connection was made and then cut: reset, aborted or a broken pipe (`ECONNRESET`, `ECONNABORTED`, `EPIPE`). */
    Dropped,

    /** The server could not be reached for a reason not told apart from the others above (no route to the host, say). */
    Unreachable,

    /** The connection was made but the server was too slow. */
    TimedOut,

    /** The secure connection could not be set up (a certificate that does not fit the address, a wrong date on the tablet). */
    SecureConnection,

    /** Anything else that went wrong while talking. */
    Other,
}

/**
 * Thrown by an [HttpTransport] that got no answer. The message never holds the request: the token, the body and the address stay
 * out.
 *
 * @property hint a short technical hint for the owner, from a fixed vocabulary: the exception's class name and, when there is
 * one, the operating system's error name, as `SocketException · EACCES`. It never holds an address, a host name or any message
 * text; the transport builds it from a whitelist. Null when there is nothing to add.
 */
public class TransportException(
    public val failure: TransportFailure,
    cause: Throwable? = null,
    public val hint: String? = null,
) : Exception("No answer from the server: $failure", cause)

/**
 * The network, as far as the upload needs it: one `PUT` with a streamed body, answered with whatever status the server chose.
 * Redirects are **not** followed (a `PUT` cannot follow one without losing its body). The Android one is
 * `UrlConnectionTransport` in `android/data` (`HttpURLConnection`, timeouts, no new dependency); tests use a fake.
 *
 * @throws TransportException when nothing was answered.
 */
public interface HttpTransport {
    public suspend fun put(request: HttpRequest): HttpAnswer
}

/** The two files of a stored ride, for an upload. */
public class RidePayload(
    /** The ride's own id (`ride.id` in the record), which is what the server keys it by and checks the record against. */
    public val rideId: String,
    /** The ride record JSON (spec §8.2). */
    public val record: UploadBody,
    /** The FIT file generated from it. */
    public val fit: UploadBody,
)

/** The ride cannot be sent from this tablet and never will be as it is: its files are gone or damaged. The [message] is for people. */
public class RideUnavailableException(
    message: String,
) : Exception(message)

/** Where the stored rides are read from (the tablet's disk; a fake in tests). */
public interface RidePayloads {
    /**
     * Opens the record and the FIT of the ride [entry] names, without reading them whole.
     *
     * @throws RideUnavailableException when they are missing or damaged: a permanent problem of this ride.
     * Any other exception is a failure of the disk for now, and the upload is tried again later.
     */
    public suspend fun payload(entry: OutboxEntry): RidePayload
}

/**
 * The real [SyncProvider] (spec §4.9, §8.3, ADR 0014 sections 3 and 6): sends a stored ride to the service as two idempotent
 * requests, keyed by the ride's id,
 *
 * 1. `PUT {base}/v1/rides/{id}` with the record, `application/json`, then
 * 2. `PUT {base}/v1/rides/{id}/fit` with the FIT, `application/vnd.ant.fit`,
 *
 * both with `Authorization: Bearer <token>`. **A ride is [SyncResult.Acknowledged] only when both answered 200 or 201** with
 * the service's own upload response for this ride; so a repeat after a cut-off is harmless (the service answers 200), and
 * a page that merely says 200 (a captive portal, the wrong server) is not taken for success.
 *
 * How each answer is read:
 *
 * | Answer | Result |
 * |---|---|
 * | 200, 201 | success of that request |
 * | 401 | [SyncResult.TokenRejected]; **not asked again until the address or the token changes** (or [retryRejected]) |
 * | 408, 429, any 5xx, no answer (unreachable, timeout, TLS), an unexpected 1xx/2xx/3xx, a 404 `ride_not_found` for the FIT | [SyncResult.RetryLater] |
 * | 409, 422, any other 4xx | [SyncResult.Refused] with the server's short message |
 * | no address or no token | [SyncResult.NotConfigured] |
 * | the ride's files missing or damaged on the tablet | [SyncResult.Refused] |
 *
 * **Nothing here logs.** The token is read from [config] at every upload (so a change in Settings needs no restart), is sent
 * only in the header and is never put in a result, a reason, an exception message or a string.
 *
 * @param config the current address and token, asked at every upload.
 */
public class HttpSyncProvider(
    private val config: () -> SyncConfig,
    private val rides: RidePayloads,
    private val transport: HttpTransport,
) : SyncProvider {
    override val name: String = "Overdrive service"

    /** The address and token the service last answered 401 to; while the settings are the same, nothing is sent. */
    private val rejected = MutableStateFlow<SyncConfig?>(null)

    /** Forgets that the token was rejected, so the next upload asks the service again (the rider's **Send now**). */
    public fun retryRejected() {
        rejected.value = null
    }

    override suspend fun upload(entry: OutboxEntry): SyncResult {
        val settings = config()
        if (!settings.isConfigured) return SyncResult.NotConfigured
        // A token the service could never have issued (spaces, accents, anything but visible ASCII) cannot be sent as a
        // header value and cannot be right: say so without asking.
        if (rejected.value == settings || !settings.token.all { it in '!'..'~' }) return SyncResult.TokenRejected
        val ride =
            try {
                rides.payload(entry)
            } catch (e: RideUnavailableException) {
                return SyncResult.Refused(e.message ?: "The ride's files are not usable")
            }
        if (!SyncApiJson.isValidRideId(ride.rideId)) {
            return SyncResult.Refused("The ride's id is not one the service accepts")
        }
        val base = settings.baseUrl.trimEnd('/') + "/v1/rides/" + ride.rideId
        val steps =
            listOf(
                Step(base, RECORD_TYPE, ride.record, isFit = false),
                Step("$base/fit", FIT_TYPE, ride.fit, isFit = true),
            )
        for (step in steps) {
            val answer =
                try {
                    transport.put(HttpRequest(step.url, step.contentType, settings.token, step.body))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: TransportException) {
                    return SyncResult.RetryLater(e.plainReason())
                }
            val failure = failureOf(answer, ride.rideId, step.isFit, settings)
            if (failure != null) return failure
        }
        return SyncResult.Acknowledged
    }

    private class Step(
        val url: String,
        val contentType: String,
        val body: UploadBody,
        val isFit: Boolean,
    )

    /** Null when [answer] is the success of its request, otherwise what the upload as a whole came to. */
    private fun failureOf(
        answer: HttpAnswer,
        rideId: String,
        isFit: Boolean,
        settings: SyncConfig,
    ): SyncResult? =
        when (val status = answer.status) {
            HTTP_OK, HTTP_CREATED -> {
                if (isUploadResponseFor(answer.body, rideId)) null else SyncResult.RetryLater(NOT_THE_SERVICE)
            }

            HTTP_UNAUTHORIZED -> {
                rejected.value = settings
                SyncResult.TokenRejected
            }

            HTTP_REQUEST_TIMEOUT -> {
                SyncResult.RetryLater("The service timed out")
            }

            HTTP_TOO_MANY_REQUESTS -> {
                SyncResult.RetryLater("The service is busy")
            }

            in HTTP_SERVER_ERRORS -> {
                SyncResult.RetryLater("Service error ($status)")
            }

            // Not ours to read as a refusal of the ride: a proxy or the wrong address, or a server that lost the record
            // this very upload had just stored (the next try sends both again).
            in HTTP_REDIRECTS -> {
                SyncResult.RetryLater("The address redirects elsewhere")
            }

            in HTTP_CLIENT_ERRORS -> {
                val error = errorOf(answer.body)
                if (isFit && error?.code == ApiErrorCode.RIDE_NOT_FOUND) {
                    SyncResult.RetryLater("The service lost the ride's record")
                } else {
                    SyncResult.Refused(error?.message?.let(::oneLine) ?: "The service refused the ride ($status)")
                }
            }

            else -> {
                SyncResult.RetryLater(NOT_THE_SERVICE)
            }
        }

    private fun isUploadResponseFor(
        body: String,
        rideId: String,
    ): Boolean =
        try {
            SyncApiJson.decodeUploadResponse(body).rideId == rideId
        } catch (e: IllegalArgumentException) {
            false
        }

    private fun errorOf(body: String): ApiError? =
        try {
            SyncApiJson.decodeApiError(body)
        } catch (e: IllegalArgumentException) {
            null
        }

    /** The server's message as one short line of text for a row in Settings. */
    private fun oneLine(message: String): String {
        val text = message.map { if (it.isISOControlLike()) ' ' else it }.joinToString("").trim()
        return if (text.length <= MAX_MESSAGE) text else text.take(MAX_MESSAGE - 1).trimEnd() + "…"
    }

    private fun Char.isISOControlLike(): Boolean = this < ' ' || this == '\u007f'

    public companion object {
        /** The record's content type. */
        public const val RECORD_TYPE: String = "application/json"

        /** The FIT upload's content type. */
        public const val FIT_TYPE: String = "application/vnd.ant.fit"

        private const val NOT_THE_SERVICE = "That is not the Overdrive service"
        private const val MAX_MESSAGE = 160
        private const val HTTP_OK = 200
        private const val HTTP_CREATED = 201
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_REQUEST_TIMEOUT = 408
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private val HTTP_REDIRECTS = 300..399
        private val HTTP_CLIENT_ERRORS = 400..499
        private val HTTP_SERVER_ERRORS = 500..599
    }
}
