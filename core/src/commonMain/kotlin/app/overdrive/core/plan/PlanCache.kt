package app.overdrive.core.plan

import app.overdrive.core.record.Crc32

/**
 * The last good answer of the service, as kept on the tablet so the list of planned workouts works offline and after a
 * restart (spec §5.7, ADR 0017).
 *
 * @property source which service it came from ([PlanCacheFormat.sourceOf] of the address): an answer from another address is
 * not this rider's plan, so a changed address leaves the cache unused.
 * @property fetchedAtMillis wall-clock milliseconds when the tablet got it.
 */
public class CachedPlan(
    public val source: String,
    public val fetchedAtMillis: Long,
    public val response: WorkoutsResponse,
)

/** Where [CachedPlan] is kept: one small file on the tablet (`android/data`'s `FilePlanCache`), memory in a test. */
public interface PlanCache {
    /** The cached answer, or null when there is none or what is there is damaged. Never throws for a damaged cache. */
    public suspend fun read(): CachedPlan?

    /** Replaces the cache with [plan]. A failure is thrown; the repository reports it and goes on without a cache. */
    public suspend fun write(plan: CachedPlan)
}

/**
 * The text a [PlanCache] keeps: one header line, then the answer exactly as the service wrote it.
 *
 * ```
 * overdrive-plan 1 <source, 8 hex digits> <fetched at, millis> <CRC-32 of the body, 8 hex digits>
 * { "from": ..., "workouts": [ ... ] }
 * ```
 *
 * Reading is strict: a header that is not exactly this, a body whose checksum differs (a torn or damaged file) and a body that is
 * not a valid answer of the contract all read as nothing, never as an exception and never as a half answer.
 */
public object PlanCacheFormat {
    private const val MAGIC = "overdrive-plan"
    private const val VERSION = "1"
    private const val HEX = 16
    private const val CRC_DIGITS = 8
    private const val HEADER_FIELDS = 5

    /** The identity of a service address: the CRC-32 of the address without a trailing slash, in hex. Not a secret, not a hash to trust. */
    public fun sourceOf(baseUrl: String): String = hex(Crc32.of(baseUrl.trimEnd('/').encodeToByteArray()))

    public fun encode(plan: CachedPlan): String {
        val body = PlanApiJson.encodeWorkoutsResponse(plan.response)
        val crc = hex(Crc32.of(body.encodeToByteArray()))
        return "$MAGIC $VERSION ${plan.source} ${plan.fetchedAtMillis} $crc\n$body"
    }

    /** The plan in [text], or null for anything that is not exactly what [encode] writes. */
    public fun decode(text: String): CachedPlan? {
        val newline = text.indexOf('\n')
        if (newline < 0) return null
        val header = text.substring(0, newline).split(' ')
        if (header.size != HEADER_FIELDS || header[0] != MAGIC || header[1] != VERSION) return null
        val source = header[2].takeIf { it.length == CRC_DIGITS && it.all(::isHexDigit) } ?: return null
        val fetchedAt = header[3].toLongOrNull()?.takeIf { it > 0 } ?: return null
        val body = text.substring(newline + 1)
        if (header[4] != hex(Crc32.of(body.encodeToByteArray()))) return null
        val response =
            try {
                PlanApiJson.decodeWorkoutsResponse(body)
            } catch (e: IllegalArgumentException) {
                return null
            }
        return CachedPlan(source, fetchedAt, response)
    }

    private fun hex(value: Long): String = value.toString(HEX).padStart(CRC_DIGITS, '0')

    private fun isHexDigit(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f'
}
