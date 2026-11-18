package app.overdrive.core.profile

import app.overdrive.core.record.Crc32

/**
 * What the tablet keeps of the rider's profile so it works offline and after a restart (spec §8.3, ADR 0021): the last good
 * answer of the service and the one edit made on the tablet that the service has not yet taken.
 *
 * @property source which service the [profile] came from ([ProfileCacheFormat.sourceOf] of the address): a profile of another
 * address is not this rider's, so a changed address leaves the cache unused.
 * @property profile the service's last answer, as it sent it.
 * @property pending the tablet's edit that has not reached the service ([ProfilePatch.andThen] of every edit since), or null.
 * It is applied over [profile] wherever the profile is used, and sent at the next opportunity.
 */
public class CachedProfile(
    public val source: String,
    public val profile: Profile,
    public val pending: ProfilePatch?,
) {
    init {
        require(pending == null || !pending.isEmpty) { "An empty edit is no edit" }
    }

    /** The profile in force: the service's with the unsent edit on top. */
    public fun effective(): Profile =
        try {
            pending?.applyTo(profile) ?: profile
        } catch (e: IllegalArgumentException) {
            // An edit that no longer makes a valid profile (the service's changed under it) is not applied; the repository drops it.
            profile
        }

    override fun equals(other: Any?): Boolean =
        other is CachedProfile && source == other.source && profile == other.profile && pending == other.pending

    override fun hashCode(): Int = 31 * (31 * source.hashCode() + profile.hashCode()) + (pending?.hashCode() ?: 0)

    override fun toString(): String = "CachedProfile(source=$source, pending=${pending != null})"
}

/** Where [CachedProfile] is kept: one small file on the tablet (`android/data`'s `FileProfileCache`), memory in a test. */
public interface ProfileCache {
    /** The cached profile, or null when there is none or what is there is damaged. Never throws for a damaged cache. */
    public suspend fun read(): CachedProfile?

    /** Replaces the cache with [profile]. A failure is thrown; the repository reports it and goes on, the next change tries again. */
    public suspend fun write(profile: CachedProfile)
}

/**
 * The text a [ProfileCache] keeps: one header line, then the profile exactly as `ProfileApiJson` writes it on one line, then a
 * second line with the unsent edit (empty when there is none).
 *
 * ```
 * overdrive-profile 1 <source, 8 hex digits> <CRC-32 of everything after this line, 8 hex digits>
 * { "ftp": 175, "zones": ..., "physics": ... }
 * { "ftp": 180 }
 * ```
 *
 * Reading is strict, like the plan's cache: a header that is not exactly this, a body whose checksum differs (a torn or damaged
 * file), a profile that is not valid by the schema, an edit that is not a patch of the schema or does not make a valid profile of
 * this one, all read as nothing, never as an exception and never as a half answer.
 */
public object ProfileCacheFormat {
    private const val MAGIC = "overdrive-profile"
    private const val VERSION = "1"
    private const val HEX = 16
    private const val CRC_DIGITS = 8
    private const val HEADER_FIELDS = 4

    /** The identity of a service address: the CRC-32 of the address without a trailing slash, in hex. Not a secret, not a hash to trust. */
    public fun sourceOf(baseUrl: String): String = hex(Crc32.of(baseUrl.trimEnd('/').encodeToByteArray()))

    public fun encode(cached: CachedProfile): String {
        val body =
            ProfileApiJson.encodeProfile(cached.profile) + "\n" +
                (cached.pending?.let(ProfileApiJson::encodePatch) ?: "")
        return "$MAGIC $VERSION ${cached.source} ${hex(Crc32.of(body.encodeToByteArray()))}\n$body"
    }

    /** The profile in [text], or null for anything that is not exactly what [encode] writes. */
    public fun decode(text: String): CachedProfile? {
        val newline = text.indexOf('\n')
        if (newline < 0) return null
        val header = text.substring(0, newline).split(' ')
        if (header.size != HEADER_FIELDS || header[0] != MAGIC || header[1] != VERSION) return null
        val source = header[2].takeIf { it.length == CRC_DIGITS && it.all(::isHexDigit) } ?: return null
        val body = text.substring(newline + 1)
        if (header[3] != hex(Crc32.of(body.encodeToByteArray()))) return null
        val lines = body.split('\n')
        if (lines.size != 2) return null
        return try {
            val profile = ProfileApiJson.decodeProfile(lines[0])
            val pending = lines[1].takeIf { it.isNotEmpty() }?.let(ProfileApiJson::decodePatch)?.takeIf { !it.isEmpty }
            pending?.applyTo(profile)
            CachedProfile(source, profile, pending)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun hex(value: Long): String = value.toString(HEX).padStart(CRC_DIGITS, '0')

    private fun isHexDigit(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f'
}
