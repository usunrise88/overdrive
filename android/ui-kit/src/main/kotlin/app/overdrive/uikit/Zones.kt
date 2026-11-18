package app.overdrive.uikit

import androidx.compose.ui.graphics.Color
import app.overdrive.uikit.tokens.OverdriveColors

/**
 * Power-zone helpers of the design system (design/system.md §2). Zones are plain numbers 1 to 7 here so
 * `ui-kit` does not depend on `core`; the app maps `PowerZone.number` into them.
 */
object Zones {
    const val ZONE_COUNT: Int = 7
    private const val PERCENT = 100.0
    private const val EPSILON = 1e-9
    private val UPPER_BOUNDS = doubleArrayOf(55.0, 75.0, 90.0, 105.0, 120.0, 150.0)

    /** Zone colour for [zone] (1 to 7). Anything else is the neutral secondary text colour. */
    fun color(zone: Int?): Color = if (zone != null && zone in 1..ZONE_COUNT) OverdriveColors.zones[zone - 1] else OverdriveColors.textSecondary

    /** Zone name for [zone] (1 to 7), or an empty string. */
    fun name(zone: Int?): String = if (zone != null && zone in 1..ZONE_COUNT) OverdriveColors.zoneNames[zone - 1] else ""

    /**
     * The zone of a workout target given as a fraction of FTP (1.0 is FTP), by Coggan's upper bounds read as
     * inclusive, exactly like core's `PowerZone.of`: 0.55 is Z1, anything above it up to 0.75 is Z2.
     */
    fun ofFraction(fraction: Double): Int {
        val pct = fraction * PERCENT
        for (zone in 1 until ZONE_COUNT) {
            if (pct <= UPPER_BOUNDS[zone - 1] + EPSILON) return zone
        }
        return ZONE_COUNT
    }

    /** The upper bounds of Z1 to Z6 as a fraction of FTP, in order, for splitting a ramp at zone boundaries. */
    val upperBoundFractions: List<Double> = UPPER_BOUNDS.map { it / PERCENT }

    /** [colour] mixed into [surface] by [amount] in sRGB, opaque: the "past" look of the workout profile. */
    fun blend(
        surface: Color,
        colour: Color,
        amount: Float = OverdriveColors.zonePastAlpha,
    ): Color =
        Color(
            red = surface.red + (colour.red - surface.red) * amount,
            green = surface.green + (colour.green - surface.green) * amount,
            blue = surface.blue + (colour.blue - surface.blue) * amount,
            alpha = 1f,
        )

}
