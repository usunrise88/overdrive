package app.overdrive.uikit

import org.junit.Assert.assertEquals
import org.junit.Test

/** How numbers are written (design/system.md §3): the same strings on every screen. */
class UiFormatTest {
    @Test
    fun missingValuesAreAnEmDashNeverZeroOrBlank() {
        assertEquals("—", UiFormat.count(null))
        assertEquals("—", UiFormat.percent(null))
        assertEquals("—", UiFormat.elapsed(null))
        assertEquals("—", UiFormat.speed(null))
        assertEquals("—", UiFormat.distanceKm(null))
        assertEquals("0", UiFormat.count(0))
    }

    @Test
    fun elapsedTimeIsMinutesSecondsBelowAnHourThenHoursMinutesSeconds() {
        assertEquals("0:00", UiFormat.elapsed(0))
        assertEquals("0:09", UiFormat.elapsed(9))
        assertEquals("2:43", UiFormat.elapsed(163))
        assertEquals("32:17", UiFormat.elapsed(1937))
        assertEquals("59:59", UiFormat.elapsed(3599))
        assertEquals("1:00:00", UiFormat.elapsed(3600))
        assertEquals("1:04:09", UiFormat.elapsed(3849))
        assertEquals("12:00:01", UiFormat.elapsed(43_201))
        assertEquals("0:00", UiFormat.elapsed(-5))
    }

    @Test
    fun speedHasOneDecimalAndDistanceTwoBelowAHundredKilometres() {
        assertEquals("34.6", UiFormat.speed(34.6))
        assertEquals("0.0", UiFormat.speed(0.0))
        assertEquals("31.9", UiFormat.speed(31.94))
        assertEquals("18.42", UiFormat.distanceKm(18_420.0))
        assertEquals("0.00", UiFormat.distanceKm(0.0))
        assertEquals("99.99", UiFormat.distanceKm(99_990.0))
        assertEquals("100.0", UiFormat.distanceKm(100_000.0))
        assertEquals("123.5", UiFormat.distanceKm(123_456.0))
    }

    @Test
    fun numbersUseADotWhateverTheDeviceLocale() {
        val before = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("34.6", UiFormat.speed(34.6))
            assertEquals("0.32", UiFormat.decimal(0.32, 2))
        } finally {
            java.util.Locale.setDefault(before)
        }
    }

    @Test
    fun negativeNumbersUseTheTrueMinus() {
        assertEquals("−10", UiFormat.signed(-10))
        assertEquals("−5%", UiFormat.percent(-5))
        assertEquals("100%", UiFormat.percent(100))
    }

    @Test
    fun rangesUseAnEnDashAndCollapseWhenEqual() {
        assertEquals("113–175", UiFormat.range(113, 175))
        assertEquals("250", UiFormat.range(250, 250))
    }

    @Test
    fun minutesRoundToTheNearest() {
        assertEquals("65 min", UiFormat.minutes(3900))
        assertEquals("1 min", UiFormat.minutes(60))
        assertEquals("28 min", UiFormat.minutes(1_650)) // 27.5
    }

    @Test
    fun zoneColourPastBlendIsOpaqueSrgbMix() {
        val surface = androidx.compose.ui.graphics.Color(0xFF000000)
        val zone = androidx.compose.ui.graphics.Color(0xFF646464)
        val blended = Zones.blend(surface, zone, 0.5f)
        assertEquals(1f, blended.alpha, 0f)
        assertEquals(0.5f * 0x64 / 255f, blended.red, 1e-5f)
    }
}
