package app.overdrive.core.remote

import app.overdrive.core.progress.ProgressApiJson
import app.overdrive.core.progress.ProgressResponse
import app.overdrive.core.sync.SyncConfig

/*
 * What the phone reads of the Progress screen's data (spec §6.3 "Progress over time", §8.3, ADR 0043): `GET /v1/progress?from&to`, one
 * [RemoteResource] of ADR 0024 per range, each kept on disk under its own name, so switching the range shows what was last fetched for
 * it at once and offline (with the cause when it is old). The phone computes nothing of what it says: the series, the trends and the
 * sentences are the service's, and a screen only words the numbers around them and draws.
 */

/**
 * The range selector of Progress (design/companion.md §4.16): **6 months**, **1 year** and **All time**, each ending today. The service
 * serves at most five years, which is what "All time" asks for.
 */
public enum class ProgressRange(
    /** How many calendar months back from today the range starts. */
    public val months: Int,
    /** A word for the cache's name. */
    public val key: String,
) {
    SIX_MONTHS(6, "6m"),
    ONE_YEAR(12, "1y"),
    ALL_TIME(60, "all"),
    ;

    /** The first day of the range for the local date [today]: the same day [months] months back, clamped to the end of a shorter month. */
    public fun from(today: String): String = monthsBefore(today, months)

    public companion object {
        public val DEFAULT: ProgressRange = SIX_MONTHS

        /** The calendar day [months] months before [date] (`YYYY-MM-DD`), clamped to the last day of a shorter month. */
        public fun monthsBefore(
            date: String,
            months: Int,
        ): String {
            val year = date.substring(0, 4).toInt()
            val month = date.substring(5, 7).toInt()
            val day = date.substring(8, 10).toInt()
            val index = year * MONTHS_PER_YEAR + (month - 1) - months
            val targetYear = index.floorDiv(MONTHS_PER_YEAR)
            val targetMonth = index.mod(MONTHS_PER_YEAR) + 1
            val lastDay = daysIn(targetYear, targetMonth)
            return "${targetYear.toString().padStart(4, '0')}-${targetMonth.toString().padStart(2, '0')}-" +
                minOf(day, lastDay).toString().padStart(2, '0')
        }

        private fun daysIn(
            year: Int,
            month: Int,
        ): Int =
            when (month) {
                FEBRUARY -> if (year % LEAP_4 == 0 && (year % LEAP_100 != 0 || year % LEAP_400 == 0)) 29 else 28
                APRIL, JUNE, SEPTEMBER, NOVEMBER -> 30
                else -> 31
            }

        private const val MONTHS_PER_YEAR = 12
        private const val FEBRUARY = 2
        private const val APRIL = 4
        private const val JUNE = 6
        private const val SEPTEMBER = 9
        private const val NOVEMBER = 11
        private const val LEAP_4 = 4
        private const val LEAP_100 = 100
        private const val LEAP_400 = 400
    }
}

/**
 * `GET /v1/progress?from&to` for each [ProgressRange], one [RemoteResource] per range with its own cache ("progress-6m").
 *
 * **What the screen calls:** [resource] for the range shown, [load] once at start, [settingsChanged] when the address or token changes,
 * and `resource(range).refresh(why)` when the screen opens ([Refresh.SCREEN_OPENED]), when the rider pulls down ([Refresh.REQUESTED])
 * and from Sync now. The companion's container does all of that (`progressFor`, `RefreshTarget.Progress`, `syncNow`).
 *
 * @param storeFor the cache of one resource by its name, or null for none.
 * @param today the phone's local date, `YYYY-MM-DD`: asked at every request, so a range follows the clock.
 */
public class ProgressRepository(
    client: ServiceClient,
    config: () -> SyncConfig,
    today: () -> String,
    storeFor: (String) -> TextStore?,
    wallMillis: () -> Long,
    elapsedMillis: () -> Long,
    minGapMillis: Long = RemoteResource.MIN_GAP_MILLIS,
) {
    private val resources: Map<ProgressRange, RemoteResource<ProgressResponse>> =
        ProgressRange.entries.associateWith { range ->
            val kind = "progress-${range.key}"
            RemoteResource(
                kind = kind,
                client = client,
                config = config,
                path = {
                    val to = today()
                    "/v1/progress?from=${range.from(to)}&to=$to"
                },
                decode = ProgressApiJson::decode,
                store = storeFor(kind),
                wallMillis = wallMillis,
                elapsedMillis = elapsedMillis,
                minGapMillis = minGapMillis,
            )
        }

    public fun resource(range: ProgressRange): RemoteResource<ProgressResponse> = resources.getValue(range)

    /** Reads every range's cache from disk. */
    public suspend fun load() {
        for (resource in resources.values) resource.load()
    }

    public fun settingsChanged() {
        for (resource in resources.values) resource.settingsChanged()
    }
}
