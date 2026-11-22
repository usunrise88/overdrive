package app.overdrive.core.profile

import app.overdrive.core.physics.PhysicsSettings
import app.overdrive.core.plan.PlanDates
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The rider's profile as `GET /v1/profile` answers it (spec §8.3, ADR 0018, `contracts/schema/profile.schema.json`):
 * the FTP, W′, the power zones, the weekly availability, the goals and the physics constants the ride engine uses.
 *
 * [ftp] is in watts, or null while the owner has not set one (a tablet then keeps its own setting). [updatedAt] is the
 * UTC instant of the last change (`2026-11-18T09:30:00Z`), null while this is still the defaults. Every key is always
 * written, null included, as the schema requires.
 *
 * The model is as strict as the schema: a value out of range, an unknown key or a missing one is an
 * [IllegalArgumentException] when it is read.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
public data class Profile(
    val ftp: Int?,
    val zones: PowerZones,
    val weeklyAvailability: WeeklyAvailability,
    val goals: List<String>,
    val physics: ProfilePhysics,
    val updatedAt: String?,
    /**
     * W′ in kilojoules (1 to 100) for the W′ balance, or null for the service's default of 20 kJ; critical power is the
     * FTP. Last, with a default, so code that builds a profile without it still compiles; on the wire the key is always
     * present (the decoder insists).
     */
    val wPrimeKj: Double? = null,
    /** Maximum heart rate in bpm, or null when not set (spec §6.7, ADR 0023). Optional on the wire. */
    val maxHeartRate: Int? = null,
    /** Threshold heart rate in bpm, or null when not set. */
    val thresholdHeartRate: Int? = null,
    /** The highest heart rate in the stored rides; read-only, null while they say nothing. */
    val estimatedMaxHeartRate: Int? = null,
    /** 95% of the best 20-minute average heart rate in the stored rides; read-only, null when not enough. */
    val estimatedThresholdHeartRate: Int? = null,
    /**
     * The bike, kit and bottles in kilograms, or null when not set (spec §12.5, docs/adr/0034). Setting it is what puts the rider's
     * weight into the physics: see [systemMassKgFromWeights]. Written to the wire only when it has a value (an absent key is "not set").
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val bikeWeightKg: Double? = null,
    /**
     * The rider's weight as the service has it from Health Connect, read-only and derived (spec §12.5): the 7-day average. Null when
     * there is none; written to the wire only when there is one.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val riderWeight: RiderWeight? = null,
    /**
     * Which table of watts per kilogram the power skills' levels are read against (spec §6.3, docs/adr/0042): null, or absent on the wire,
     * is the men's. Written to the wire only when the owner has chosen one.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val powerProfileTable: PowerProfileTable? = null,
) {
    init {
        require(ftp == null || ftp in MIN_FTP..MAX_FTP) { "FTP must be within $MIN_FTP..$MAX_FTP W, got $ftp" }
        require(wPrimeKj == null || wPrimeKj in MIN_W_PRIME_KJ..MAX_W_PRIME_KJ) {
            "W′ must be within $MIN_W_PRIME_KJ..$MAX_W_PRIME_KJ kJ, got $wPrimeKj"
        }
        require(maxHeartRate == null || maxHeartRate in MIN_MAX_HR..MAX_MAX_HR) {
            "Maximum heart rate must be within $MIN_MAX_HR..$MAX_MAX_HR bpm, got $maxHeartRate"
        }
        require(thresholdHeartRate == null || thresholdHeartRate in MIN_THRESHOLD_HR..MAX_THRESHOLD_HR) {
            "Threshold heart rate must be within $MIN_THRESHOLD_HR..$MAX_THRESHOLD_HR bpm, got $thresholdHeartRate"
        }
        require(estimatedMaxHeartRate == null || estimatedMaxHeartRate in MIN_MAX_HR..MAX_MAX_HR) {
            "Estimated maximum heart rate must be within $MIN_MAX_HR..$MAX_MAX_HR bpm, got $estimatedMaxHeartRate"
        }
        require(
            estimatedThresholdHeartRate == null || estimatedThresholdHeartRate in MIN_THRESHOLD_HR..MAX_THRESHOLD_HR,
        ) {
            "Estimated threshold heart rate must be within $MIN_THRESHOLD_HR..$MAX_THRESHOLD_HR bpm, " +
                "got $estimatedThresholdHeartRate"
        }
        require(bikeWeightKg == null || (bikeWeightKg > 0.0 && bikeWeightKg <= MAX_BIKE_WEIGHT_KG)) {
            "The bike weight must be within (0, $MAX_BIKE_WEIGHT_KG] kg, got $bikeWeightKg"
        }
        require(maxHeartRate == null || thresholdHeartRate == null || thresholdHeartRate < maxHeartRate) {
            "Threshold heart rate $thresholdHeartRate must be below the maximum $maxHeartRate"
        }
        require(goals.size <= MAX_GOALS) { "At most $MAX_GOALS goals, got ${goals.size}" }
        for (goal in goals) {
            require(goal.isNotBlank()) { "A goal must not be blank" }
            require(goal.length <= MAX_GOAL_LENGTH) { "A goal has at most $MAX_GOAL_LENGTH characters" }
        }
        require(updatedAt == null || INSTANT.matches(updatedAt)) { "updatedAt is not a UTC instant: '$updatedAt'" }
    }

    /**
     * The system mass the weights give: the rider's 7-day average weight plus the bike's, or null unless **both** are there. A profile
     * that has no weighing this week, or no bike weight, leaves the mass to [ProfilePhysics.systemMassKg] (docs/adr/0034).
     */
    public fun systemMassKgFromWeights(): Double? {
        val rider = riderWeight ?: return null
        val bike = bikeWeightKg ?: return null
        val total = rider.kg + bike
        return if (total <= ProfilePhysics.MAX_MASS_KG) total else null
    }

    public companion object {
        public const val MIN_FTP: Int = 30
        public const val MAX_FTP: Int = 2000
        public const val MAX_BIKE_WEIGHT_KG: Double = 100.0
        public const val MAX_RIDER_WEIGHT_KG: Double = 500.0
        public const val MIN_W_PRIME_KJ: Double = 1.0
        public const val MAX_W_PRIME_KJ: Double = 100.0
        public const val MAX_GOALS: Int = 10
        public const val MAX_GOAL_LENGTH: Int = 200
        public const val MIN_MAX_HR: Int = 100
        public const val MAX_MAX_HR: Int = 230
        public const val MIN_THRESHOLD_HR: Int = 80
        public const val MAX_THRESHOLD_HR: Int = 220

        private val INSTANT = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,3})?Z$")

        /** The profile of a rider who has set nothing: no FTP, Coggan's zones, no availability, no goals, default physics. */
        public fun default(): Profile =
            Profile(
                ftp = null,
                zones = PowerZones.COGGAN,
                weeklyAvailability = WeeklyAvailability.UNKNOWN,
                goals = emptyList(),
                physics = ProfilePhysics.DEFAULT,
                updatedAt = null,
            )
    }
}

/** The two tables of watts per kilogram the power skills' levels can be read against (spec §6.3, docs/adr/0042); [MEN] is the default. */
@Serializable
public enum class PowerProfileTable {
    @SerialName("men")
    MEN,

    @SerialName("women")
    WOMEN,
}

/**
 * The rider's weight as the service has it (spec §12.5, docs/adr/0034): the mean of the daily weights of the last seven local days,
 * [kg] to one decimal, with the local [date] of the newest weighing in it and the number of [days] of the seven that had one.
 */
@Serializable
public data class RiderWeight(
    val kg: Double,
    /** `YYYY-MM-DD`. */
    val date: String,
    val days: Int,
) {
    init {
        require(kg > 0.0 && kg <= Profile.MAX_RIDER_WEIGHT_KG) {
            "The rider's weight must be within (0, ${Profile.MAX_RIDER_WEIGHT_KG}] kg, got $kg"
        }
        require(days in 1..WEIGHT_WINDOW_DAYS) { "The days with a weighing are 1..$WEIGHT_WINDOW_DAYS, got $days" }
        require(PlanDates.epochDay(date) != null) { "Not a calendar date: '$date'" }
    }

    public companion object {
        /** The window of the average, in days (spec §12.5). */
        public const val WEIGHT_WINDOW_DAYS: Int = 7
    }
}

/** One power zone in whole watts; [maxWatts] is null for the last zone, which has no upper bound. */
@Serializable
public data class PowerZoneRange(
    /** 1 to 7. */
    val zone: Int,
    val minWatts: Int,
    val maxWatts: Int?,
)

/**
 * Power zones as shares of FTP: [upperPercentOfFtp] are the inclusive upper bounds of zones 1 to 6 in whole percent,
 * strictly increasing; zone 7 is everything above the sixth. Same reading as the ride app's `PowerZone`: with Coggan's
 * bounds ([COGGAN]) [zoneOf] gives the same zone as `PowerZone.of`. The watts of a zone are derived from the FTP
 * here, never stored.
 */
@Serializable
public data class PowerZones(
    val upperPercentOfFtp: List<Int>,
) {
    init {
        require(upperPercentOfFtp.size == BOUNDS) { "Zones need $BOUNDS upper bounds, got ${upperPercentOfFtp.size}" }
        for (bound in upperPercentOfFtp) {
            require(
                bound in MIN_PERCENT..MAX_PERCENT,
            ) { "A zone bound is within $MIN_PERCENT..$MAX_PERCENT %, got $bound" }
        }
        for (i in 1 until upperPercentOfFtp.size) {
            require(upperPercentOfFtp[i] > upperPercentOfFtp[i - 1]) {
                "Zone bounds must strictly increase, got ${upperPercentOfFtp[i]} after ${upperPercentOfFtp[i - 1]}"
            }
        }
    }

    /**
     * The seven zones in watts for [ftp]. Power is measured in whole watts, so a bound of 55% of 250 W (137.5) is 137 W:
     * zone 1 is 0 to 137 W and zone 2 starts at 138 W.
     */
    public fun ranges(ftp: Int): List<PowerZoneRange> {
        require(ftp > 0) { "FTP must be positive, got $ftp" }
        val ranges = ArrayList<PowerZoneRange>()
        var min = 0
        for ((index, percent) in upperPercentOfFtp.withIndex()) {
            val max = (percent.toLong() * ftp / PERCENT).toInt()
            ranges += PowerZoneRange(zone = index + 1, minWatts = min, maxWatts = max)
            min = max + 1
        }
        ranges += PowerZoneRange(zone = BOUNDS + 1, minWatts = min, maxWatts = null)
        return ranges
    }

    /** The zone, 1 to 7, of [powerWatts] for a rider with [ftp]; negative power counts as zero. */
    public fun zoneOf(
        powerWatts: Int,
        ftp: Int,
    ): Int {
        require(ftp > 0) { "FTP must be positive, got $ftp" }
        val scaled = maxOf(powerWatts, 0).toLong() * PERCENT
        for ((index, percent) in upperPercentOfFtp.withIndex()) {
            if (scaled <= percent.toLong() * ftp) return index + 1
        }
        return BOUNDS + 1
    }

    public companion object {
        private const val BOUNDS = 6
        private const val MIN_PERCENT = 1
        private const val MAX_PERCENT = 300
        private const val PERCENT = 100L

        /** Coggan's zones, as the ride app's `PowerZone`: 55, 75, 90, 105, 120 and 150 % of FTP. */
        public val COGGAN: PowerZones = PowerZones(listOf(55, 75, 90, 105, 120, 150))
    }
}

/** The weekdays of [WeeklyAvailability], Monday first. */
public enum class Weekday { MON, TUE, WED, THU, FRI, SAT, SUN }

/** Minutes the rider can train on each weekday: 0 is a rest day, null is not known. */
@Serializable
public data class WeeklyAvailability(
    val mon: Int?,
    val tue: Int?,
    val wed: Int?,
    val thu: Int?,
    val fri: Int?,
    val sat: Int?,
    val sun: Int?,
) {
    init {
        for (day in Weekday.entries) {
            val minutes = this[day]
            require(minutes == null || minutes in 0..MAX_MINUTES) {
                "Availability on ${day.name.lowercase()} is within 0..$MAX_MINUTES minutes, got $minutes"
            }
        }
    }

    public operator fun get(day: Weekday): Int? =
        when (day) {
            Weekday.MON -> mon
            Weekday.TUE -> tue
            Weekday.WED -> wed
            Weekday.THU -> thu
            Weekday.FRI -> fri
            Weekday.SAT -> sat
            Weekday.SUN -> sun
        }

    /** A copy with [day] set (null makes it unknown again). */
    public fun with(
        day: Weekday,
        minutes: Int?,
    ): WeeklyAvailability =
        when (day) {
            Weekday.MON -> copy(mon = minutes)
            Weekday.TUE -> copy(tue = minutes)
            Weekday.WED -> copy(wed = minutes)
            Weekday.THU -> copy(thu = minutes)
            Weekday.FRI -> copy(fri = minutes)
            Weekday.SAT -> copy(sat = minutes)
            Weekday.SUN -> copy(sun = minutes)
        }

    public companion object {
        public const val MAX_MINUTES: Int = 1440

        /** No weekday known. */
        public val UNKNOWN: WeeklyAvailability = WeeklyAvailability(null, null, null, null, null, null, null)
    }
}

/**
 * The constants of the virtual-speed model, under the names and with the defaults of the ride engine's
 * [PhysicsSettings]; the profile's bounds are tighter than the engine's, which only asks for positive values.
 */
@Serializable
public data class ProfilePhysics(
    /** Rider, bike and kit together, in kilograms. */
    val systemMassKg: Double,
    /** Drag area in square metres. */
    val cdA: Double,
    /** Rolling resistance coefficient. */
    val crr: Double,
    /** Air density in kg/m³. */
    val airDensity: Double,
    /** Share of the measured power that reaches the wheel, in `(0, 1]`. */
    val drivetrainEfficiency: Double,
) {
    init {
        require(systemMassKg > 0.0 && systemMassKg <= MAX_MASS_KG) {
            "System mass is within (0, $MAX_MASS_KG] kg, got $systemMassKg"
        }
        require(cdA > 0.0 && cdA <= MAX_CDA) { "CdA is within (0, $MAX_CDA] m², got $cdA" }
        require(crr >= 0.0 && crr <= MAX_CRR) { "Crr is within [0, $MAX_CRR], got $crr" }
        require(airDensity > 0.0 && airDensity <= MAX_AIR_DENSITY) {
            "Air density is within (0, $MAX_AIR_DENSITY], got $airDensity"
        }
        require(drivetrainEfficiency > 0.0 && drivetrainEfficiency <= 1.0) {
            "Drivetrain efficiency is within (0, 1], got $drivetrainEfficiency"
        }
    }

    /** The engine's settings for these constants. */
    public fun toPhysicsSettings(): PhysicsSettings =
        PhysicsSettings(
            systemMassKg = systemMassKg,
            cdA = cdA,
            crr = crr,
            airDensity = airDensity,
            drivetrainEfficiency = drivetrainEfficiency,
        )

    public companion object {
        public const val MAX_MASS_KG: Double = 500.0
        public const val MAX_CDA: Double = 2.0
        public const val MAX_CRR: Double = 0.1
        public const val MAX_AIR_DENSITY: Double = 2.0

        /** The engine's defaults: what a rider who has set nothing rides with. */
        public val DEFAULT: ProfilePhysics =
            ProfilePhysics(
                systemMassKg = PhysicsSettings.DEFAULT_SYSTEM_MASS_KG,
                cdA = PhysicsSettings.DEFAULT_CDA,
                crr = PhysicsSettings.DEFAULT_CRR,
                airDensity = PhysicsSettings.STANDARD_AIR_DENSITY,
                drivetrainEfficiency = 1.0,
            )
    }
}
