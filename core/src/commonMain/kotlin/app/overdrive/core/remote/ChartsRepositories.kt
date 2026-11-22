package app.overdrive.core.remote

import app.overdrive.core.effort.EFFORT_DEFAULT_WEEKS
import app.overdrive.core.effort.EFFORT_MAX_WEEKS
import app.overdrive.core.effort.EffortApiJson
import app.overdrive.core.effort.EffortResponse
import app.overdrive.core.plan.PlanCalendar
import app.overdrive.core.plan.PlanDates
import app.overdrive.core.powerskills.POWER_SKILLS_DEFAULT_DAYS
import app.overdrive.core.powerskills.POWER_SKILLS_MAX_DAYS
import app.overdrive.core.powerskills.PowerSkillsApiJson
import app.overdrive.core.powerskills.PowerSkillsResponse
import app.overdrive.core.sync.SyncConfig
import kotlinx.coroutines.flow.MutableStateFlow

/*
 * What the phone reads for the Power skills radar and the weekly Effort chart (spec §6.3, §8.3, docs/adr/0042): `GET /v1/power-skills`
 * for the window the rider chose and `GET /v1/effort`. Each is a [RemoteResource] of ADR 0024, the same as the repeated sessions
 * (ADR 0028): read from disk first, asked when the screen opens or the rider pulls down, kept on disk, offline-safe, with the cause when
 * the cache is old. The phone computes nothing of what they say: the scores, the levels, the usual ranges and the verdicts are the
 * service's, and a screen only draws and words them.
 */

/** The window of the Power skills card (design/companion.md §4.14): eight weeks, everything the service serves, or a range of days. */
public sealed interface PowerSkillsWindow {
    /** The cache's name of this window, a file-name-safe word. */
    public val key: String

    /** The days of the window for the local date [today]: `from` and `to`, both included. */
    public fun days(today: String): Pair<String, String>

    /** The last 56 days up to today: the default. */
    public data object EightWeeks : PowerSkillsWindow {
        override val key: String = "8w"

        override fun days(today: String): Pair<String, String> =
            PlanCalendar.addDays(today, 1 - POWER_SKILLS_DEFAULT_DAYS) to today
    }

    /**
     * All time: the longest window the service serves, three years up to today, so the "all time" outline and the window's are the
     * same set of rides. The response's `allTime` is the best of **all** rides up to the end of the window whatever its start.
     */
    public data object AllTime : PowerSkillsWindow {
        override val key: String = "all"

        override fun days(today: String): Pair<String, String> =
            PlanCalendar.addDays(today, 1 - POWER_SKILLS_MAX_DAYS) to today
    }

    /** A range the rider chose in the picker: real local days, [from] not after [to], at most three years. */
    public data class Custom(
        val from: String,
        val to: String,
    ) : PowerSkillsWindow {
        init {
            val start = requireNotNull(PlanDates.epochDay(from)) { "from is not a calendar date" }
            val end = requireNotNull(PlanDates.epochDay(to)) { "to is not a calendar date" }
            require(end >= start) { "from is after to" }
            require(end - start + 1 <= POWER_SKILLS_MAX_DAYS) { "the range is longer than $POWER_SKILLS_MAX_DAYS days" }
        }

        override val key: String get() = "c-$from-$to"

        override fun days(today: String): Pair<String, String> = from to to
    }
}

/**
 * `GET /v1/power-skills?from&to` for each [PowerSkillsWindow], one [RemoteResource] per window, each with its own cache, so switching
 * the window shows what was last fetched for it at once (and offline). The two standing windows are made with the repository; a custom
 * one on first use, and only the most recent [keep] are kept in memory (the caches on disk are bounded by the caller's store).
 *
 * @param storeFor the cache of one window by its name ("power-skills-8w"), or null for none.
 * @param today the phone's local date, `YYYY-MM-DD`.
 */
public class PowerSkillsResources(
    private val client: ServiceClient,
    private val config: () -> SyncConfig,
    private val today: () -> String,
    private val storeFor: (String) -> TextStore?,
    private val wallMillis: () -> Long,
    private val elapsedMillis: () -> Long,
    private val keep: Int = KEEP_CUSTOM,
    private val minGapMillis: Long = RemoteResource.MIN_GAP_MILLIS,
) {
    private val standing: Map<PowerSkillsWindow, RemoteResource<PowerSkillsResponse>> =
        listOf(PowerSkillsWindow.EightWeeks, PowerSkillsWindow.AllTime).associateWith(::make)

    private val custom =
        MutableStateFlow<Map<PowerSkillsWindow.Custom, RemoteResource<PowerSkillsResponse>>>(emptyMap())

    private fun make(window: PowerSkillsWindow): RemoteResource<PowerSkillsResponse> {
        val kind = "power-skills-${window.key}"
        return RemoteResource(
            kind = kind,
            client = client,
            config = config,
            path = {
                val (from, to) = window.days(today())
                "/v1/power-skills?from=$from&to=$to"
            },
            decode = PowerSkillsApiJson::decodePowerSkills,
            store = storeFor(kind),
            wallMillis = wallMillis,
            elapsedMillis = elapsedMillis,
            minGapMillis = minGapMillis,
        )
    }

    /** The resource of [window]. A custom window's cache is read when it is first asked for, before its first request. */
    public fun resource(window: PowerSkillsWindow): RemoteResource<PowerSkillsResponse> {
        standing[window]?.let { return it }
        val range = window as PowerSkillsWindow.Custom
        while (true) {
            val current = custom.value
            current[range]?.let { return it }
            val made = make(range)
            val all = current + (range to made)
            val next = all.entries.drop(maxOf(0, all.size - keep)).associate { it.toPair() }
            if (custom.compareAndSet(current, next)) return made
        }
    }

    /** Reads the caches of the two standing windows from disk. */
    public suspend fun load() {
        for (resource in standing.values) resource.load()
    }

    public fun settingsChanged() {
        for (resource in standing.values) resource.settingsChanged()
        for (resource in custom.value.values) resource.settingsChanged()
    }

    private companion object {
        const val KEEP_CUSTOM = 4
    }
}

/**
 * `GET /v1/effort?weeks=N` (spec §6.3, docs/adr/0042): the weekly training load with each week's usual range, verdict, days and
 * activities. One [RemoteResource] with its own cache. The Effort card draws the last [EFFORT_DEFAULT_WEEKS] (12) weeks and its Values
 * sheet all of them, so the container asks for [weeks] = [EFFORT_MAX_WEEKS] (52): one request, some 60 KB, whatever the card shows.
 */
public fun effortResource(
    client: ServiceClient,
    config: () -> SyncConfig,
    store: TextStore?,
    wallMillis: () -> Long,
    elapsedMillis: () -> Long,
    weeks: Int = EFFORT_MAX_WEEKS,
    minGapMillis: Long = RemoteResource.MIN_GAP_MILLIS,
): RemoteResource<EffortResponse> {
    require(weeks in 1..EFFORT_MAX_WEEKS) { "weeks is not within 1..52" }
    return RemoteResource(
        kind = "effort-${weeks}w",
        client = client,
        config = config,
        path = { "/v1/effort?weeks=$weeks" },
        decode = EffortApiJson::decodeEffort,
        store = store,
        wallMillis = wallMillis,
        elapsedMillis = elapsedMillis,
        minGapMillis = minGapMillis,
    )
}
