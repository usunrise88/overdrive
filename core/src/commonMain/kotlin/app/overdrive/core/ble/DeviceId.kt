package app.overdrive.core.ble

import kotlin.jvm.JvmInline

/**
 * An opaque handle to one Bluetooth peripheral, handed out by the transport layer (on Android, derived from the
 * hardware address, which this type never exposes). Core and everything that can reach a log, a ride record or a
 * recorded session sees only this: [toString] prints a short non-reversible fingerprint, never [value], so a stray
 * `println` or an exception message cannot leak an address into the repository (docs/protocols/ftms-hr.md §7.1).
 *
 * Only the transport implementation and the device store, which live outside `core`, ever read [value].
 */
@JvmInline
public value class DeviceId(
    public val value: String,
) {
    init {
        require(value.isNotBlank()) { "A device id must not be blank" }
    }

    /** `DeviceId(#a1b2)`: 16 bits of FNV-1a, enough to tell devices apart in a log, not enough to recover [value]. */
    override fun toString(): String = "DeviceId(#${fingerprint(value)})"

    private companion object {
        const val FNV_OFFSET = 0x811C9DC5L
        const val FNV_PRIME = 0x01000193L
        const val MASK_32 = 0xFFFFFFFFL
        const val MASK_16 = 0xFFFFL
        const val FINGERPRINT_DIGITS = 4

        fun fingerprint(text: String): String {
            var hash = FNV_OFFSET
            for (char in text) {
                hash = ((hash xor char.code.toLong()) * FNV_PRIME) and MASK_32
            }
            return (hash and MASK_16).toString(16).padStart(FINGERPRINT_DIGITS, '0')
        }
    }
}
