package app.overdrive.core.click

/**
 * The bits of the Click v2 button bitmap (docs/protocols/click-v2.md §4, the table "Button bitmap for Click v2"). The
 * controller reports every button in one 32-bit word where **a clear bit means pressed** and a set bit means released, so
 * the all-released frame is `0xFFFFFFFF`. [pressedOf] turns a bitmap into the mask of pressed buttons, which is what the
 * rest of the code works with.
 *
 * The masks are the notes' two-source reading (BikeControl's `RideButtonMask` and trainer-control's `RIDE_MASK`) and are
 * **unverified on a real controller** until the bench (click-v2 §9 item 5, bench-checklist 5.5). The right puck has five
 * buttons ([A], [B], [Y], [Z], [PLUS]); the left puck's navigation diamond and minus are listed because they share the
 * word, and because a frame from the wrong side should be recognisable in a log.
 */
public object ClickBits {
    /** Left puck, navigation left. */
    public const val NAV_LEFT: Int = 0x0001

    /** Left puck, navigation up. */
    public const val NAV_UP: Int = 0x0002

    /** Left puck, navigation right. */
    public const val NAV_RIGHT: Int = 0x0004

    /** Left puck, navigation down. */
    public const val NAV_DOWN: Int = 0x0008

    /** Right puck, A: to the right of the diamond. */
    public const val A: Int = 0x0010

    /** Right puck, B: the bottom of the diamond, directly above [PLUS]. */
    public const val B: Int = 0x0020

    /** Right puck, Y: the top of the diamond. */
    public const val Y: Int = 0x0040

    /** Right puck, Z: to the left of the diamond. */
    public const val Z: Int = 0x0080

    /** Left puck, minus (the notes call it shift up, left). */
    public const val MINUS: Int = 0x0100

    /** Left puck, minus (the notes call it shift down, left): a second bit the notes list for the same button. */
    public const val MINUS_SHIFT_DOWN: Int = 0x0200

    /** Right puck, plus (the notes call it shift up, right), under [B]. */
    public const val PLUS: Int = 0x1000

    /** Right puck, plus (the notes call it shift down, right): a second bit the notes list for the same button. */
    public const val PLUS_SHIFT_DOWN: Int = 0x2000

    /** The five buttons of the right puck: the ones this app uses. */
    public const val RIGHT_PUCK: Int = A or B or Y or Z or PLUS

    /** The buttons of the left puck. */
    public const val LEFT_PUCK: Int = NAV_LEFT or NAV_UP or NAV_RIGHT or NAV_DOWN or MINUS

    /** The bitmap of a controller with nothing pressed: every bit set. */
    public const val ALL_RELEASED: Int = -1

    /** The mask of buttons pressed in [bitmap] (the bits that are clear in it). */
    public fun pressedOf(bitmap: Int): Int = bitmap.inv()

    /** The bitmap that says exactly the buttons in [pressed] are pressed (the inverse of [pressedOf]). */
    public fun bitmapOf(pressed: Int): Int = pressed.inv()

    private val names =
        listOf(
            NAV_LEFT to "NAV_LEFT",
            NAV_UP to "NAV_UP",
            NAV_RIGHT to "NAV_RIGHT",
            NAV_DOWN to "NAV_DOWN",
            A to "A",
            B to "B",
            Y to "Y",
            Z to "Z",
            MINUS to "MINUS",
            MINUS_SHIFT_DOWN to "MINUS_SHIFT_DOWN",
            PLUS to "PLUS",
            PLUS_SHIFT_DOWN to "PLUS_SHIFT_DOWN",
        )

    /**
     * The names of the bits set in [mask], for logs and the learn screen: the bits the notes name first, in the order of
     * the table (`A`, `PLUS`), then each bit they do not name as hex (`0x4000`), lowest first.
     */
    public fun namesOf(mask: Int): List<String> {
        val result = ArrayList<String>()
        var known = 0
        for ((bit, name) in names) {
            known = known or bit
            if (mask and bit != 0) result += name
        }
        for (position in 0 until Int.SIZE_BITS) {
            val bit = 1 shl position
            if (mask and bit != 0 && known and bit == 0) result += "0x${bit.toUInt().toString(HEX_RADIX)}"
        }
        return result
    }

    private const val HEX_RADIX = 16
}
