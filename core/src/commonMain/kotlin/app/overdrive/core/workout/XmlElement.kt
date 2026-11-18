package app.overdrive.core.workout

/** One element of a parsed XML document. Names and attribute names are lower-cased for tolerant lookup. */
internal class XmlElement(
    val name: String,
    val attributes: Map<String, String>,
    val children: List<XmlElement>,
    /** The element's own text nodes joined and trimmed; child elements are not included. */
    val text: String,
) {
    fun attribute(name: String): String? = attributes[name.lowercase()]

    fun child(name: String): XmlElement? = children.firstOrNull { it.name == name.lowercase() }
}

/**
 * A small, tolerant XML reader for workout files, in pure Kotlin so `core` needs no platform XML API.
 *
 * It reads what files from workout editors contain: an XML declaration, comments, a DOCTYPE, CDATA,
 * elements with single- or double-quoted attributes, self-closing tags, the five predefined entities
 * and numeric character references. It does not validate: unknown entities are kept as written, an
 * attribute without a value reads as empty, a stray closing tag is ignored, one that closes an
 * ancestor also closes everything inside it, and elements still open at the end of the input are
 * closed there. It is not a general XML parser (no namespaces, no external entities).
 *
 * @throws WorkoutParseException if the input has no root element or contains a malformed tag.
 */
internal fun parseXml(input: String): XmlElement = MiniXmlReader(input.removePrefix("")).readDocument()

private class MiniXmlReader(
    private val s: String,
) {
    private var pos = 0

    private class OpenElement(
        val name: String,
        val attributes: Map<String, String>,
    ) {
        val children = mutableListOf<XmlElement>()
        val text = StringBuilder()
    }

    fun readDocument(): XmlElement {
        val stack = mutableListOf<OpenElement>()
        var root: XmlElement? = null

        fun close(open: OpenElement) {
            val element = XmlElement(open.name, open.attributes, open.children, open.text.toString().trim())
            val parent = stack.lastOrNull()
            if (parent != null) {
                parent.children += element
            } else if (root == null) {
                root = element
            }
        }

        while (pos < s.length) {
            when {
                s.startsWith("<!--", pos) -> {
                    skipPast("-->", "comment")
                }

                s.startsWith("<![CDATA[", pos) -> {
                    val end = s.indexOf("]]>", pos)
                    if (end < 0) fail("Unterminated CDATA section")
                    stack.lastOrNull()?.text?.append(s.substring(pos + CDATA_OPEN.length, end))
                    pos = end + CDATA_CLOSE.length
                }

                s.startsWith("<?", pos) -> {
                    skipPast("?>", "processing instruction")
                }

                s.startsWith("<!", pos) -> {
                    skipDeclaration()
                }

                s.startsWith("</", pos) -> {
                    val end = s.indexOf('>', pos)
                    if (end < 0) fail("Unterminated closing tag")
                    val name = s.substring(pos + 2, end).trim().lowercase()
                    pos = end + 1
                    val index = stack.indexOfLast { it.name == name }
                    if (index >= 0) {
                        while (stack.size > index) close(stack.removeAt(stack.lastIndex))
                    }
                }

                s[pos] == '<' -> {
                    val (open, selfClosing) = readStartTag()
                    if (selfClosing) {
                        close(open)
                    } else {
                        stack += open
                    }
                }

                else -> {
                    val next = s.indexOf('<', pos).let { if (it < 0) s.length else it }
                    stack.lastOrNull()?.text?.append(decodeEntities(s.substring(pos, next)))
                    pos = next
                }
            }
            if (stack.isEmpty() && root != null) break
        }
        while (stack.isNotEmpty()) close(stack.removeAt(stack.lastIndex))
        return root ?: fail("No root element")
    }

    private fun readStartTag(): Pair<OpenElement, Boolean> {
        pos++ // '<'
        val name = readName()
        if (name.isEmpty()) fail("Expected an element name")
        val attributes = LinkedHashMap<String, String>()
        while (true) {
            skipWhitespace()
            if (pos >= s.length) fail("Unterminated tag <$name")
            when {
                s[pos] == '>' -> {
                    pos++
                    return OpenElement(name.lowercase(), attributes) to false
                }

                s.startsWith("/>", pos) -> {
                    pos += 2
                    return OpenElement(name.lowercase(), attributes) to true
                }

                s[pos] == '/' -> {
                    pos++
                }

                else -> {
                    val attribute = readName()
                    if (attribute.isEmpty()) fail("Unexpected '${s[pos]}' in tag <$name")
                    skipWhitespace()
                    var value = ""
                    if (pos < s.length && s[pos] == '=') {
                        pos++
                        skipWhitespace()
                        value = readAttributeValue(name)
                    }
                    attributes[attribute.lowercase()] = value
                }
            }
        }
    }

    private fun readAttributeValue(tag: String): String {
        if (pos >= s.length) fail("Unterminated tag <$tag")
        val quote = s[pos]
        if (quote == '"' || quote == '\'') {
            val end = s.indexOf(quote, pos + 1)
            if (end < 0) fail("Unterminated attribute value in <$tag")
            val raw = s.substring(pos + 1, end)
            pos = end + 1
            return decodeEntities(raw)
        }
        val start = pos
        while (pos < s.length && !s[pos].isWhitespace() && s[pos] != '>' && !s.startsWith("/>", pos)) pos++
        return decodeEntities(s.substring(start, pos))
    }

    private fun readName(): String {
        val start = pos
        while (pos < s.length && (s[pos].isLetterOrDigit() || s[pos] in NAME_PUNCTUATION)) pos++
        return s.substring(start, pos)
    }

    private fun skipWhitespace() {
        while (pos < s.length && s[pos].isWhitespace()) pos++
    }

    private fun skipPast(
        terminator: String,
        what: String,
    ) {
        val end = s.indexOf(terminator, pos)
        if (end < 0) fail("Unterminated $what")
        pos = end + terminator.length
    }

    /** Skips `<!DOCTYPE ...>`, including an internal subset in square brackets. */
    private fun skipDeclaration() {
        var depth = 0
        while (pos < s.length) {
            when (s[pos]) {
                '[' -> {
                    depth++
                }

                ']' -> {
                    depth--
                }

                '>' -> {
                    if (depth <= 0) {
                        pos++
                        return
                    }
                }
            }
            pos++
        }
        fail("Unterminated declaration")
    }

    private fun decodeEntities(raw: String): String {
        if ('&' !in raw) return raw
        val out = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            val semicolon = if (c == '&') raw.indexOf(';', i) else -1
            val replacement =
                if (semicolon in
                    (i + 1)..(i + MAX_ENTITY_LENGTH)
                ) {
                    entity(raw.substring(i + 1, semicolon))
                } else {
                    null
                }
            if (replacement != null) {
                out.append(replacement)
                i = semicolon + 1
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    private fun entity(name: String): String? =
        when {
            name == "amp" -> "&"
            name == "lt" -> "<"
            name == "gt" -> ">"
            name == "quot" -> "\""
            name == "apos" -> "'"
            name.startsWith("#x") || name.startsWith("#X") -> name.drop(2).toIntOrNull(HEX_RADIX)?.let(::codePoint)
            name.startsWith("#") -> name.drop(1).toIntOrNull()?.let(::codePoint)
            else -> null
        }

    private fun codePoint(value: Int): String? =
        when {
            value in 0..0xFFFF && value !in 0xD800..0xDFFF -> {
                value.toChar().toString()
            }

            value in 0x10000..0x10FFFF -> {
                val v = value - 0x10000
                charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
            }

            else -> {
                null
            }
        }

    private fun fail(message: String): Nothing = throw WorkoutParseException("Invalid XML at offset $pos: $message")

    private companion object {
        const val CDATA_OPEN = "<![CDATA["
        const val CDATA_CLOSE = "]]>"
        const val NAME_PUNCTUATION = "_-.:"
        const val MAX_ENTITY_LENGTH = 10
        const val HEX_RADIX = 16
    }
}
