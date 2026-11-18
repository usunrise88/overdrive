package app.overdrive.core.ble.recording

import app.overdrive.core.ble.GattUuid
import app.overdrive.core.ble.IdentityUuids

/**
 * What a recorded session may contain (docs/protocols/ftms-hr.md §7.1, `CLAUDE.md`: the repository is public and
 * recordings of the owner's body stay out of it). The recorder holds a [DeviceId][app.overdrive.core.ble.DeviceId] for
 * nobody: lines carry timings, characteristics and bytes, nothing that names a unit. On top of that:
 *
 * - the **model name** in the header is the advertised model only: separators split it into words, and a word that looks
 *   like a serial number (four or more digits, or five or more hex digits mixing digits and letters) is dropped, as is
 *   everything but letters, digits, space, dot, hyphen and underscore, and the result is cut to 24 characters;
 * - the **values of identifying characteristics** (serial number, system id, device name, firmware and hardware revision,
 *   manufacturer and model number strings) are never written: the line keeps its place and status, the data is `*`.
 */
public object BleRecordingPolicy {
    private const val MAX_MODEL_LENGTH = 24
    private const val SERIAL_DIGITS = 4
    private const val SERIAL_HEX_LENGTH = 5

    /** Characteristics whose values are replaced by `*`. */
    public val redactedCharacteristics: Set<GattUuid> = IdentityUuids.ALL

    /** Whether the value of [characteristic] must not be recorded. */
    public fun isRedacted(characteristic: GattUuid): Boolean = characteristic in redactedCharacteristics

    /**
     * The advertised model name made safe to write, or null if nothing is left. `Coospo HR7-123456` becomes
     * `Coospo HR7`, `D500` stays `D500`.
     */
    public fun sanitizeModel(advertised: String?): String? {
        if (advertised == null) return null
        val cleaned = advertised.filter { it.isLetterOrDigit() || it in " ._-:" }
        val words =
            cleaned
                .split(' ', '-', '_', ':', '.')
                .filter { it.isNotEmpty() && !looksLikeSerial(it) }
        // Keep the original separators out of it: the model is a label, words joined by spaces are enough.
        val model = words.joinToString(" ").take(MAX_MODEL_LENGTH).trim()
        return model.ifEmpty { null }
    }

    private fun looksLikeSerial(word: String): Boolean {
        val digits = word.count { it.isDigit() }
        if (digits >= SERIAL_DIGITS) return true
        val allHex = word.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        return allHex && word.length >= SERIAL_HEX_LENGTH && digits > 0 && word.any { it.isLetter() }
    }
}
