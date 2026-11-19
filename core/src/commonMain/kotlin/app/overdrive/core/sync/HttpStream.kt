package app.overdrive.core.sync

/**
 * One `GET` that is read **as it arrives**: the live coach's cue stream (`text/event-stream`, spec §12.1, docs/adr/0038, 0039). Like
 * [HttpGetRequest] it holds a secret ([bearerToken]), so its [toString] leaves it out.
 *
 * @property lastEventId sent as `Last-Event-ID` when it is not null: the service then resends the cues after it that have not expired.
 */
public class HttpStreamRequest(
    public val url: String,
    /** Sent as `Authorization: Bearer <token>`. A secret: never logged. */
    public val bearerToken: String,
    public val lastEventId: String? = null,
) {
    override fun toString(): String = "GET $url (event stream)"
}

/**
 * The network as far as a stream needs it: one `GET` whose body is given to [stream]'s `onChunk` as text **while it is read**, until the
 * server ends the response or the request is cancelled. Redirects are **not** followed. `UrlConnectionTransport` in `android/data`
 * implements it beside the other verbs, with the same stack, the same bearer header and the same rules for secrets; tests use a fake.
 *
 * - **A status other than 200** is the answer: it is returned with the start of its body and `onChunk` is never called.
 * - **A response that the server ends** is a `200` answer with an empty body.
 * - **A connection that breaks, or says nothing for longer than the transport's read timeout** (longer than the service's 15 s heartbeat) is a
 *   [TransportException].
 * - **Cancelling the coroutine closes the connection** at once; a read that is waiting does not hold it up.
 *
 * @throws TransportException when nothing was answered, or the stream broke.
 */
public interface HttpStreamTransport {
    public suspend fun stream(
        request: HttpStreamRequest,
        onChunk: suspend (String) -> Unit,
    ): HttpAnswer
}
