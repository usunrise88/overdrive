package app.overdrive.core.sync

/**
 * One `PATCH` of the rider's profile (spec §8.3, ADR 0021). Like [HttpRequest] it holds a secret ([bearerToken]), so its
 * [toString] leaves it out and the body too (the profile is the rider's).
 *
 * @property body the JSON of the patch (`profile-patch.schema.json`), sent as `application/json`.
 * @property maxBytes the most the answer may hold; see [HttpGetRequest.maxBytes].
 */
public class HttpPatchRequest(
    public val url: String,
    /** Sent as `Authorization: Bearer <token>`. A secret: never logged. */
    public val bearerToken: String,
    public val body: String,
    public val maxBytes: Int,
) {
    override fun toString(): String = "PATCH $url (application/json, ${body.length} chars)"
}

/**
 * The network as far as changing the profile needs it: one `PATCH` with a small JSON body, answered with whatever status the
 * server chose. Redirects are **not** followed. The Android implementation is `UrlConnectionTransport` in `android/data`, the
 * same class and stack as the other two transports; tests use a fake.
 *
 * @throws TransportException when nothing was answered.
 */
public interface HttpPatchTransport {
    public suspend fun patch(request: HttpPatchRequest): HttpAnswer
}
