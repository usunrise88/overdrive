package app.overdrive.core.ble

/**
 * The GATT layout of the Zwift Click v2 (docs/protocols/click-v2.md §1 and §2): the custom service it advertises, the
 * older one it may be found under, and the three characteristics inside. Facts the protocol requires, written down with
 * their sources in the notes; nothing here is copied from code.
 */
public object ClickUuids {
    /** Zwift's Bluetooth company identifier, `0x094A`, in the manufacturer data of an advertisement (§1). */
    public const val MANUFACTURER_ID: Int = 0x094A

    /** `0000FC82-0000-1000-8000-00805F9B34FB`: the service a Click v2 advertises and offers (§1, §2). */
    public val SERVICE: GattUuid = GattUuid.short(0xFC82)

    /** `00000001-19CA-4651-86E5-FA29DCDD09D1`: the older service of Click v1 and Play, a fallback (§2). */
    public val LEGACY_SERVICE: GattUuid = vendor(0x0001)

    /** Notify: button frames and vendor messages (§2). */
    public val ASYNC: GattUuid = vendor(0x0002)

    /** Write and write without response: the host's handshake and commands (§2). */
    public val SYNC_RX: GattUuid = vendor(0x0003)

    /** Indicate (and read): the handshake reply (§2). */
    public val SYNC_TX: GattUuid = vendor(0x0004)

    private fun vendor(number: Int): GattUuid =
        requireNotNull(
            GattUuid.parse("0000${number.toString(HEX_RADIX).padStart(HEX_DIGITS, '0')}-19CA-4651-86E5-FA29DCDD09D1"),
        ) { "Malformed vendor UUID $number" }

    private const val HEX_RADIX = 16
    private const val HEX_DIGITS = 4
}
