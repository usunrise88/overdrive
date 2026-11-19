package app.overdrive.core.sync

/**
 * One `POST` with a small JSON body: what the companion sends to answer an FTP suggestion (`POST /v1/ftp-suggestion/dismiss`,
 * spec §6.3). Like [HttpRequest] it holds a secret ([bearerToken]), so its [toString] leaves it out, and the body too.
 *
 * @property body the JSON of the request, sent as `application/json`.
 * @property maxBytes the most the answer may hold; see [HttpGetRequest.maxBytes].
 */
public class HttpPostRequest(
    public val url: String,
    /** Sent as `Authorization: Bearer <token>`. A secret: never logged. */
    public val bearerToken: String,
    public val body: String,
    public val maxBytes: Int,
) {
    override fun toString(): String = "POST $url (application/json, ${body.length} chars)"
}

/**
 * The network as far as a small `POST` needs it, answered with whatever status the server chose. Redirects are **not**
 * followed. The Android implementation is `UrlConnectionTransport` in `android/data`, the same class and stack as the other
 * transports (GET, PUT, PATCH); tests use a fake.
 *
 * @throws TransportException when nothing was answered.
 */
public interface HttpPostTransport {
    public suspend fun post(request: HttpPostRequest): HttpAnswer
}

/**
 * One `POST` with a **binary** body: what the companion sends to put a FIT file on the service (`POST /v1/activities/fit`, spec §11.8,
 * ADR 0028). It holds two things that are private, so its [toString] leaves out both: the [bearerToken] and the [body], which is the
 * rider's ride (heart rate and position). Only the kind of body and its size are printed.
 *
 * @property body the bytes of the request, sent as they are with `Content-Type: [contentType]` and a fixed length.
 * @property maxBytes the most the answer may hold; see [HttpGetRequest.maxBytes].
 */
public class HttpPostBytesRequest(
    public val url: String,
    /** Sent as `Authorization: Bearer <token>`. A secret: never logged. */
    public val bearerToken: String,
    public val body: ByteArray,
    public val contentType: String,
    public val maxBytes: Int,
) {
    override fun toString(): String = "POST $url ($contentType, ${body.size} bytes)"
}

/**
 * The network as far as sending a file needs it: one `POST` of bytes, answered with whatever status the server chose. Redirects are
 * **not** followed. `UrlConnectionTransport` in `android/data` implements it next to the other verbs, with the same stack, timeouts
 * and rules for secrets; tests use a fake.
 *
 * @throws TransportException when nothing was answered.
 */
public interface HttpPostBytesTransport {
    public suspend fun postBytes(request: HttpPostBytesRequest): HttpAnswer
}
