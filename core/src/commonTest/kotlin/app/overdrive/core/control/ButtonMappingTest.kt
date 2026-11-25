package app.overdrive.core.control

import app.overdrive.core.click.ClickBits
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The mapping of the five roles to the Click's bits (spec §4.7: "The mapping is a setting"). */
class ButtonMappingTest {
    @Test
    fun `the default is the documented ergonomic guess`() {
        val mapping = ButtonMapping.DEFAULT
        assertEquals(ClickBits.PLUS, mapping.maskOf(HandlebarButton.PLUS), "Plus is gear up")
        assertEquals(ClickBits.B, mapping.maskOf(HandlebarButton.BUTTON_2), "B, directly above Plus, is gear down")
        assertEquals(ClickBits.Y, mapping.maskOf(HandlebarButton.BUTTON_3), "Y, the far one, is play and pause")
        assertEquals(ClickBits.A, mapping.maskOf(HandlebarButton.BUTTON_4), "A, right, is volume up")
        assertEquals(ClickBits.Z, mapping.maskOf(HandlebarButton.BUTTON_5), "Z, left, is volume down")
        assertEquals(ClickBits.RIGHT_PUCK, mapping.allBits, "and together they are the five buttons of the right puck")
    }

    @Test
    fun `a role is down while any of its bits is down and bits nobody owns are ignored`() {
        val mapping = ButtonMapping.DEFAULT
        assertEquals(emptySet(), mapping.rolesDown(0))
        assertEquals(setOf(HandlebarButton.PLUS), mapping.rolesDown(ClickBits.PLUS))
        assertEquals(
            setOf(HandlebarButton.PLUS, HandlebarButton.BUTTON_5),
            mapping.rolesDown(ClickBits.Z or ClickBits.PLUS),
        )
        assertEquals(
            emptySet(),
            mapping.rolesDown(ClickBits.MINUS or ClickBits.NAV_UP or 0x4000),
            "left-puck and unknown bits",
        )
        assertEquals(HandlebarButton.BUTTON_4, mapping.roleOf(ClickBits.A))
        assertNull(mapping.roleOf(ClickBits.MINUS))
    }

    @Test
    fun `a button that sets two bits is one role with a two-bit mask`() {
        val mapping =
            assertNotNull(
                ButtonMapping.DEFAULT.with(
                    HandlebarButton.PLUS,
                    ClickBits.PLUS or ClickBits.PLUS_SHIFT_DOWN,
                ),
            )
        assertEquals(setOf(HandlebarButton.PLUS), mapping.rolesDown(ClickBits.PLUS_SHIFT_DOWN))
        assertEquals(setOf(HandlebarButton.PLUS), mapping.rolesDown(ClickBits.PLUS))
    }

    @Test
    fun `a mapping needs a mask for every role and never lets two roles share a bit`() {
        val good = HandlebarButton.entries.associateWith { 1 shl it.ordinal }
        assertNotNull(ButtonMapping.of(good))
        assertNull(ButtonMapping.of(good - HandlebarButton.PLUS), "a role is missing")
        assertNull(ButtonMapping.of(good + (HandlebarButton.PLUS to 0)), "an empty mask")
        assertNull(
            ButtonMapping.of(good + (HandlebarButton.BUTTON_2 to good.getValue(HandlebarButton.PLUS))),
            "a shared bit",
        )
        assertNull(ButtonMapping.of(good + (HandlebarButton.BUTTON_2 to (0b11 or 0b100))), "a partly shared mask")
        assertNull(ButtonMapping.DEFAULT.with(HandlebarButton.PLUS, ClickBits.A), "A is Button 4's")
        assertNull(ButtonMapping.DEFAULT.with(HandlebarButton.PLUS, 0))
    }

    @Test
    fun `with changes one role and keeps the rest and mappings compare by value`() {
        val swapped = assertNotNull(ButtonMapping.DEFAULT.with(HandlebarButton.PLUS, ClickBits.MINUS))
        assertEquals(ClickBits.MINUS, swapped.maskOf(HandlebarButton.PLUS))
        assertEquals(ClickBits.B, swapped.maskOf(HandlebarButton.BUTTON_2))
        assertNotEquals(ButtonMapping.DEFAULT, swapped)
        assertEquals(ButtonMapping.DEFAULT, ButtonMapping.DEFAULT.with(HandlebarButton.PLUS, ClickBits.PLUS))
        assertEquals(
            ButtonMapping.DEFAULT.hashCode(),
            ButtonMapping.DEFAULT.with(HandlebarButton.PLUS, ClickBits.PLUS).hashCode(),
        )
    }

    @Test
    fun `a mapping survives its text form so the setting can be stored`() {
        assertEquals(
            "PLUS=00001000;BUTTON_2=00000020;BUTTON_3=00000040;BUTTON_4=00000010;BUTTON_5=00000080",
            ButtonMapping.DEFAULT.serialize(),
        )
        assertEquals(ButtonMapping.DEFAULT, ButtonMapping.parse(ButtonMapping.DEFAULT.serialize()))
        val weird = ButtonMapping.of(HandlebarButton.entries.associateWith { 1 shl (it.ordinal * 7) })
        assertNotNull(weird)
        assertEquals(weird, ButtonMapping.parse(weird.serialize()))
        val high = ButtonMapping.of(HandlebarButton.entries.associateWith { 1 shl (27 + it.ordinal) })
        assertEquals(high, ButtonMapping.parse(checkNotNull(high).serialize()), "bit 31 included")
        assertEquals(
            "ButtonMapping(Plus=PLUS, Button 2=B, Button 3=Y, Button 4=A, Button 5=Z)",
            ButtonMapping.DEFAULT.toString(),
        )
    }

    @Test
    fun `damaged text is not a mapping and never throws`() {
        val good = ButtonMapping.DEFAULT.serialize()
        for (bad in listOf(
            "",
            ";;;",
            "PLUS=1",
            good.replace("BUTTON_2", "BUTTON_9"),
            good.replace("00000020", "00000010"),
            good.replace("00000020", "zzzzzzzz"),
            good.replace("00000020", "000000000020"),
            good.replace("=", ":"),
            good + ";PLUS=00000100",
            good.replace("00001000", ""),
            good.replace("00001000", "-0001000"),
        )) {
            assertNull(ButtonMapping.parse(bad), bad)
        }
        val random = Random(8)
        repeat(5_000) {
            val raw = CharArray(random.nextInt(0, 90)) { random.nextInt(32, 127).toChar() }.concatToString()
            ButtonMapping.parse(raw)
        }
        // A truncated good text is also not a mapping.
        for (length in good.indices) assertNull(ButtonMapping.parse(good.take(length)), "cut to $length")
    }
}
