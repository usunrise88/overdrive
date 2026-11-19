package app.overdrive.core.remote

import app.overdrive.core.insights.Insight
import app.overdrive.core.insights.InsightsApiJson
import app.overdrive.core.insights.InsightsResponse
import app.overdrive.core.plan.PlanCalendar
import app.overdrive.core.sync.SyncConfig
import app.overdrive.core.wellness.RecoveryResponse
import app.overdrive.core.wellness.UsualSleepTimes
import app.overdrive.core.wellness.WellnessApiJson
import app.overdrive.core.wellness.WellnessDay
import app.overdrive.core.wellness.WellnessResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/*
 * What the phone reads of the watch data from its own service (spec §6.1, §6.4, §6.5, ADR 0027): the wellness figures with their
 * personal ranges (`GET /v1/wellness`), the recovery rating (`GET /v1/recovery`) and the written notes (`GET /v1/insights`). Each is
 * a [RemoteResource]: read from disk first, asked when a screen opens or the rider pulls down, kept on disk, offline-safe.
 */

/** The range selector of Wellness (design/companion.md §3.4): 4 weeks, 3 months, 1 year, each ending today. */
public enum class WellnessRange(
    public val days: Int,
    /** A word for the cache's name. */
    public val key: String,
) {
    FOUR_WEEKS(28, "4w"),
    THREE_MONTHS(91, "13w"),
    ONE_YEAR(365, "52w"),
    ;

    /** The first day of the range for the local date [today]. */
    public fun from(today: String): String = PlanCalendar.addDays(today, 1 - days)

    /**
     * The requests that cover the range, oldest first. The service serves at most 186 days at a time, so a year is two of them
     * (the older half and the newer), which [WellnessResources] joins.
     */
    public fun parts(today: String): List<Pair<String, String>> {
        val first = from(today)
        if (days <= WellnessResources.MAX_SERVED_DAYS) return listOf(first to today)
        val half = days / 2
        val middle = PlanCalendar.addDays(first, half - 1)
        return listOf(first to middle, PlanCalendar.addDays(middle, 1) to today)
    }

    public companion object {
        public val DEFAULT: WellnessRange = FOUR_WEEKS
    }
}

/**
 * Wellness as a screen draws it: every day of the range (oldest first, one per local date), the usual sleep times and how many nights
 * the baselines stand on, from the latest answer. A year is two answers joined here, because [WellnessResponse] holds at most 186 days.
 */
public class WellnessData(
    public val from: String,
    public val to: String,
    public val generatedAt: String,
    public val stale: Boolean,
    public val nightsLogged: Int,
    public val nightsNeeded: Int,
    public val usual: UsualSleepTimes,
    public val days: List<WellnessDay>,
) {
    public companion object {
        public fun of(response: WellnessResponse): WellnessData =
            WellnessData(
                response.from,
                response.to,
                response.generatedAt,
                response.stale,
                response.nightsLogged,
                response.nightsNeeded,
                response.usual,
                response.days,
            )

        /** The parts in order, oldest first; the figures about the baselines are the newest part's. */
        public fun join(parts: List<WellnessResponse>): WellnessData {
            val newest = parts.last()
            return WellnessData(
                parts.first().from,
                newest.to,
                newest.generatedAt,
                parts.any { it.stale },
                newest.nightsLogged,
                newest.nightsNeeded,
                newest.usual,
                parts.flatMap { it.days },
            )
        }
    }
}

/** What a screen reads of one range of Wellness: the joined state, and the ways to load and refresh it. */
public class WellnessView internal constructor(
    private val parts: List<RemoteResource<WellnessResponse>>,
    scope: CoroutineScope,
) {
    public val state: StateFlow<ResourceState<WellnessData>> =
        combine(parts.map { it.state }) { states -> join(states.toList()) }
            .stateIn(scope, SharingStarted.Eagerly, ResourceState())

    internal suspend fun load() {
        for (part in parts) part.load()
    }

    internal fun settingsChanged() {
        for (part in parts) part.settingsChanged()
    }

    internal suspend fun refresh(why: Refresh) {
        coroutineScope {
            for (part in parts) launch { part.refresh(why) }
        }
    }

    private fun join(states: List<ResourceState<WellnessResponse>>): ResourceState<WellnessData> {
        val have = states.mapNotNull { it.value }
        // A year whose older half is missing shows the newer half, and says why through the problem.
        val value =
            when {
                have.isEmpty() -> null
                states.last().value == null -> null
                else -> WellnessData.join(states.dropWhile { it.value == null }.mapNotNull { it.value })
            }
        return ResourceState(
            loaded = states.all { it.loaded },
            value = value,
            fetchedAtMillis = states.filter { it.value != null }.mapNotNull { it.fetchedAtMillis }.minOrNull(),
            problem = states.firstNotNullOfOrNull { it.problem },
            refreshing = states.any { it.refreshing },
            asked = states.any { it.asked },
        )
    }
}

/** `GET /v1/wellness?from&to` for each [WellnessRange], one cache per part (design/companion.md §4.5, ADR 0023). */
public class WellnessResources(
    scope: CoroutineScope,
    client: ServiceClient,
    config: () -> SyncConfig,
    today: () -> String,
    storeFor: (String) -> TextStore?,
    wallMillis: () -> Long,
    elapsedMillis: () -> Long,
    minGapMillis: Long = RemoteResource.MIN_GAP_MILLIS,
) {
    private val views: Map<WellnessRange, WellnessView> =
        WellnessRange.entries.associateWith { range ->
            val count = range.parts("2026-01-01").size
            WellnessView(
                List(count) { index ->
                    val kind = "wellness-${range.key}-$index"
                    RemoteResource(
                        kind = kind,
                        client = client,
                        config = config,
                        path = {
                            val (from, to) = range.parts(today())[index]
                            "/v1/wellness?from=$from&to=$to"
                        },
                        decode = WellnessApiJson::decodeWellnessResponse,
                        store = storeFor(kind),
                        wallMillis = wallMillis,
                        elapsedMillis = elapsedMillis,
                        minGapMillis = minGapMillis,
                    )
                },
                scope,
            )
        }

    public fun view(range: WellnessRange): WellnessView = views.getValue(range)

    /** Reads every range's cache from disk. */
    public suspend fun load() {
        for (view in views.values) view.load()
    }

    public fun settingsChanged() {
        for (view in views.values) view.settingsChanged()
    }

    /** Asks for [range]'s answers (each part at most as [why] allows). */
    public suspend fun refresh(
        range: WellnessRange,
        why: Refresh,
    ) {
        view(range).refresh(why)
    }

    public companion object {
        /** `GET /v1/wellness` serves at most this many days (`400 invalid_range` beyond). */
        public const val MAX_SERVED_DAYS: Int = 186
    }
}

/** `GET /v1/recovery` (design/companion.md §4.1, ADR 0023): the state of this morning with the facts behind it. */
public fun recoveryResource(
    client: ServiceClient,
    config: () -> SyncConfig,
    store: TextStore?,
    wallMillis: () -> Long,
    elapsedMillis: () -> Long,
    minGapMillis: Long = RemoteResource.MIN_GAP_MILLIS,
): RemoteResource<RecoveryResponse> =
    RemoteResource(
        kind = "recovery",
        client = client,
        config = config,
        path = { "/v1/recovery" },
        decode = WellnessApiJson::decodeRecoveryResponse,
        store = store,
        wallMillis = wallMillis,
        elapsedMillis = elapsedMillis,
        minGapMillis = minGapMillis,
    )

/** The notes fetched so far, newest first. */
public data class InsightsList(
    val items: List<Insight> = emptyList(),
    /** Older notes may exist than the ones fetched. */
    val hasMore: Boolean = false,
    val loadingMore: Boolean = false,
    val moreProblem: ServiceProblem? = null,
)

/** What the Notes screen reads: the newest window's state and every note fetched. */
public data class InsightsView(
    val first: ResourceState<InsightsResponse> = ResourceState(),
    val list: InsightsList = InsightsList(),
)

/**
 * `GET /v1/insights?from&to` (design/companion.md §4.1, §4.2, ADR 0025): the newest [windowDays] days are a [RemoteResource] (cached,
 * shown at once, offline too); older windows of the same size are fetched by [loadMore], appended in memory and dropped when the
 * newest is fetched again. A year of notes at most: the service serves 366 days, and nothing on a phone reads further back.
 */
public class InsightsRepository(
    scope: CoroutineScope,
    private val client: ServiceClient,
    config: () -> SyncConfig,
    private val today: () -> String,
    store: TextStore?,
    wallMillis: () -> Long,
    elapsedMillis: () -> Long,
    private val windowDays: Int = WINDOW_DAYS,
    private val maxWindows: Int = MAX_WINDOWS,
    minGapMillis: Long = RemoteResource.MIN_GAP_MILLIS,
) {
    /** The newest window: today and the days before it. */
    public val newest: RemoteResource<InsightsResponse> =
        RemoteResource(
            kind = "insights",
            client = client,
            config = config,
            path = { window(0) },
            decode = InsightsApiJson::decodeResponse,
            store = store,
            wallMillis = wallMillis,
            elapsedMillis = elapsedMillis,
            minGapMillis = minGapMillis,
        )

    private class More(
        val pages: List<InsightsResponse> = emptyList(),
        val loading: Boolean = false,
        val problem: ServiceProblem? = null,
        val after: Long? = null,
    )

    private val more = MutableStateFlow(More())
    private val loadingLock = Mutex()

    public val view: StateFlow<InsightsView> =
        combine(newest.state, more) { first, extra -> viewOf(first, extra) }
            .stateIn(scope, SharingStarted.Eagerly, InsightsView())

    private fun window(index: Int): String {
        val to = PlanCalendar.addDays(today(), -index * windowDays)
        val from = PlanCalendar.addDays(to, 1 - windowDays)
        return "/v1/insights?from=$from&to=$to"
    }

    public suspend fun load(): Unit = newest.load()

    public fun settingsChanged(): Unit = newest.settingsChanged()

    public suspend fun refresh(why: Refresh) {
        val before = newest.state.value.fetchedAtMillis
        newest.refresh(why)
        if (newest.state.value.fetchedAtMillis != before) more.value = More()
    }

    /** Fetches the window before the last one fetched, if there is one and none is being fetched. */
    public suspend fun loadMore() {
        val first = newest.state.value
        if (first.value == null) return
        if (!loadingLock.tryLock()) return
        try {
            val current = more.value.takeIf { it.after == first.fetchedAtMillis } ?: More(after = first.fetchedAtMillis)
            val index = current.pages.size + 1
            if (index >= maxWindows) return
            more.value = More(current.pages, loading = true, after = current.after)
            more.value =
                when (
                    val outcome =
                        client.get(
                            window(index),
                            RemoteResource.DEFAULT_MAX_BYTES,
                            InsightsApiJson::decodeResponse,
                        )
                ) {
                    is Outcome.Ok -> More(current.pages + outcome.value, after = current.after)
                    is Outcome.Failed -> More(current.pages, problem = outcome.problem, after = current.after)
                    Outcome.NotSetUp -> More(current.pages, after = current.after)
                }
        } finally {
            loadingLock.unlock()
        }
    }

    private fun viewOf(
        first: ResourceState<InsightsResponse>,
        extra: More,
    ): InsightsView {
        val head = first.value ?: return InsightsView(first, InsightsList())
        val mine = extra.takeIf { it.after == first.fetchedAtMillis }
        val pages = mine?.pages.orEmpty()
        return InsightsView(
            first,
            InsightsList(
                items = head.insights + pages.flatMap { it.insights },
                hasMore = pages.size + 1 < maxWindows,
                loadingMore = mine?.loading == true,
                moreProblem = mine?.problem,
            ),
        )
    }

    public companion object {
        /** The design loads 30 days, then 30 more (design/companion.md §4.2). */
        public const val WINDOW_DAYS: Int = 30
        public const val MAX_WINDOWS: Int = 12
    }
}
