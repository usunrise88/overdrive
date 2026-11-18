package app.overdrive.ride.workouts

import androidx.compose.runtime.Immutable
import app.overdrive.core.ride.FlatStep
import app.overdrive.core.ride.flatSteps
import app.overdrive.core.ride.totalDurationSeconds
import app.overdrive.core.workout.FreeStep
import app.overdrive.core.workout.LeafStep
import app.overdrive.core.workout.RampStep
import app.overdrive.core.workout.RepeatStep
import app.overdrive.core.workout.SteadyStep
import app.overdrive.core.workout.Workout
import app.overdrive.uikit.UiFormat
import app.overdrive.uikit.Zones
import app.overdrive.uikit.chart.ProfileData
import app.overdrive.uikit.chart.ProfileStep
import kotlin.math.floor

/** One row of the step table on the Ready screen (design/system.md §6.1). */
@Immutable
data class StepRow(
    val name: String,
    val durationSeconds: Int,
    /** "88%", "45–70%" or "Gears". */
    val percentText: String,
    /** "220 W", "113–175 W", or empty for a free-ride step. */
    val wattsText: String,
    /** 1 to 7, or null for a free-ride step. */
    val zone: Int?,
)

/** What the Ready screen shows about a workout, derived from the workout and the rider's FTP. */
@Immutable
data class WorkoutDescription(
    val title: String,
    val description: String?,
    val durationSeconds: Int,
    val hardestWatts: Int?,
    val hardestZone: Int?,
    /** "ERG", "ERG + gears" or "Gears". */
    val mode: String,
    /** "65 min · steady, ramp, intervals" for the list. */
    val meta: String,
    val rows: List<StepRow>,
    val profile: ProfileData,
)

/** Pure descriptions of workouts and their steps for the UI (no Android, tested on the JVM). */
object WorkoutDescriber {
    /** Whole watts of an FTP [fraction], halves rounded up as core's workout player does. */
    fun watts(
        fraction: Double,
        ftp: Int,
        intensityPercent: Int = 100,
    ): Int = floor(fraction * ftp * intensityPercent / 100.0 + 0.5 + ROUNDING_ALLOWANCE).toInt()

    /** Describes [workout] for a rider with the given [ftp]. */
    fun describe(
        workout: Workout,
        ftp: Int,
    ): WorkoutDescription {
        val flat = workout.flatSteps()
        val erg = flat.filter { it.targetFrom != null }
        val hardest = erg.maxOfOrNull { maxOf(it.targetFrom ?: 0.0, it.targetTo ?: 0.0) }
        return WorkoutDescription(
            title = workout.name,
            description = workout.description,
            durationSeconds = flat.totalDurationSeconds(),
            hardestWatts = hardest?.let { watts(it, ftp) },
            hardestZone = hardest?.let { Zones.ofFraction(it) },
            mode =
                when {
                    erg.isEmpty() -> "Gears"
                    erg.size == flat.size -> "ERG"
                    else -> "ERG + gears"
                },
            meta = "${UiFormat.minutes(flat.totalDurationSeconds())} · ${kinds(workout)}",
            rows = rows(workout, ftp),
            profile = profileOf(flat),
        )
    }

    /** The step kinds present, in a fixed order: "steady, ramp, intervals, free". */
    private fun kinds(workout: Workout): String {
        val leaves = workout.steps.flatMap { if (it is RepeatStep) it.steps else listOf(it as LeafStep) }
        return buildList {
            if (leaves.any { it is SteadyStep }) add("steady")
            if (leaves.any { it is RampStep }) add("ramp")
            if (workout.steps.any { it is RepeatStep && it.count > 1 }) add("intervals")
            if (leaves.any { it is FreeStep }) add("free")
        }.joinToString(", ")
    }

    private fun rows(
        workout: Workout,
        ftp: Int,
    ): List<StepRow> =
        buildList {
            for (step in workout.steps) {
                when (step) {
                    is LeafStep -> add(row(step, null, 1, ftp))
                    is RepeatStep -> step.steps.forEach { add(row(it, step.label, step.count, ftp)) }
                }
            }
        }

    private fun row(
        leaf: LeafStep,
        repeatLabel: String?,
        repeats: Int,
        ftp: Int,
    ): StepRow {
        val name = (leaf.label ?: repeatLabel ?: defaultName(leaf)) + if (repeats > 1) " ×$repeats" else ""
        return when (leaf) {
            is SteadyStep ->
                StepRow(name, leaf.duration, "${percent(leaf.target)}%", "${watts(leaf.target, ftp)} W", Zones.ofFraction(leaf.target))
            is RampStep ->
                StepRow(
                    name,
                    leaf.duration,
                    "${UiFormat.range(percent(leaf.from), percent(leaf.to))}%",
                    "${UiFormat.range(watts(leaf.from, ftp), watts(leaf.to, ftp))} W",
                    Zones.ofFraction((leaf.from + leaf.to) / 2.0),
                )
            is FreeStep -> StepRow(name, leaf.duration, "Gears", "", null)
        }
    }

    private fun defaultName(leaf: LeafStep): String =
        when (leaf) {
            is SteadyStep -> "Steady"
            is RampStep -> "Ramp"
            is FreeStep -> "Free ride"
        }

    private fun percent(fraction: Double): Int = floor(fraction * PERCENT + 0.5 + ROUNDING_ALLOWANCE).toInt()

    /** The geometry of the profile chart: one instance per workout, so the chart caches its paths. */
    fun profileOf(steps: List<FlatStep>): ProfileData =
        ProfileData(
            steps.map { ProfileStep(it.startSeconds, it.durationSeconds, it.targetFrom?.toFloat(), it.targetTo?.toFloat()) },
        )

    /**
     * What a step is, for the "Next" line of the overlay and the ride screen: "Recover · 5:00 · 125 W", a ramp as
     * "Warm-up · 10:00 · 113–175 W", a free-ride step as "Free ride · 5:00". Watts include the intensity setting.
     */
    fun nextLine(
        step: FlatStep,
        ftp: Int,
        intensityPercent: Int,
    ): String {
        val from = step.targetFrom ?: return "${step.label} · ${UiFormat.elapsed(step.durationSeconds)}"
        val to = step.targetTo ?: from
        val watts = UiFormat.range(watts(from, ftp, intensityPercent), watts(to, ftp, intensityPercent))
        return "${step.label} · ${UiFormat.elapsed(step.durationSeconds)} · $watts W"
    }

    private const val PERCENT = 100.0
    private const val ROUNDING_ALLOWANCE = 1e-9
}
