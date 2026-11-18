package app.overdrive.core.workout

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MiniXmlTest {
    @Test
    fun `reads nested elements, attributes and text`() {
        val root = parseXml("<a x=\"1\" y='two'><b>hello</b><c/><b>again</b></a>")
        assertEquals("a", root.name)
        assertEquals("1", root.attribute("x"))
        assertEquals("two", root.attribute("y"))
        assertNull(root.attribute("z"))
        assertEquals(listOf("b", "c", "b"), root.children.map { it.name })
        assertEquals("hello", root.child("b")?.text)
        assertEquals("", root.text)
    }

    @Test
    fun `names and attribute names are case-insensitive`() {
        val root = parseXml("<Workout_File><SteadyState DURATION=\"60\" Power=\"0.5\"/></Workout_File>")
        assertEquals("workout_file", root.name)
        val step = root.child("STEADYSTATE")!!
        assertEquals("60", step.attribute("duration"))
        assertEquals("0.5", step.attribute("POWER"))
    }

    @Test
    fun `decodes the predefined and numeric entities in text and attributes`() {
        val root = parseXml("<a t=\"&quot;q&quot; &amp; &apos;s&apos;\">&lt;x&gt; &#65;&#x42; &#x1F600;</a>")
        assertEquals("\"q\" & 's'", root.attribute("t"))
        assertEquals("<x> AB 😀", root.text)
    }

    @Test
    fun `leaves unknown or malformed entities as written`() {
        val root = parseXml("<a>fish &chips; &amp and &#xZZ; &</a>")
        assertEquals("fish &chips; &amp and &#xZZ; &", root.text)
    }

    @Test
    fun `skips the declaration, comments, processing instructions and a doctype with an internal subset`() {
        val root =
            parseXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE note [ <!ENTITY e "x"> ]>
                <!-- a <fake> comment -->
                <?pi something?>
                <a><!-- inside <b/> --><c/></a>
                """.trimIndent(),
            )
        assertEquals("a", root.name)
        assertEquals(listOf("c"), root.children.map { it.name })
    }

    @Test
    fun `reads cdata as text without decoding it`() {
        val root = parseXml("<a><![CDATA[1 < 2 && 3 > 2 &amp;]]></a>")
        assertEquals("1 < 2 && 3 > 2 &amp;", root.text)
    }

    @Test
    fun `tolerates a byte order mark, crlf line endings and odd spacing`() {
        val root = parseXml("﻿<a\r\n  x = \"1\"\r\n>\r\n  <b />\r\n</a >")
        assertEquals("1", root.attribute("x"))
        assertEquals(listOf("b"), root.children.map { it.name })
    }

    @Test
    fun `tolerates attributes without a value or without quotes`() {
        val root = parseXml("<a flag n=5 m=six/>")
        assertEquals("", root.attribute("flag"))
        assertEquals("5", root.attribute("n"))
        assertEquals("six", root.attribute("m"))
    }

    @Test
    fun `ignores a stray closing tag and closes what is still open at the end`() {
        val stray = parseXml("<a><b></c></b></a>")
        assertEquals(listOf("b"), stray.children.map { it.name })

        val unclosed = parseXml("<a><b><c></a>")
        assertEquals(listOf("b"), unclosed.children.map { it.name })
        assertEquals(listOf("c"), unclosed.child("b")!!.children.map { it.name })

        val truncated = parseXml("<a><b x=\"1\"/><c>text")
        assertEquals(listOf("b", "c"), truncated.children.map { it.name })
        assertEquals("text", truncated.child("c")!!.text)
    }

    @Test
    fun `ignores everything after the root element`() {
        val root = parseXml("<a/><b/>trailing")
        assertEquals("a", root.name)
    }

    @Test
    fun `an element's text excludes its children and is trimmed`() {
        val root = parseXml("<a>  one <b>inner</b> two  </a>")
        assertEquals("one  two", root.text)
    }

    @Test
    fun `rejects input that is not xml`() {
        assertFailsWith<WorkoutParseException> { parseXml("") }
        assertFailsWith<WorkoutParseException> { parseXml("just text") }
        assertFailsWith<WorkoutParseException> { parseXml("<!-- only a comment -->") }
        assertFailsWith<WorkoutParseException> { parseXml("<a><!-- never closed </a>") }
        assertFailsWith<WorkoutParseException> { parseXml("<a b=\"unterminated></a>") }
        assertFailsWith<WorkoutParseException> { parseXml("<a <b/>") }
        assertFailsWith<WorkoutParseException> { parseXml("< a/>") }
        assertFailsWith<WorkoutParseException> { parseXml("<a><![CDATA[never closed</a>") }
    }
}
