package app.overdrive.core.live

import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The line parser of the cue stream (docs/adr/0039): tables of streams cut in every way a network can cut them. */
class SseParserTest {
    private val contracts = File(System.getProperty("overdrive.contracts.dir"))

    private fun parse(vararg chunks: String): Triple<List<SseEvent>, String?, Long?> {
        val parser = SseParser()
        val events = chunks.flatMap { parser.feed(it) }
        return Triple(events, parser.lastEventId, parser.retryMillis)
    }

    private class Case(
        val name: String,
        val input: String,
        val events: List<SseEvent>,
        val lastId: String? = null,
        val retry: Long? = null,
    )

    private fun event(
        data: String,
        type: String = "message",
        id: String? = null,
    ) = SseEvent(type, data, id)

    private val table =
        listOf(
            Case("a plain event", "data: hello\n\n", listOf(event("hello"))),
            Case(
                "a named event with an id",
                "id: 7\nevent: cue\ndata: {}\n\n",
                listOf(event("{}", "cue", "7")),
                lastId = "7",
            ),
            Case(
                "the fields in any order",
                "data: x\nevent: end\nid: 9\n\n",
                listOf(event("x", "end", "9")),
                lastId = "9",
            ),
            Case("one space after the colon is dropped and no more", "data:  two\n\n", listOf(event(" two"))),
            Case("no space after the colon", "data:tight\n\n", listOf(event("tight"))),
            Case("a field with no colon is a field with no value", "data\n\n", listOf(event(""))),
            Case(
                "data lines are joined with a line feed",
                "data: a\ndata: b\ndata:\ndata: c\n\n",
                listOf(event("a\nb\n\nc")),
            ),
            Case("CRLF ends a line", "event: cue\r\ndata: x\r\n\r\n", listOf(event("x", "cue"))),
            Case("a lone CR ends a line", "event: cue\rdata: x\r\r", listOf(event("x", "cue"))),
            Case("mixed line ends", "data: a\r\ndata: b\ndata: c\r\r\n", listOf(event("a\nb\nc"))),
            Case("a comment is ignored", ": open\n\ndata: x\n: hb\n\n", listOf(event("x"))),
            Case("a comment inside an event does not end it", "data: a\n: hb\ndata: b\n\n", listOf(event("a\nb"))),
            Case("a heartbeat alone dispatches nothing", ": hb\n\n: hb\n\n", emptyList()),
            Case("an event with no data is not dispatched", "event: cue\n\n", emptyList()),
            Case("but its id counts", "id: 5\n\ndata: x\n\n", listOf(event("x", id = "5")), lastId = "5"),
            Case(
                "the id persists into the next event",
                "id: 1\ndata: a\n\ndata: b\n\n",
                listOf(event("a", id = "1"), event("b", id = "1")),
                lastId = "1",
            ),
            Case("an id with a NUL is ignored", "id: a\u0000b\ndata: x\n\n", listOf(event("x"))),
            Case(
                "an empty id clears it",
                "id: 3\ndata: a\n\nid\ndata: b\n\n",
                listOf(event("a", id = "3"), event("b", id = "")),
                lastId = "",
            ),
            Case("retry is milliseconds", "retry: 3000\n\n", emptyList(), retry = 3000),
            Case("retry that is not digits is ignored", "retry: 3s\nretry: -1\nretry: 1.5\nretry:\n\n", emptyList()),
            Case("the last retry wins", "retry: 3000\nretry: 5000\n\n", emptyList(), retry = 5000),
            Case("an unknown field is ignored", "foo: bar\ndata: x\n\n", listOf(event("x"))),
            Case("a byte order mark at the start is dropped", "${0xFEFF.toChar()}data: x\n\n", listOf(event("x"))),
            Case(
                "an event type does not leak into the next",
                "event: a\ndata: 1\n\ndata: 2\n\n",
                listOf(event("1", "a"), event("2")),
            ),
            Case("an event cut short is not dispatched", "data: whole\n\ndata: half\n", listOf(event("whole"))),
            Case("a line cut short is not dispatched", "data: whole\n\ndata: ha", listOf(event("whole"))),
            Case("blank lines between events", "data: a\n\n\n\ndata: b\n\n", listOf(event("a"), event("b"))),
            Case("a colon in the value is kept", "data: {\"a\":\"b: c\"}\n\n", listOf(event("{\"a\":\"b: c\"}"))),
            Case("non-ASCII text survives", "data: café 😀\n\n", listOf(event("café 😀"))),
        )

    @Test
    fun `every case of the table, whole`() {
        for (case in table) {
            val (events, lastId, retry) = parse(case.input)
            assertEquals(case.events, events, case.name)
            assertEquals(case.lastId, lastId, "${case.name}: last event id")
            assertEquals(case.retry, retry, "${case.name}: retry")
        }
    }

    @Test
    fun `every case of the table, a character at a time and cut in every place`() {
        for (case in table) {
            val one = parse(*case.input.map { it.toString() }.toTypedArray())
            assertEquals(case.events, one.first, "${case.name} (a character at a time)")
            assertEquals(case.lastId, one.second, "${case.name} (a character at a time): last event id")
            assertEquals(case.retry, one.third, "${case.name} (a character at a time): retry")
            for (cut in 0..case.input.length) {
                // Not between the two halves of a surrogate pair: a decoder never delivers half a character.
                if (cut in 1 until case.input.length && case.input[cut - 1].isHighSurrogate()) continue
                val two = parse(case.input.substring(0, cut), case.input.substring(cut))
                assertEquals(case.events, two.first, "${case.name} cut at $cut")
                assertEquals(case.lastId, two.second, "${case.name} cut at $cut: last event id")
            }
        }
    }

    @Test
    fun `a CRLF cut between the CR and the LF is one line end, not two`() {
        val (events, _, _) = parse("data: a\r", "\ndata: b\r\n", "\r", "\n")
        assertEquals(listOf(event("a\nb")), events)
        // Two CRs and an LF, cut so that the LF follows the second CR, are one empty line (the event ends) and no more.
        val (second, _, _) = parse("data: a\r", "\r", "\ndata: b\n\n")
        assertEquals(listOf(event("a"), event("b")), second)
    }

    private fun streamFixture(): String = File(contracts, "fixtures/live-coach/stream/cues.sse.txt").readText()

    @Test
    fun `the stream the service writes is read whole, a piece at a time, and with the other line ends`() {
        val text = streamFixture()
        val expected = parse(text)
        assertEquals(listOf("cue", "cue", "end"), expected.first.map { it.type })
        assertEquals(listOf("3", "4", "4"), expected.first.map { it.id })
        assertEquals(3000L, expected.third)
        assertEquals("4", expected.second)
        for (size in listOf(1, 2, 3, 5, 7, 13, 64, 1000)) {
            val pieces = text.chunked(size).toTypedArray()
            assertEquals(expected.first, parse(*pieces).first, "pieces of $size")
        }
        val random = Random(20261119)
        repeat(200) {
            val cuts = (0 until random.nextInt(1, 12)).map { random.nextInt(text.length + 1) }.sorted()
            val pieces = (listOf(0) + cuts + listOf(text.length)).zipWithNext { a, b -> text.substring(a, b) }
            assertEquals(expected.first, parse(*pieces.toTypedArray()).first, "random cuts $cuts")
        }
        assertEquals(expected.first, parse(text.replace("\n", "\r\n")).first, "CRLF")
        assertEquals(expected.first, parse(text.replace("\n", "\r")).first, "CR")
    }

    @Test
    fun `the events of the stream are the contract's cues and its end`() {
        val events = parse(streamFixture()).first
        val cues = events.filter { it.type == "cue" }.map { LiveCoachJson.decodeCue(it.data) }
        assertEquals(listOf(3, 4), cues.map { it.id })
        assertEquals(listOf("3", "4"), cues.map { it.id.toString() })
        assertEquals(LiveStreamEndReason.ENDED, LiveCoachJson.decodeStreamEnd(events.last().data).reason)
    }

    @Test
    fun `the parser can go on as a new stream with the id it had`() {
        val parser = SseParser()
        assertEquals(1, parser.feed("id: 8\ndata: a\n\ndata: half").size)
        assertEquals("8", parser.lastEventId)
        parser.finish()
        // The half event is forgotten, and so is an id that came with it.
        assertEquals(listOf(event("b", id = "8")), parser.feed("data: b\n\n"))
        val resumed = SseParser(initialLastEventId = "12")
        assertEquals("12", resumed.lastEventId)
        resumed.feed("id: 13\ndata: x")
        resumed.finish()
        assertEquals("12", resumed.lastEventId)
        assertEquals(listOf(event("y", id = "12")), resumed.feed("data: y\n\n"))
    }

    @Test
    fun `a line or an event that never ends is refused and not kept`() {
        val small = SseParser(maxChars = 16)
        small.feed("data: 0123456789\n\n")
        assertFailsWith<SseProtocolException> { SseParser(maxChars = 16).feed("data: 01234567890123456789") }
        assertFailsWith<SseProtocolException> { SseParser(maxChars = 16).feed("data: 012345678\ndata: 012345678\n\n") }
        // The default holds the biggest cue the contract allows.
        val biggest = "x".repeat(LiveCoachJson.CUE_EVENT_MAX_BYTES)
        assertEquals(1, SseParser().feed("data: $biggest\n\n").size)
    }

    @Test
    fun `an event prints no data`() {
        assertFalse(event("secret words", "cue", "3").toString().contains("secret"))
        assertTrue(event("secret words", "cue", "3").toString().contains("cue"))
        assertNull(parse("data: x\n\n").second)
    }
}
