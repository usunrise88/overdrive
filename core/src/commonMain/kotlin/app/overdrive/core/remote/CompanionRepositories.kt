package app.overdrive.core.remote

import app.overdrive.core.metrics.DEFAULT_RIDES_LIMIT
import app.overdrive.core.metrics.MetricsApiJson
import app.overdrive.core.metrics.MetricsResponse
import app.overdrive.core.metrics.RideDetailResponse
import app.overdrive.core.metrics.RideItem
import app.overdrive.core.metrics.RidesResponse
import app.overdrive.core.plan.PlanCalendar
import app.overdrive.core.sync.SyncConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex

/**
 * The range selector of Fitness and Power (design/companion.md §3.4): whole Monday-first weeks ending with the current one, so
 * the weekly columns are exactly 6, 26 or 52 and the daily series cover the same days.
 */
public enum class MetricsRange(
    public val weeks: Int,
    /** A word for the cache's name. */
    public val key: String,
) {
    SIX_WEEKS(6, "6w"),
    SIX_MONTHS(26, "26w"),
    ONE_YEAR(52, "52w"),
    ;

    /** The first day of the range for the local date [today]: the Monday of the current week, `weeks - 1` weeks back. */
    public fun from(today: String): String {
        val monday = PlanCalendar.addDays(today, 1 - PlanCalendar.dayOfWeek(today))
        return PlanCalendar.addDays(monday, -DAYS_PER_WEEK * (weeks - 1))
    }

    public companion object {
        public val DEFAULT: MetricsRange = SIX_MONTHS
        private const val DAYS_PER_WEEK = 7
    }
}

/**
 * `GET /v1/metrics?from&to` for each [MetricsRange] (spec §6.3, §8.3, ADR 0022), one [RemoteResource] per range, each with its
 * own cache, so switching the range shows what was last fetched for it at once.
 *
 * @param storeFor the cache of one resource by its name ("metrics-26w"), or null for none.
 * @param today the phone's local date, `YYYY-MM-DD`.
 */
public class MetricsRepository(
    client: ServiceClient,
    config: () -> SyncConfig,
    today: () -> String,
    storeFor: (String) -> TextStore?,
    wallMillis: () -> Long,
    elapsedMillis: () -> Long,
    minGapMillis: Long = RemoteResource.MIN_GAP_MILLIS,
) {
    private val resources: Map<MetricsRange, RemoteResource<MetricsResponse>> =
        MetricsRange.entries.associateWith { range ->
            val kind = "metrics-${range.key}"
            RemoteResource(
                kind = kind,
                client = client,
                config = config,
                path = {
                    val to = today()
                    "/v1/metrics?from=${range.from(to)}&to=$to"
                },
                decode = MetricsApiJson::decodeMetrics,
                store = storeFor(kind),
                wallMillis = wallMillis,
                elapsedMillis = elapsedMillis,
                minGapMillis = minGapMillis,
            )
        }

    public fun resource(range: MetricsRange): RemoteResource<MetricsResponse> = resources.getValue(range)

    /** Reads every range's cache from disk. */
    public suspend fun load() {
        for (resource in resources.values) resource.load()
    }

    public fun settingsChanged() {
        for (resource in resources.values) resource.settingsChanged()
    }
}

/** The rows of the Rides list as far as they have been fetched. */
public data class RidesList(
    val items: List<RideItem> = emptyList(),
    /** Older items exist than the ones fetched. */
    val hasMore: Boolean = false,
    /** A page of older items is being fetched. */
    val loadingMore: Boolean = false,
    /** The last attempt at an older page failed. */
    val moreProblem: ServiceProblem? = null,
)

/** What the Rides screen reads: the newest page's [first] state (updated, stale, error) and the [list] of every page fetched. */
public data class RidesView(
    val first: ResourceState<RidesResponse> = ResourceState(),
    val list: RidesList = RidesList(),
)

/**
 * `GET /v1/rides?before&limit` (spec §6.7, §8.3, ADR 0022): the newest page is a [RemoteResource] (cached, shown at once,
 * offline too); older pages are fetched by [loadMore] with the cursor the service gave, appended in memory and dropped when the
 * newest page is fetched again (the list may have changed under them).
 *
 * @param scope where [view] is combined; the screens read [view] and nothing else.
 */
public class RidesRepository(
    scope: CoroutineScope,
    private val client: ServiceClient,
    config: () -> SyncConfig,
    store: TextStore?,
    wallMillis: () -> Long,
    elapsedMillis: () -> Long,
    private val pageSize: Int = DEFAULT_RIDES_LIMIT,
    minGapMillis: Long = RemoteResource.MIN_GAP_MILLIS,
) {
    /** The newest page. */
    public val newest: RemoteResource<RidesResponse> =
        RemoteResource(
            kind = "rides",
            client = client,
            config = config,
            path = { "/v1/rides?limit=$pageSize" },
            decode = MetricsApiJson::decodeRides,
            store = store,
            wallMillis = wallMillis,
            elapsedMillis = elapsedMillis,
            minGapMillis = minGapMillis,
        )

    private class More(
        val pages: List<RidesResponse> = emptyList(),
        val loading: Boolean = false,
        val problem: ServiceProblem? = null,
        /** The `fetchedAtMillis` of the newest page these pages hang from. */
        val after: Long? = null,
    )

    private val more = MutableStateFlow(More())
    private val loadingLock = Mutex()

    /** What the Rides screen shows: the newest page's state and every page fetched so far, newest first. */
    public val view: StateFlow<RidesView> =
        combine(newest.state, more) { first, extra -> viewOf(first, extra) }
            .stateIn(scope, SharingStarted.Eagerly, RidesView())

    /** Reads the newest page's cache from disk. */
    public suspend fun load(): Unit = newest.load()

    public fun settingsChanged(): Unit = newest.settingsChanged()

    /** Refreshes the newest page; older pages already fetched are dropped when a new one arrives. */
    public suspend fun refresh(why: Refresh) {
        val before = newest.state.value.fetchedAtMillis
        newest.refresh(why)
        if (newest.state.value.fetchedAtMillis != before) more.value = More()
    }

    /** Fetches the page of older items after the last one, if there is one and none is being fetched. */
    public suspend fun loadMore() {
        val first = newest.state.value
        val head = first.value ?: return
        if (!loadingLock.tryLock()) return
        try {
            val current = more.value.takeIf { it.after == first.fetchedAtMillis } ?: More(after = first.fetchedAtMillis)
            val last = current.pages.lastOrNull() ?: head
            val cursor = last.nextBefore?.takeIf { last.hasMore } ?: return
            more.value = More(current.pages, loading = true, after = current.after)
            val outcome =
                client.get(
                    "/v1/rides?before=$cursor&limit=$pageSize",
                    RemoteResource.DEFAULT_MAX_BYTES,
                    MetricsApiJson::decodeRides,
                )
            more.value =
                when (outcome) {
                    is Outcome.Ok -> More(current.pages + outcome.value, after = current.after)
                    is Outcome.Failed -> More(current.pages, problem = outcome.problem, after = current.after)
                    Outcome.NotSetUp -> More(current.pages, after = current.after)
                }
        } finally {
            loadingLock.unlock()
        }
    }

    private fun viewOf(
        first: ResourceState<RidesResponse>,
        extra: More,
    ): RidesView {
        val head = first.value ?: return RidesView(first, RidesList())
        val mine = extra.takeIf { it.after == first.fetchedAtMillis }
        val pages = mine?.pages.orEmpty()
        val last = pages.lastOrNull() ?: head
        return RidesView(
            first,
            RidesList(
                items = head.items + pages.flatMap { it.items },
                hasMore = last.hasMore,
                loadingMore = mine?.loading == true,
                moreProblem = mine?.problem,
            ),
        )
    }
}

/**
 * `GET /v1/rides/{id}?series=true` (spec §6.1, §8.3): one ride as the detail screen shows it, a [RemoteResource] per ride id,
 * each cached by its id, so a ride that was opened once opens offline. Only the most recent few are kept in memory.
 *
 * @param storeFor the cache of one ride by its id, or null for none.
 */
public class RideDetails(
    private val client: ServiceClient,
    private val config: () -> SyncConfig,
    private val storeFor: (String) -> TextStore?,
    private val wallMillis: () -> Long,
    private val elapsedMillis: () -> Long,
    private val keep: Int = KEEP_IN_MEMORY,
    private val minGapMillis: Long = RemoteResource.MIN_GAP_MILLIS,
) {
    private val resources = MutableStateFlow<Map<String, RemoteResource<RideDetailResponse>>>(emptyMap())

    /** The resource of ride [id], made on first use. @throws IllegalArgumentException if [id] is not a ride id. */
    public fun resource(id: String): RemoteResource<RideDetailResponse> {
        require(MetricsApiJson.isValidRideId(id)) { "Not a ride id" }
        while (true) {
            val current = resources.value
            current[id]?.let { return it }
            val made =
                RemoteResource(
                    kind = "ride-detail",
                    client = client,
                    config = config,
                    path = { "/v1/rides/$id?series=true" },
                    decode = MetricsApiJson::decodeRideDetail,
                    store = storeFor(id),
                    wallMillis = wallMillis,
                    elapsedMillis = elapsedMillis,
                    minGapMillis = minGapMillis,
                )
            val all = current + (id to made)
            val next = all.entries.drop(maxOf(0, all.size - keep)).associate { it.toPair() }
            if (resources.compareAndSet(current, next)) return made
        }
    }

    public fun settingsChanged() {
        for (resource in resources.value.values) resource.settingsChanged()
    }

    private companion object {
        const val KEEP_IN_MEMORY = 8
    }
}
