package app.overdrive.uikit.tokens

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverdriveTokensTest {
    @Test
    fun hasSevenZoneColours() {
        assertEquals(7, OverdriveColors.zones.size)
        assertEquals(7, OverdriveColors.zoneNames.size)
    }

    @Test
    fun zoneListIndexIsZoneNumberMinusOne() {
        assertEquals(OverdriveColors.z3, OverdriveColors.zones[2])
    }

    @Test
    fun primaryTextMatchesTokensJson() {
        // color.text.primary in design/tokens.json
        assertEquals(Color(0xFFEEF4FC), OverdriveColors.textPrimary)
    }

    @Test
    fun defaultOverlayOpacityIsWithinRange() {
        val value = OverdriveDimens.overlayOpacityDefault
        assertTrue(value >= OverdriveDimens.overlayOpacityMin)
        assertTrue(value <= OverdriveDimens.overlayOpacityMax)
    }
}
