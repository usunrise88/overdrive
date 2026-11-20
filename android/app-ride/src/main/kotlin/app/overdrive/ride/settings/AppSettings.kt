package app.overdrive.ride.settings

import app.overdrive.core.control.ButtonMapping
import app.overdrive.core.physics.PhysicsSettings
import app.overdrive.core.profile.RiderWeight
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

/**
 * Where a ride's trainer and strap come from (developer setting, ADR 0010). **Bluetooth is the default and a ride never
 * falls back to the simulation by itself**: with no trainer paired the Ready screen says so, because a recorded ride of
 * made-up numbers would be worse than no ride.
 */
enum class DeviceSource(
    val label: String,
) {
    Bluetooth("Bluetooth"),
    Simulated("Simulated"),
}

/** What the simulated Zwift Click reports on the Devices screen with simulated devices (developer setting). */
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
    /**
     * Air density in kg/m³. **Never stored and never set on the tablet**: it is the engine's standard value unless the rider's
     * profile on the service says otherwise (see [withProfile], ADR 0021).
     */
    val airDensity: Double = PhysicsSettings.STANDARD_AIR_DENSITY,
    /** Share of the measured power that reaches the wheel. Like [airDensity]: the service's profile or the engine's 1.0. */
    val drivetrainEfficiency: Double = DEFAULT_DRIVETRAIN_EFFICIENCY,
    val overlayMode: OverlayStartMode = OverlayStartMode.Compact,
    /** Backing opacity in percent, 85 to 100 in steps of 5 (design/system.md §5.1). */
    val overlayOpacityPercent: Int = SettingsRules.DEFAULT_OPACITY_PERCENT,
    val overlayBlur: Boolean = false,
    /**
     * The overlay expands to its detailed mode 10 s before a step change and returns to the rider's mode 10 s after it (spec §11.3,
     * docs/adr/0034). On by default; no sound either way.
     */
    val overlayExpandsAtSteps: Boolean = true,
    /**
     * The live coach (spec §12.1, docs/adr/0039): during a workout that asks for it, the service speaks short cues in the rider's earphones and
     * the overlay shows them. It needs the connection, and it never changes a target, the intensity or the workout. **On by default.** Read when a ride
     * starts: it is not something a ride is built from, so changing it rebuilds nothing.
     */
    val liveCoach: Boolean = true,
    /** Overlay position as a fraction of the screen, null until the rider has dragged it (written by M1-B). */
    val overlayX: Float? = null,
    val overlayY: Float? = null,
    val demoSpeed: DemoSpeed = DemoSpeed.Normal,
    val scriptedDropouts: Boolean = false,
    val simulatedClick: SimulatedClick = SimulatedClick.Connected,
    /** Whether the ride uses the real devices or the simulated ones (developer setting). */
    val deviceSource: DeviceSource = DeviceSource.Bluetooth,
    /**
     * Whether every Bluetooth connection is written to a text file on the tablet, for fixing the codecs without a bench
     * machine (developer setting, **on** in the early builds; ADR 0010). The files hold the rider's heart rate.
     */
    val recordBleSessions: Boolean = true,
    /**
     * Which of the Click's five buttons is which of the five roles (spec §4.7: "The mapping is a setting"), as learned on
     * the Devices screen. Always a valid mapping, never empty and never overlapping; a stored value that is not one reads as
     * [ButtonMapping.DEFAULT] (see [SettingsRepository]).
     */
    val buttonMapping: ButtonMapping = ButtonMapping.DEFAULT,
    /** What the owner flips at the bench without a rebuild: the Click's connection options and the gesture timings. */
    val click: ClickSettings = ClickSettings(),
    /** The web service's address (spec §4.9): stored and **not used** in Phase 1. Empty when none, otherwise a valid address. */
    val syncUrl: String = "",
    /** The web service's token (spec §4.9): stored and **not used** in Phase 1. A secret: it prints as bullets. */
    val syncToken: SecretText = SecretText(),
    /**
     * How much of a route's grade the rider feels, in percent (spec §11.4): 50 means a 10% road is felt as 5%, 0 is the flat. The
     * grade of the road scaled by it is what the trainer simulates and what the virtual speed uses.
     */
    val routeDifficultyPercent: Int = SettingsRules.DEFAULT_ROUTE_DIFFICULTY_PERCENT,
    /**
     * The engine's tuning numbers: resuming after a pause, the cadence guard and the heart-rate hold (docs/adr/0034). Always inside
     * their rules (see [RideTuning.sanitized]); they reach the engine through [app.overdrive.ride.host.toRideSettings].
     */
    val tuning: RideTuning = RideTuning(),
    /**
     * The bike, kit and bottles in kilograms **from the rider's profile on the service** (spec §12.5, docs/adr/0034). **Never stored on the
     * tablet** (like [airDensity]): null without a profile or while the profile has none. With [riderWeight] it makes the ride's mass.
     */
    val bikeWeightKg: Double? = null,
    /** The rider's 7-day average weight from the profile: read-only, never stored, null without one. */
    val riderWeight: RiderWeight? = null,
) {
    /** The neutral gear, the one that rides the flat: the middle, with the spare gear on the hard side. */
    val neutralGear: Int get() = (gearCount + 1) / 2

    /** The same settings with every value brought into its valid range and onto its step. */
    fun sanitized(): AppSettings =
        copy(
            click = click.sanitized(),
            tuning = tuning.sanitized(),
            syncUrl = SyncRules.cleanUrl(syncUrl),
            syncToken = SecretText(SyncRules.cleanToken(syncToken.value)),
            ftp = SettingsRules.ftp.normalize(ftp),
            gearCount = SettingsRules.gears.normalize(gearCount),
            gradePerGear = SettingsRules.gradePerGear.normalize(gradePerGear),
            massKg = SettingsRules.mass.normalize(massKg),
            cdA = SettingsRules.cdA.normalize(cdA),
            crr = SettingsRules.crr.normalize(crr),
            routeDifficultyPercent = SettingsRules.routeDifficulty.normalize(routeDifficultyPercent),
            overlayOpacityPercent = SettingsRules.opacity.normalize(overlayOpacityPercent),
            overlayX = overlayX?.takeIf { it.isFinite() }?.coerceIn(0f, 1f),
            overlayY = overlayY?.takeIf { it.isFinite() }?.coerceIn(0f, 1f),
        )

    /** Only the values a ride is built from; the rest can change without touching a ride that is ready. */
    fun rideInputs(): List<Any> =
        listOf(
            ftp,
            gearCount,
            gradePerGear,
            massKg,
            cdA,
            crr,
            airDensity,
            drivetrainEfficiency,
            demoSpeed,
            scriptedDropouts,
            deviceSource,
            routeDifficultyPercent,
            tuning,
        )

    companion object {
        /** The engine's: the measured power is taken as the power at the wheel. */
        const val DEFAULT_DRIVETRAIN_EFFICIENCY: Double = 1.0
    }
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

    /** Spec §11.4: steep roads stay rideable on one cog. Equals `RouteGrade.DEFAULT_DIFFICULTY_PERCENT`. */
    const val DEFAULT_ROUTE_DIFFICULTY_PERCENT: Int = 50

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

    /** Route difficulty in percent of the road's grade, 0 to 100 in steps of 10 (spec §11.4). */
    val routeDifficulty = IntRule(min = 0, max = 100, step = 10)

    /** The bike's weight in kg, 3 to 30 in steps of 0.5 (the profile itself takes anything above 0 up to 100). */
    val bikeWeight = DecimalRule(min = 3.0, max = 30.0, step = 0.5)

    /** What the first press of the stepper sets when the bike's weight was not set: a typical road bike with bottles. */
    const val DEFAULT_BIKE_WEIGHT_KG: Double = 9.0
}
