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
        TransportFailure.Unreachable -> "Service unreachable"
        TransportFailure.TimedOut -> "The service did not answer in time"
        TransportFailure.SecureConnection -> "Secure connection failed"
        TransportFailure.Other -> "Could not talk to the service"
    }
