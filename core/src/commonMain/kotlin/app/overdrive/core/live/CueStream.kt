package app.overdrive.core.live

import app.overdrive.core.remote.Outcome
import app.overdrive.core.remote.ProblemKind
import app.overdrive.core.remote.ServiceProblem
import app.overdrive.core.sync.HttpAnswer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Where the cue stream of one session comes from (docs/adr/0038, 0039): `ServiceClient.stream` of `GET /v1/live/sessions/{id}/cues`. It gives
 * the response's text to [onChunk] as it arrives and returns when the server ends the response, when the request cannot be made or when it
 * breaks. [lastEventId] is sent as `Last-Event-ID`.
 */
public fun interface CueStreamSource {
    public suspend fun open(
        lastEventId: String?,
        onChunk: suspend (String) -> Unit,
    ): Outcome<HttpAnswer>
}

/** Why [CueStream.run] stopped by itself (it also stops when its coroutine is cancelled, which is how a ride's end stops it). */
public sealed interface CueStreamEnd {
    /** The service said so with an `end` event: `ended` and `superseded` end the session, `shutdown` asks for a new one when the service is back. */
    public data class Ended(
        val reason: LiveStreamEndReason,
    ) : CueStreamEnd

    /** The service does not know the session any more (`404`): it was forgotten, or a newer one replaced it. */
    public data object SessionGone : CueStreamEnd

    /** No address or token is set up: nothing can be asked. */
    public data object NotSetUp : CueStreamEnd

    /** The service refused the token. A request goes out again only when the address or token changes. */
    public data object TokenRejected : CueStreamEnd
}

/**
 * The listening half of a live session: **opens the cue stream and keeps it open** (docs/adr/0039). It reads the stream with [SseParser],
 * hands every `cue` event to [onCue] and stops by itself on an `end` event or a session the service has forgotten.
 *
 * - **Reconnection.** A stream that ends or breaks is opened again after the interval the service asked for (`retry:`, 3 s by default), with
 *   `Last-Event-ID` set to the last event it saw, so a cue is neither lost nor played twice (the service resends only the cues after it that
 *   have not expired). **The interval is held between [MIN_RETRY_MILLIS] and [MAX_RETRY_MILLIS]**, so a service that says `retry: 0` cannot
 *   make a busy loop.
 * - **Backoff.** Every connection in a row that gave no data at all doubles the wait, up to [MAX_BACKOFF_MILLIS]; one that gave any (a
 *   cue or the heartbeat) starts it over. No jitter: there is one rider.
 * - **Stopping.** It is a suspending function: cancel its coroutine and the connection is closed (the transport's job) and nothing more
 *   happens. Nothing is retried after an `end` event, a `404` or a rejected token.
 * - **What the service says is not trusted.** A cue or end event that is not the contract's is skipped (counted in [skipped], never
 *   printed: the text of a cue is the rider's); a stream that breaks the parser's limits is a broken connection.
 *
 * It has no clock and starts no thread: the waiting is `delay`, so a test runs hours of it in virtual time.
 */
public class CueStream(
    private val source: CueStreamSource,
    private val onCue: (LiveCue) -> Unit,
    private val defaultRetryMillis: Long = DEFAULT_RETRY_MILLIS,
) {
    /** How many events were skipped for not being the contract's. For tests and the bench; never the events themselves. */
    public var skipped: Int = 0
        private set

    /** How many times the stream was opened (or tried). For tests. */
    public var attempts: Int = 0
        private set

    private class EndEvent(
        val reason: LiveStreamEndReason,
    ) : CancellationException("The service ended the stream")

    /** Opens the stream and keeps it open until the service ends it. Throws only for cancellation. */
    public suspend fun run(): CueStreamEnd {
        var lastEventId: String? = null
        var retry = defaultRetryMillis.coerceIn(MIN_RETRY_MILLIS, MAX_RETRY_MILLIS)
        var silentConnections = 0
        while (true) {
            attempts++
            val parser = SseParser(initialLastEventId = lastEventId)
            var gotData = false
            val outcome =
                try {
                    source.open(lastEventId) { chunk ->
                        gotData = true
                        val events =
                            try {
                                parser.feed(chunk)
                            } catch (e: SseProtocolException) {
                                // A line that never ends: this connection is not worth keeping.
                                throw BrokenStream()
                            }
                        for (event in events) handle(event)
                        parser.retryMillis?.let { retry = it.coerceIn(MIN_RETRY_MILLIS, MAX_RETRY_MILLIS) }
                        lastEventId = parser.lastEventId
                    }
                } catch (e: EndEvent) {
                    return CueStreamEnd.Ended(e.reason)
                } catch (e: BrokenStream) {
                    gotData = false
                    Outcome.Failed(ServiceProblem.NotTheService)
                }
            when (outcome) {
                is Outcome.NotSetUp -> {
                    return CueStreamEnd.NotSetUp
                }

                is Outcome.Failed -> {
                    when (outcome.problem.kind) {
                        ProblemKind.NO_SUCH_ROUTE -> return CueStreamEnd.SessionGone
                        ProblemKind.TOKEN_REJECTED -> return CueStreamEnd.TokenRejected
                        else -> Unit
                    }
                    silentConnections = if (gotData) 0 else silentConnections + 1
                }

                is Outcome.Ok -> {
                    // The server closed a stream that was open: a proxy's timeout, a restart. Open it again.
                    silentConnections = if (gotData) 0 else silentConnections + 1
                }
            }
            parser.finish()
            delay(waitMillis(retry, silentConnections))
        }
    }

    private class BrokenStream : RuntimeException()

    private fun handle(event: SseEvent) {
        when (event.type) {
            "cue" -> {
                val cue =
                    try {
                        LiveCoachJson.decodeCue(event.data)
                    } catch (e: IllegalArgumentException) {
                        skipped++
                        return
                    }
                onCue(cue)
            }

            "end" -> {
                val end =
                    try {
                        LiveCoachJson.decodeStreamEnd(event.data)
                    } catch (e: IllegalArgumentException) {
                        skipped++
                        return
                    }
                throw EndEvent(end.reason)
            }
        }
    }

    public companion object {
        /** What the service asks for first (`retry: 3000`), and what is used until it does. */
        public const val DEFAULT_RETRY_MILLIS: Long = 3_000

        /** The shortest wait between two connections, whatever the service asks for. */
        public const val MIN_RETRY_MILLIS: Long = 1_000

        /** The longest wait the service can ask for. */
        public const val MAX_RETRY_MILLIS: Long = 60_000

        /** The longest wait of the backoff. */
        public const val MAX_BACKOFF_MILLIS: Long = 30_000

        private const val MAX_DOUBLINGS = 5

        /** The wait before the next connection: [retry], doubled for each connection in a row that gave nothing, and at most the backoff's limit. */
        internal fun waitMillis(
            retry: Long,
            silentConnections: Int,
        ): Long {
            if (silentConnections <= 1) return retry
            val doubled = retry shl (silentConnections - 1).coerceAtMost(MAX_DOUBLINGS)
            return doubled.coerceAtMost(maxOf(MAX_BACKOFF_MILLIS, retry))
        }
    }
}
