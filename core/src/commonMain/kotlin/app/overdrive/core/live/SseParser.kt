package app.overdrive.core.live

/**
 * One event of a Server-Sent Events stream, as the browsers' `EventSource` would dispatch it (WHATWG HTML §9.2).
 *
 * @property type the `event:` field, or `message` when there was none.
 * @property data the `data:` lines joined with a line feed, without the last one. Never empty: an event with no data is not dispatched.
 * @property id the stream's last event id when this event was dispatched (the `id:` field persists from one event to the next), or null
 * when none was ever sent.
 */
public data class SseEvent(
    val type: String,
    val data: String,
    val id: String?,
) {
    // The data is the rider's cue: it is never printed.
    override fun toString(): String = "SseEvent(type=$type, id=$id, ${data.length} characters)"
}

/** The stream broke the parser's limits (a line or an event longer than it may be): the connection is not worth keeping. */
public class SseProtocolException(
    message: String,
) : RuntimeException(message)

/**
 * The line parser of Server-Sent Events: **pure** (text in, events out, no clock, no thread, no I/O), fed with whatever pieces the network
 * delivers (`feed`), which may cut a line, a field or a CRLF anywhere.
 *
 * The rules are the standard's (WHATWG HTML §9.2.6): a line ends with CRLF, LF or a lone CR; an empty line dispatches the event; a line
 * that starts with `:` is a comment (the service's `: open` and `: hb` heartbeats); `field: value` drops **one** space after the colon, and
 * a line with no colon is a field with an empty value; `event` names the event, `data` adds a line to it, `id` sets the last event id (and
 * is ignored when it holds a NUL), `retry` sets the reconnection time when it is digits only (anything else is ignored); other fields are
 * ignored; a byte order mark at the start of the stream is dropped; an event with no `data` is not dispatched (its `id` still counts); and
 * **an event cut short by the end of the stream is never dispatched** ([finish]).
 *
 * **Limits.** A line or the data of one event longer than [maxChars] is [SseProtocolException]: a service that never ends a line would
 * otherwise cost the tablet its memory. The default is a little more than the biggest cue the contract allows.
 *
 * Not thread safe: one reader feeds it.
 */
public class SseParser(
    private val maxChars: Int = DEFAULT_MAX_CHARS,
    initialLastEventId: String? = null,
) {
    private val line = StringBuilder()
    private val data = StringBuilder()
    private var hasData = false
    private var eventType = ""
    private var idBuffer: String? = initialLastEventId
    private var skipLineFeed = false
    private var atStart = true

    /** The id of the last event dispatched (or set by one): what to send as `Last-Event-ID` on a reconnect. Null when none was sent. */
    public var lastEventId: String? = initialLastEventId
        private set

    /** The reconnection time the stream asked for with `retry:`, in milliseconds, or null when it did not. */
    public var retryMillis: Long? = null
        private set

    /** Reads [chunk], which may begin or end in the middle of anything, and returns the events it completes, in order. */
    public fun feed(chunk: String): List<SseEvent> {
        val events = ArrayList<SseEvent>(0)
        for (ch in chunk) {
            if (atStart) {
                atStart = false
                if (ch.code == BYTE_ORDER_MARK_CODE) continue
            }
            if (skipLineFeed) {
                skipLineFeed = false
                if (ch == '\n') continue
            }
            when (ch) {
                '\r' -> {
                    skipLineFeed = true
                    endOfLine()?.let(events::add)
                }

                '\n' -> {
                    endOfLine()?.let(events::add)
                }

                else -> {
                    if (line.length >=
                        maxChars
                    ) {
                        throw SseProtocolException("A line of the stream is longer than $maxChars characters")
                    }
                    line.append(ch)
                }
            }
        }
        return events
    }

    /**
     * The stream ended. What is half read (a line with no end, an event with no empty line after it) is forgotten, as the standard says: it
     * was not complete. The parser can be fed again as a new stream that remembers the last event id.
     */
    public fun finish() {
        line.clear()
        data.clear()
        hasData = false
        eventType = ""
        idBuffer = lastEventId
        skipLineFeed = false
        atStart = true
    }

    private fun endOfLine(): SseEvent? {
        val text = line.toString()
        line.setLength(0)
        if (text.isEmpty()) return dispatch()
        if (text[0] == ':') return null
        val colon = text.indexOf(':')
        val field: String
        var value: String
        if (colon < 0) {
            field = text
            value = ""
        } else {
            field = text.substring(0, colon)
            value = text.substring(colon + 1)
            if (value.startsWith(' ')) value = value.substring(1)
        }
        when (field) {
            "event" -> {
                eventType = value
            }

            "data" -> {
                if (data.length + value.length + 1 > maxChars) {
                    throw SseProtocolException("An event of the stream is longer than $maxChars characters")
                }
                data.append(value).append('\n')
                hasData = true
            }

            "id" -> {
                if ('\u0000' !in value) idBuffer = value
            }

            "retry" -> {
                if (value.isNotEmpty() &&
                    value.all { it in '0'..'9' }
                ) {
                    retryMillis = value.toLongOrNull() ?: Long.MAX_VALUE
                }
            }
        }
        return null
    }

    private fun dispatch(): SseEvent? {
        lastEventId = idBuffer
        if (!hasData) {
            eventType = ""
            return null
        }
        data.setLength(data.length - 1)
        val event = SseEvent(eventType.ifEmpty { "message" }, data.toString(), lastEventId)
        data.setLength(0)
        hasData = false
        eventType = ""
        return event
    }

    public companion object {
        /** Just over what the biggest cue the contract allows takes as a line: 400000 bytes of speech in base64 and the rest of the cue. */
        public const val DEFAULT_MAX_CHARS: Int = LiveCoachJson.CUE_EVENT_MAX_BYTES + 4_096

        private const val BYTE_ORDER_MARK_CODE = 0xFEFF
    }
}
