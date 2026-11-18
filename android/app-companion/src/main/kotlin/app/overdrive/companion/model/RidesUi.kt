package app.overdrive.companion.model

import androidx.compose.runtime.Immutable
import app.overdrive.core.metrics.LapKind
import app.overdrive.core.metrics.RideDetailResponse
import app.overdrive.core.metrics.RideItem
import app.overdrive.core.metrics.RideKind
import app.overdrive.core.remote.ResourceState
import app.overdrive.core.remote.RidesView
import app.overdrive.core.ride.FlatStep
import app.overdrive.core.ride.flatSteps
import app.overdrive.uikit.UiFormat
import app.overdrive.uikit.Zones
import app.overdrive.uikit.chart.ChartMath
import app.overdrive.uikit.chart.ProfileStep
import app.overdrive.uikit.chart.ReadoutEntry
import app.overdrive.uikit.chart.ReadoutKey
import app.overdrive.uikit.chart.RideChartSpec
import app.overdrive.uikit.chart.ValuesRow
import app.overdrive.uikit.tokens.OverdriveColors
import kotlin.math.roundToInt

/** Where a row sits in its month's card: the card's corners and its dividers follow (design/companion.md §4.6). */
enum class CardPosition { Only, First, Middle, Last }

/** One row of the Rides list (design/system.md §7.5): a ride from the tablet or an activity from the watch. */
@Immutable
data class RideRowUi(
    val id: String,
    val kind: RideKind,
    val title: String,
    val subtitle: String,
    /** A watch activity: a 16 dp `heart-pulse` and the word "Watch" before the subtitle, never colour. */
    val watch: Boolean,
    val load: String,
    /** "196 W" for a ride, "142 bpm" for an activity. */
    val caption: String,
    val position: CardPosition,
)

/** An item of the lazy list: a month's header (sticky) or one of its rows. */
sealed interface RidesEntry {
    val key: String

    data class Month(
        override val key: String,
        val month: String,
        val total: String,
    ) : RidesEntry

    data class Row(
        val row: RideRowUi,
    ) : RidesEntry {
        override val key: String get() = "${row.kind.name.lowercase()}-${row.id}"
    }
}

@Immutable
class RidesContent(
    val entries: List<RidesEntry>,
    val hasMore: Boolean,
    val loadingMore: Boolean,
    val moreProblem: ProblemUi?,
)

/** The Rides list made from the pages fetched so far (design/companion.md §4.6): grouped by month, newest first. */
object RidesUi {
    fun of(
        view: RidesView,
        words: Words,
        host: String,
    ): ScreenUi<RidesContent> =
        screenOf(
            view.first,
            words,
            host,
            loading = "Loading your rides…",
            empty = "No rides yet" to EMPTY_TEXT,
            isEmpty = { view.list.items.isEmpty() },
        ) { content(view, words, host) }

    fun content(
        view: RidesView,
        words: Words,
        host: String,
    ): RidesContent {
        val groups = view.list.items.groupBy { it.date.take(MONTH_CHARS) }
        val entries = ArrayList<RidesEntry>()
        for ((month, items) in groups) {
            val total = items.sumOf { it.load ?: 0.0 }
            entries += RidesEntry.Month(month, words.month("$month-01"), "Load ${words.load(total)}")
            items.forEachIndexed { index, item ->
                val position =
                    when {
                        items.size == 1 -> CardPosition.Only
                        index == 0 -> CardPosition.First
                        index == items.lastIndex -> CardPosition.Last
                        else -> CardPosition.Middle
                    }
                entries += RidesEntry.Row(row(item, position, words))
            }
        }
        return RidesContent(
            entries,
            view.list.hasMore,
            view.list.loadingMore,
            view.list.moreProblem?.let { ProblemWords.of(it, host, null) },
        )
    }

    private fun row(
        item: RideItem,
        position: CardPosition,
        words: Words,
    ): RideRowUi {
        val whenText = "${words.dayOfMonth(item.date)} · ${words.duration(item.durationSeconds)}"
        val watch = item.kind == RideKind.ACTIVITY
        return RideRowUi(
            id = item.id,
            kind = item.kind,
            title = item.name,
            subtitle = if (watch) "Watch · $whenText" else whenText,
            watch = watch,
            load = words.load(item.load),
            caption =
                if (watch) {
                    item.averageHeartRate?.let { "${it.roundToInt()} bpm" } ?: UiFormat.EMPTY
                } else {
                    item.averagePower?.let { "${it.roundToInt()} W" } ?: UiFormat.EMPTY
                },
            position = position,
        )
    }

    private const val MONTH_CHARS = 7
    private const val EMPTY_TEXT =
        "Rides from the tablet and activities from the watch appear here after they reach the service."
}

/** One lap of the table (design/companion.md §4.7). */
@Immutable
data class LapUi(
    val index: Int,
    val name: String,
    val time: String,
    val watts: String,
    /** "of 288", "ramp", or null for a free step. */
    val target: String?,
    val heart: String,
)

/** The "Planned workout" row card of a ride that followed one. */
@Immutable
data class PlanSummaryUi(
    val title: String,
    val efforts: String?,
)

@Immutable
class RideDetailContent(
    val name: String,
    val whenLine: String,
    val tiles: List<TileUi>,
    val plan: PlanSummaryUi?,
    /** Null when the ride has no samples (a few seconds): the card says so instead of drawing a chart. */
    val chart: RideChartUi?,
    val zones: List<Int>,
    val laps: List<LapUi>?,
)

/** The ride detail made from `GET /v1/rides/{id}?series=true` (design/companion.md §4.7, §6.8). Pure, like the other screens' models. */
object RideDetailUi {
    fun of(
        state: ResourceState<RideDetailResponse>,
        words: Words,
        host: String,
    ): ScreenUi<RideDetailContent> =
        screenOf(state, words, host, loading = "Loading the ride…", empty = null) { content(it, words) }

    fun content(
        ride: RideDetailResponse,
        words: Words,
    ): RideDetailContent {
        val s = ride.summary
        val tiles =
            listOf(
                TileUi("Duration", words.duration(s.durationSeconds), null),
                TileUi("Normalised", s.normalizedPower?.roundToInt()?.toString(), null, "W"),
                TileUi("Load", s.trainingStressScore?.roundToInt()?.toString(), null),
                TileUi("Average", s.averagePower?.roundToInt()?.toString(), null, "W"),
                TileUi("Intensity", s.intensityFactor?.let { UiFormat.decimal(it, 2) }, null),
                TileUi("Work", s.workKilojoules.roundToInt().toString(), null, "kJ"),
                TileUi("Avg heart", s.averageHeartRate?.roundToInt()?.toString(), null, "bpm"),
                TileUi("Max heart", s.maxHeartRate?.toString(), null, "bpm"),
                TileUi("Moving", words.duration(s.movingSeconds), null),
            )
        val plan =
            ride.plan?.let {
                val steps =
                    if (it.stepsRidden == it.stepsPlanned) {
                        "all ${it.stepsPlanned} steps ridden"
                    } else {
                        "${it.stepsRidden} of ${it.stepsPlanned} steps ridden"
                    }
                PlanSummaryUi(
                    "${it.workout.name} · $steps",
                    it.effortRange?.let { range ->
                        val held = UiFormat.range(range.minWatts, range.maxWatts)
                        "Efforts $held W against ${it.effortTarget?.roundToInt()} W"
                    },
                )
            }
        return RideDetailContent(
            name = ride.name,
            whenLine = words.rideWhen(ride.startTime, ride.endTime),
            tiles = tiles,
            plan = plan,
            chart = if (ride.series.isNullOrEmpty()) null else chart(ride, words),
            zones = ride.secondsInZones,
            laps = laps(ride, words),
        )
    }

    private fun laps(
        ride: RideDetailResponse,
        words: Words,
    ): List<LapUi>? {
        if (ride.laps.size <= 1) return null
        return ride.laps.mapIndexed { i, lap ->
            LapUi(
                index = i + 1,
                name = lap.label ?: "Lap ${i + 1}",
                time = words.duration(lap.endSeconds - lap.startSeconds),
                watts = lap.averagePower?.roundToInt()?.toString() ?: UiFormat.EMPTY,
                target =
                    when {
                        lap.type == LapKind.RAMP -> "ramp"
                        lap.targetPower != null -> "of ${lap.targetPower}"
                        else -> null
                    },
                heart = lap.averageHeartRate?.roundToInt()?.toString() ?: UiFormat.EMPTY,
            )
        }
    }

    private fun chart(
        ride: RideDetailResponse,
        words: Words,
    ): RideChartUi {
        val series = ride.series.orEmpty()
        val seconds = DoubleArray(series.size) { series[it].t.toDouble() }
        val power = DoubleArray(series.size) { series[it].power?.toDouble() ?: Double.NaN }
        val heart = DoubleArray(series.size) { series[it].heartRate?.toDouble() ?: Double.NaN }
        val wBal = DoubleArray(series.size) { series[it].wBal }
        val flat = ride.plan?.workout?.flatSteps().orEmpty()
        val steps =
            flat.takeIf { it.isNotEmpty() }?.map {
                ProfileStep(it.startSeconds, it.durationSeconds, it.targetFrom?.toFloat(), it.targetTo?.toFloat())
            }
        val spec =
            RideChartSpec.of(
                seconds = seconds,
                power = power,
                heartRate = heart,
                wBalance = wBal,
                plan = steps,
                ftp = ride.ftp,
                thresholdHr = null,
                wPrimeKj = ride.wPrime,
                durationSeconds = ride.summary.durationSeconds,
            )
        val lowest = ride.wPrimeLowest
        val readoutAt = { x: Double? ->
            if (x == null || x == spec.restX) {
                wholeRide(ride, words, lowest.kilojoules, lowest.atSec)
            } else {
                at(series, flat, x, words)
            }
        }
        return RideChartUi(
            heading = "Power, heart rate and W′ balance",
            spec = spec,
            readoutAt = readoutAt,
            table =
                ValuesTable(
                    "Power, heart rate and W′ balance",
                    "Time",
                    listOf("Power", "Heart", "W′ kJ"),
                    series.reversed().map { p ->
                        ValuesRow(
                            "t${p.t}",
                            words.duration(p.t),
                            listOf(
                                p.power?.toString() ?: UiFormat.EMPTY,
                                p.heartRate?.toString() ?: UiFormat.EMPTY,
                                UiFormat.decimal(p.wBal, 1),
                            ),
                        )
                    },
                ),
            summary = "Power, heart rate and W′ balance",
            noHeartRate = !spec.hasHeartRate,
            noWBalance = !spec.hasWBalance,
        )
    }

    private fun wholeRide(
        ride: RideDetailResponse,
        words: Words,
        lowestKj: Double,
        lowestAt: Int,
    ): Readout =
        Readout(
            "Whole ride",
            listOfNotNull(
                ride.summary.averagePower?.let {
                    ReadoutEntry(ReadoutKey.Line, OverdriveColors.textPrimary, "Power avg", "${it.roundToInt()} W")
                },
                ride.summary.averageHeartRate?.let {
                    ReadoutEntry(
                        ReadoutKey.ThinLine,
                        OverdriveColors.textSecondary,
                        "Heart avg",
                        it.roundToInt().toString(),
                    )
                },
                ReadoutEntry(
                    ReadoutKey.Dot,
                    OverdriveColors.textPrimary,
                    "W′ lowest",
                    "${UiFormat.decimal(lowestKj, 1)} kJ at ${words.duration(lowestAt)}",
                ),
            ),
        )

    private fun at(
        series: List<app.overdrive.core.metrics.DetailPoint>,
        flat: List<FlatStep>,
        x: Double,
        words: Words,
    ): Readout {
        val i = ChartMath.nearestIndex(DoubleArray(series.size) { series[it].t.toDouble() }, x)
        val p = series[i]
        val step = flat.firstOrNull { p.t > it.startSeconds && p.t <= it.endSeconds }
        return Readout(
            words.duration(p.t),
            listOfNotNull(
                p.power?.let { ReadoutEntry(ReadoutKey.Line, OverdriveColors.textPrimary, "Power", "$it W") },
                p.heartRate?.let {
                    ReadoutEntry(ReadoutKey.ThinLine, OverdriveColors.textSecondary, "Heart", it.toString())
                },
                ReadoutEntry(ReadoutKey.Dot, OverdriveColors.textPrimary, "W′", "${UiFormat.decimal(p.wBal, 1)} kJ"),
                step?.let { ReadoutEntry(ReadoutKey.None, OverdriveColors.textPrimary, null, stepWords(it)) },
            ),
        )
    }

    /** "VO2 4 of 5 · Z5": the step's own label, its place in a repeat, and its zone. */
    fun stepWords(step: FlatStep): String {
        val count = step.repeatCount
        val repeat = if (count != null && count > 1) " ${step.repeatIteration} of $count" else ""
        val zone =
            step.targetFrom?.let { from ->
                " · Z${Zones.ofFraction((from + (step.targetTo ?: from)) / 2.0)}"
            } ?: ""
        return "${step.label}$repeat$zone"
    }
}

/** The pushed screen of a watch activity from the list (design/companion.md §4.8): what the list already says, until the watch data stage. */
@Immutable
data class ActivityUi(
    val name: String,
    val whenLine: String,
    val tiles: List<TileUi>,
    val load: TileUi,
    val countsLine: String,
)

object ActivityWords {
    private const val LOAD_NOTE = "From heart rate, approximate: short hard efforts are under-counted."
    private const val COUNTS_LINE =
        "Counts toward fatigue, form and recovery. Not used for the power curve, FTP or aerobic drift."

    fun of(
        item: RideItem,
        words: Words,
    ): ActivityUi =
        ActivityUi(
            name = item.name,
            whenLine = "${words.day(item.date)} · from the watch",
            tiles =
                listOf(
                    TileUi("Duration", words.duration(item.durationSeconds), null),
                    TileUi("Avg heart", item.averageHeartRate?.roundToInt()?.toString(), null, "bpm"),
                    TileUi("Max heart", null, null, "bpm"),
                ),
            load = TileUi("Load", item.load?.roundToInt()?.toString(), LOAD_NOTE),
            countsLine = COUNTS_LINE,
        )
}
