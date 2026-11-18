package app.overdrive.core.ble.recording

import app.overdrive.core.ble.ClickUuids
import app.overdrive.core.ble.GattUuid
import app.overdrive.core.ble.IdentityUuids

/**
 * What a recorded session may contain (docs/protocols/ftms-hr.md §7.1, `CLAUDE.md`: the repository is public and
 * recordings of the owner's body stay out of it). The recorder holds a [DeviceId][app.overdrive.core.ble.DeviceId] for
 * nobody: lines carry timings, characteristics and bytes, nothing that names a unit. On top of that:
 *
 * - the **model name** in the header is the advertised model only: it keeps ASCII letters and digits, separators split it
 *   into words, and a word that looks like a serial number (four or more digits, or five or more hex digits mixing digits
 *   and letters) is dropped, as is a run of three or more two-digit hex groups (a hardware address); the result is cut to
 *   24 characters;
 * - the **values of identifying characteristics** (serial number, system id, device name, firmware and hardware revision,
 *   manufacturer and model number strings) are never written: the line keeps its place and status, the data is `*`;
 * - the **public key a Zwift Click may append to its handshake reply** (docs/protocols/click-v2.md §3.1, §9 item 3) is
 *   cut: a reply that starts with `RideOn` keeps its first eight bytes (`RideOn` and the two version bytes) and the rest
 *   becomes zeros of the same length, because a key that is the same on every connection would name the unit. The length
 *   survives, which is what the bench question asks, and the adapter ignores the tail either way.
 */
public object BleRecordingPolicy {
    private const val MAX_MODEL_LENGTH = 24
    private const val SERIAL_DIGITS = 4
    private const val SERIAL_HEX_LENGTH = 5
    private const val ADDRESS_GROUPS = 3
    private const val SEPARATORS = " ._-:"

    /** `RideOn` in ASCII: the Click's handshake word (docs/protocols/click-v2.md §3.1). */
    private val RIDE_ON = byteArrayOf(0x52, 0x69, 0x64, 0x65, 0x4F, 0x6E)

    /** `RideOn` and the two version bytes. */
    private const val HANDSHAKE_KEEP = 8

    /** Characteristics whose values are replaced by `*`. */
    public val redactedCharacteristics: Set<GattUuid> = IdentityUuids.ALL

    /** Whether the value of [characteristic] must not be recorded. */
    public fun isRedacted(characteristic: GattUuid): Boolean = characteristic in redactedCharacteristics

    /**
     * The bytes of [characteristic] as they may be written: unchanged, except that the tail of a Click handshake reply is
     * zeroed (see above). Applies to the Click's own characteristics only; a null characteristic is a state line and has no
     * value.
     */
    public fun scrub(
        characteristic: GattUuid?,
        bytes: ByteArray,
    ): ByteArray {
        val clickValue = characteristic == ClickUuids.SYNC_TX || characteristic == ClickUuids.ASYNC
        if (!clickValue || bytes.size <= HANDSHAKE_KEEP || !startsWithRideOn(bytes)) return bytes
        return bytes.copyOf(HANDSHAKE_KEEP) + ByteArray(bytes.size - HANDSHAKE_KEEP)
    }

    private fun startsWithRideOn(bytes: ByteArray): Boolean =
        bytes.size >= RIDE_ON.size && RIDE_ON.indices.all { bytes[it] == RIDE_ON[it] }

    /**
     * The advertised model name made safe to write, or null if nothing is left. `Coospo HR7-123456` becomes
     * `Coospo HR7`, `D500` stays `D500`.
     */
    public fun sanitizeModel(advertised: String?): String? {
        if (advertised == null) return null
        val cleaned = advertised.filter { isAscii(it) || it in SEPARATORS }
        val tokens = cleaned.split(' ', '-', '_', ':', '.').filter { it.isNotEmpty() }
        val words = withoutAddresses(tokens).filter { !looksLikeSerial(it) }
        // The model is a label: words joined by single spaces are enough, the original separators are not kept.
        val model = words.joinToString(" ").take(MAX_MODEL_LENGTH).trim()
        return model.ifEmpty { null }
    }

    private fun isAscii(char: Char): Boolean = char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9'

    private fun isHexPair(token: String): Boolean =
        token.length == 2 && token.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    /** Drops every run of three or more two-digit hex groups, which is how a hardware address looks once split. */
    private fun withoutAddresses(tokens: List<String>): List<String> {
        val kept = ArrayList<String>(tokens.size)
        var i = 0
        while (i < tokens.size) {
            var end = i
            while (end < tokens.size && isHexPair(tokens[end])) end++
            if (end - i >= ADDRESS_GROUPS) {
                i = end
            } else if (end > i) {
                kept += tokens.subList(i, end)
                i = end
            } else {
                kept += tokens[i]
                i++
            }
        }
        return kept
    }

    private fun looksLikeSerial(word: String): Boolean {
        val digits = word.count { it.isDigit() }
        if (digits >= SERIAL_DIGITS) return true
        val allHex = word.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        return allHex && word.length >= SERIAL_HEX_LENGTH && digits > 0 && word.any { it.isLetter() }
    }
}
