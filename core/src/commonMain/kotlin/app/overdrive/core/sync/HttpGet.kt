package app.overdrive.core.sync

/**
 * One `GET` of the planned workouts (spec §8.3). Like [HttpRequest] it holds a secret ([bearerToken]), so its [toString]
 * leaves it out, and it has no body.
 *
 * @property maxBytes the most the answer may hold. A longer one is not read on: the transport throws a
 * [TransportException] (`Other`), so a server that sends something enormous costs a failed refresh and not the tablet's memory.
 */
public class HttpGetRequest(
    public val url: String,
    /** Sent as `Authorization: Bearer <token>`. A secret: never logged. */
    public val bearerToken: String,
    public val maxBytes: Int,
) {
    override fun toString(): String = "GET $url"
}

/**
 * The network as far as reading the plan needs it: one `GET`, answered with whatever status the server chose. Redirects are
 * **not** followed. It is the read side of what [HttpTransport] does for the upload, and the Android class that implements
 * one implements both (`UrlConnectionTransport` in `android/data`): the same stack, the same timeouts, the same rules for
 * secrets. Tests use a fake.
 *
 * @throws TransportException when nothing was answered.
 */
public interface HttpGetTransport {
    public suspend fun get(request: HttpGetRequest): HttpAnswer
}

/** Why a request got no answer, in the plain words the settings and the Ready screen say. */
internal fun TransportFailure.plainReason(): String =
    when (this) {
        TransportFailure.NameNotFound -> "Cannot find the service address (DNS)"
        TransportFailure.NetworkBlocked -> "The system blocks this app's network access"
        TransportFailure.NoNetwork -> "No network connection"
        TransportFailure.Refused -> "The service refused the connection"
        TransportFailure.Dropped -> "The connection to the service was cut"
        TransportFailure.Unreachable -> "Service unreachable"
        TransportFailure.TimedOut -> "The service did not answer in time"
        TransportFailure.SecureConnection -> "Secure connection failed"
        TransportFailure.Other -> "Could not talk to the service"
    }

/**
 * What the owner reads when a request got no answer: the plain words of the [failure][TransportException.failure], then the
 * technical [hint][TransportException.hint] in brackets when there is one ("The system blocks this app's network access
 * (SocketException · EACCES)").
 */
internal fun TransportException.plainReason(): String = failure.plainReason() + (hint?.let { " ($it)" } ?: "")

/**
 * What the service answered when it was not the answer asked for, in plain words ([noRoute] is the words for its
 * `404 not_found`, which is what a service without the route says).
 */
internal fun reasonForStatus(
    status: Int,
    body: String,
    noRoute: String,
): String =
    when {
        status == HTTP_REQUEST_TIMEOUT -> "The service timed out"
        status == HTTP_TOO_MANY_REQUESTS -> "The service is busy"
        status in HTTP_SERVER_ERRORS -> "Service error ($status)"
        status in HTTP_REDIRECTS -> "The address redirects elsewhere"
        status == HTTP_NOT_FOUND && errorCodeOf(body) == ApiErrorCode.NOT_FOUND -> noRoute
        status in HTTP_CLIENT_ERRORS -> "The service refused the request ($status)"
        else -> NOT_THE_SERVICE
    }

/** The words for an answer that is not the contract's: whatever answered, it is not the Overdrive service. */
internal const val NOT_THE_SERVICE: String = "That is not the Overdrive service"

private fun errorCodeOf(body: String): ApiErrorCode? =
    try {
        SyncApiJson.decodeApiError(body).code
    } catch (e: IllegalArgumentException) {
        null
    }

private const val HTTP_NOT_FOUND = 404
private const val HTTP_REQUEST_TIMEOUT = 408
private const val HTTP_TOO_MANY_REQUESTS = 429
private val HTTP_REDIRECTS = 300..399
private val HTTP_CLIENT_ERRORS = 400..499
private val HTTP_SERVER_ERRORS = 500..599
