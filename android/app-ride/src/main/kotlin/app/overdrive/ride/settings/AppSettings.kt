package app.overdrive.ride.settings

import app.overdrive.core.physics.PhysicsSettings
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.roundToInt

/** How fast a simulated ride runs, so a 60-minute workout can be previewed in minutes (developer setting). */
enum class DemoSpeed(
    val factor: Int,
    val label: String,
) {
    Normal(1, "1×"),
    Fast(10, "10×"),
    Faster(60, "60×"),
}

/** What the simulated Zwift Click reports on the Devices screen until M4 brings the real one (developer setting). */
enum class SimulatedClick(
    val label: String,
) {
    Connected("Connected"),
    Locked("Locked"),
    NotPaired("Not paired"),
}

/** The overlay's initial mode, consumed by the overlay window in M1-B. */
enum class OverlayStartMode {
    Compact,
    Detailed,
}

/**
 * Everything the rider can set (spec §4.3, §4.4, §4.5, §4.6), plus the developer options of the simulated ride.
 * Values are always inside the ranges of [SettingsRules]: build one with [sanitized] when the source is not trusted.
 */
data class AppSettings(
    val ftp: Int = SettingsRules.DEFAULT_FTP,
    val gearCount: Int = SettingsRules.DEFAULT_GEARS,
    /** Grade added per gear in percent, one decimal. */
    val gradePerGear: Double = SettingsRules.DEFAULT_GRADE_PER_GEAR,
    val massKg: Double = PhysicsSettings.DEFAULT_SYSTEM_MASS_KG,
    val cdA: Double = PhysicsSettings.DEFAULT_CDA,
    val crr: Double = PhysicsSettings.DEFAULT_CRR,
    val overlayMode: OverlayStartMode = OverlayStartMode.Compact,
    /** Backing opacity in percent, 85 to 100 in steps of 5 (design/system.md §5.1). */
    val overlayOpacityPercent: Int = SettingsRules.DEFAULT_OPACITY_PERCENT,
    val overlayBlur: Boolean = false,
    /** Overlay position as a fraction of the screen, null until the rider has dragged it (written by M1-B). */
    val overlayX: Float? = null,
    val overlayY: Float? = null,
    val demoSpeed: DemoSpeed = DemoSpeed.Normal,
    val scriptedDropouts: Boolean = false,
    val simulatedClick: SimulatedClick = SimulatedClick.Connected,
) {
    /** The neutral gear, the one that rides the flat: the middle, with the spare gear on the hard side. */
    val neutralGear: Int get() = (gearCount + 1) / 2

    /** The same settings with every value brought into its valid range and onto its step. */
    fun sanitized(): AppSettings =
        copy(
            ftp = SettingsRules.ftp.normalize(ftp),
            gearCount = SettingsRules.gears.normalize(gearCount),
            gradePerGear = SettingsRules.gradePerGear.normalize(gradePerGear),
            massKg = SettingsRules.mass.normalize(massKg),
            cdA = SettingsRules.cdA.normalize(cdA),
            crr = SettingsRules.crr.normalize(crr),
            overlayOpacityPercent = SettingsRules.opacity.normalize(overlayOpacityPercent),
            overlayX = overlayX?.takeIf { it.isFinite() }?.coerceIn(0f, 1f),
            overlayY = overlayY?.takeIf { it.isFinite() }?.coerceIn(0f, 1f),
        )

    /** Only the values a ride is built from; the rest can change without touching a ride that is ready. */
    fun rideInputs(): List<Any> = listOf(ftp, gearCount, gradePerGear, massKg, cdA, crr, demoSpeed, scriptedDropouts)
}

/** A number setting: its range, step and how it is kept on the grid. */
class IntRule(
    val min: Int,
    val max: Int,
    val step: Int,
) {
    /** [value] limited to the range and snapped to the nearest step from [min]. */
    fun normalize(value: Int): Int =
        (min + ((value.coerceIn(min, max) - min).toDouble() / step).roundToInt() * step).coerceAtMost(max)

    /** One step up or down, kept inside the range. */
    fun stepped(
        value: Int,
        direction: Int,
    ): Int = normalize(value + direction * step)

    fun canStep(
        value: Int,
        direction: Int,
    ): Boolean = value + direction * step in min..max
}

/** The decimal counterpart of [IntRule]; values are held on a grid of [step] so repeated steps never drift. */
class DecimalRule(
    val min: Double,
    val max: Double,
    val step: Double,
) {
    private val places = BigDecimal.valueOf(step).stripTrailingZeros().scale().coerceAtLeast(0)

    /** [value] limited to the range and snapped to the grid, written with only as many decimals as the step has. */
    fun normalize(value: Double): Double {
        if (!value.isFinite()) return min
        val index = ((value.coerceIn(min, max) - min) / step).roundToInt()
        return BigDecimal
            .valueOf(
                min + index * step,
            ).setScale(places, RoundingMode.HALF_UP)
            .toDouble()
            .coerceIn(min, max)
    }

    fun stepped(
        value: Double,
        direction: Int,
    ): Double = normalize(value + direction * step)

    fun canStep(
        value: Double,
        direction: Int,
    ): Boolean = value + direction * step in (min - EPS)..(max + EPS)

    private companion object {
        const val EPS = 1e-9
    }
}

/** The valid values of every setting (design/system.md §6.6). The Settings screen steps through these. */
object SettingsRules {
    const val DEFAULT_FTP: Int = 200
    const val DEFAULT_GEARS: Int = 24
    const val DEFAULT_GRADE_PER_GEAR: Double = 1.0
    const val DEFAULT_OPACITY_PERCENT: Int = 95

    /** FTP in watts, steps of 5 (spec §4.5). */
    val ftp = IntRule(min = 50, max = 600, step = 5)

    /** Number of virtual gears, 10 to 30 (spec §4.4). */
    val gears = IntRule(min = 10, max = 30, step = 1)

    /** Grade per gear in percent, 0.5 to 2.0 in steps of 0.1. */
    val gradePerGear = DecimalRule(min = 0.5, max = 2.0, step = 0.1)

    /** System mass in kg. */
    val mass = DecimalRule(min = 40.0, max = 200.0, step = 1.0)

    /** Drag area in m². */
    val cdA = DecimalRule(min = 0.10, max = 1.00, step = 0.01)

    /** Rolling resistance coefficient. */
    val crr = DecimalRule(min = 0.0010, max = 0.0200, step = 0.0005)

    /** Overlay opacity in percent: 85 to 100 in steps of 5. */
    val opacity = IntRule(min = 85, max = 100, step = 5)
}
