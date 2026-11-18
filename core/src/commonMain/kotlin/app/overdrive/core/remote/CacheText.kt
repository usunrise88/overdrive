package app.overdrive.core.remote

import app.overdrive.core.record.Crc32

/** Where one cached answer is kept: a small file on the phone (`android/data`'s `FileTextStore`), memory in a test. */
public interface TextStore {
    /** The text, or null when there is none or it cannot be read. Never throws for a damaged store. */
    public suspend fun read(): String?

    /** Replaces the text. A failure is thrown; the repository goes on without a cache. */
    public suspend fun write(text: String)
}

/**
 * One answer of the service as the phone keeps it on disk: the body exactly as the service wrote it, with where it came from
 * and when it was fetched.
 *
 * @property source which service it came from ([CacheText.sourceOf] of the address): an answer from another address is not this
 * rider's, so a changed address leaves the cache unused.
 * @property fetchedAtMillis wall-clock milliseconds when the phone got it ("Updated 07:40").
 */
public class CachedBody(
    public val source: String,
    public val fetchedAtMillis: Long,
    public val body: String,
)

/**
 * The text a [TextStore] keeps: one header line, then the answer exactly as the service wrote it.
 *
 * ```
 * overdrive-cache 1 <kind> <source, 8 hex digits> <fetched at, millis> <CRC-32 of the body, 8 hex digits>
 * { ...the answer... }
 * ```
 *
 * `kind` names what the answer is ("metrics-26w", "rides", "ride-detail"), so a file can never be read as another resource.
 * Reading is strict: a header that is not exactly this, another kind, a body whose checksum differs (a torn or damaged file)
 * all read as nothing, never as an exception and never as half an answer. Whether the body is a valid answer of the contract is
 * the resource's to say. The file holds no secret: the rider's figures and a checksum of the address, never the token.
 */
public object CacheText {
    private const val MAGIC = "overdrive-cache"
    private const val VERSION = "1"
    private const val HEX = 16
    private const val CRC_DIGITS = 8
    private const val HEADER_FIELDS = 6

    /** The identity of a service address: the CRC-32 of the address without a trailing slash, in hex. Not a secret, not a hash to trust. */
    public fun sourceOf(baseUrl: String): String = hex(Crc32.of(baseUrl.trimEnd('/').encodeToByteArray()))

    public fun encode(
        kind: String,
        cached: CachedBody,
    ): String {
        require(kind.isNotEmpty() && kind.none { it.isWhitespace() }) { "A kind is one word" }
        val crc = hex(Crc32.of(cached.body.encodeToByteArray()))
        return "$MAGIC $VERSION $kind ${cached.source} ${cached.fetchedAtMillis} $crc\n${cached.body}"
    }

    /** The answer in [text], or null for anything that is not exactly what [encode] writes for [kind]. */
    public fun decode(
        kind: String,
        text: String,
    ): CachedBody? {
        val newline = text.indexOf('\n')
        if (newline < 0) return null
        val header = text.substring(0, newline).split(' ')
        if (header.size != HEADER_FIELDS || header[0] != MAGIC || header[1] != VERSION || header[2] != kind) return null
        val source = header[3].takeIf { it.length == CRC_DIGITS && it.all(::isHexDigit) } ?: return null
        val fetchedAt = header[4].toLongOrNull()?.takeIf { it > 0 } ?: return null
        val body = text.substring(newline + 1)
        if (header[5] != hex(Crc32.of(body.encodeToByteArray()))) return null
        return CachedBody(source, fetchedAt, body)
    }

    private fun hex(value: Long): String = value.toString(HEX).padStart(CRC_DIGITS, '0')

    private fun isHexDigit(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f'
}
