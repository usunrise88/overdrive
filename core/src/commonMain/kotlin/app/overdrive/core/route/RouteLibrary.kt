package app.overdrive.core.route

import app.overdrive.core.plan.PlanAgenda
import app.overdrive.core.plan.PlannedWorkout
import app.overdrive.core.remote.CacheText
import app.overdrive.core.remote.ProblemKind
import app.overdrive.core.remote.Refresh
import app.overdrive.core.remote.RemoteResource
import app.overdrive.core.remote.ServiceClient
import app.overdrive.core.remote.ServiceProblem
import app.overdrive.core.remote.TextStore
import app.overdrive.core.sync.SyncConfig
import app.overdrive.core.workout.FreeStep
import app.overdrive.core.workout.RepeatStep
import app.overdrive.core.workout.Step
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the tablet has of one route: its points on the tablet (a ride may use it), a download under way, a failed one, or nothing yet. */
public enum class RouteStatus {
    /** The route's points are on the tablet, in memory and on disk: a ride on it needs no network. */
    READY,

    /** The points are being fetched or read from disk. */
    DOWNLOADING,

    /** The last attempt failed (no network, a service that is down, a route the service no longer has). */
    FAILED,

    /** Nothing was asked yet. */
    NOT_DOWNLOADED,
}

/**
 * The routes' points on the tablet: what a ride can use, with no network.
 *
 * @property downloaded the routes whose points are loaded, by id. A ride is built from these and from nothing else.
 * @property downloading the ids being fetched or read from disk now.
 * @property failed the ids whose last attempt failed, with the reason in plain words.
 */
public data class RouteDetailsState(
    val downloaded: Map<String, Route> = emptyMap(),
    val downloading: Set<String> = emptySet(),
    val failed: Map<String, String> = emptyMap(),
) {
    public fun statusOf(id: String): RouteStatus =
        when {
            id in downloaded -> RouteStatus.READY
            id in downloading -> RouteStatus.DOWNLOADING
            id in failed -> RouteStatus.FAILED
            else -> RouteStatus.NOT_DOWNLOADED
        }
}

/**
 * The routes on the tablet (spec §11.4, §8.3, ADR 0036, ADR 0037): the **list** (`GET /v1/routes`) is a cached [RemoteResource] shown at
 * once and offline, like the plan; the **points** of one route (`GET /v1/routes/{id}`) are fetched when a route is chosen, and for the
 * routes the next days' planned `free` steps name ([routeIdsOf]), and **kept on disk, one file a route**, so that a ride never needs the
 * network: a route a ride needs is either loaded here or the ride is flat and says "route not downloaded yet".
 *
 * A route **never changes** (the service creates and deletes, it does not edit), so a route that is on the tablet is never asked for
 * again: [ensure] reads the disk first and goes to the network only for what is not there, one request at a time. What came back must be
 * the route that was asked for, else it is not the service. Nothing here logs, and no token is in any state.
 *
 * @param listStore the list's cache, or null for none.
 * @param detailStore the cache of one route by its id, or null for none; `FileTextStores` bounds the folder.
 */
public class RouteLibrary(
    private val client: ServiceClient,
    private val config: () -> SyncConfig,
    listStore: TextStore?,
    private val detailStore: (String) -> TextStore?,
    private val wallMillis: () -> Long,
    private val elapsedMillis: () -> Long,
    private val minGapMillis: Long = RemoteResource.MIN_GAP_MILLIS,
) {
    /** `GET /v1/routes`: the routes the agent created, newest first, without points. Read from disk by [load]. */
    public val list: RemoteResource<RoutesResponse> =
        RemoteResource(
            kind = "routes",
            client = client,
            config = config,
            path = { "/v1/routes" },
            decode = RoutesApiJson::decodeRoutes,
            store = listStore,
            wallMillis = wallMillis,
            elapsedMillis = elapsedMillis,
            minGapMillis = minGapMillis,
        )

    private val mutableDetails = MutableStateFlow(RouteDetailsState())

    /** Which routes' points are on the tablet, and which are being fetched or failed. */
    public val details: StateFlow<RouteDetailsState> = mutableDetails.asStateFlow()

    /** One request for a route's points at a time: the points are large and the tablet's link is a home Wi-Fi. */
    private val downloads = Mutex()
    private val resources = MutableStateFlow<Map<String, RemoteResource<Route>>>(emptyMap())

    /** The service the routes on the tablet came from: a checksum of its address, as the caches key it; null while none is set up. */
    private val source = MutableStateFlow(sourceNow())

    private fun sourceNow(): String? = config().takeIf { it.isConfigured }?.let { CacheText.sourceOf(it.baseUrl) }

    /** Reads the list's cache from disk. Call once at start, before the first [refreshList]. */
    public suspend fun load() {
        list.load()
    }

    /** Asks the service for the list if [why] allows it (see [RemoteResource.refresh]). */
    public suspend fun refreshList(why: Refresh) {
        list.refresh(why)
    }

    /**
     * The address or token in Settings were read or changed. Routes from another service are not this rider's, so a changed
     * address drops what was loaded; the list follows its own rule.
     */
    public fun settingsChanged() {
        list.settingsChanged()
        val now = sourceNow()
        // Setting a service up for the first time is not a change of service: what was read from disk for it stays.
        val before = source.getAndUpdate { now }
        if (before != null && before != now) {
            resources.value = emptyMap()
            mutableDetails.value = RouteDetailsState()
        }
    }

    /**
     * Brings the points of [ids] onto the tablet: from disk when they are there, from the service when not. Each is done once; an id
     * that is loaded is not asked for again, and one that is not an id is left out. Never throws for a failed request: the reason is in
     * [RouteDetailsState.failed].
     *
     * @param why [Refresh.REQUESTED] when the rider chose the route (a refused token is tried again), else [Refresh.START].
     */
    public suspend fun ensure(
        ids: Collection<String>,
        why: Refresh = Refresh.START,
    ) {
        for (id in ids.distinct()) ensure(id, why)
    }

    /** [ensure] for one route. */
    public suspend fun ensure(
        id: String,
        why: Refresh = Refresh.START,
    ) {
        if (!RoutesApiJson.isValidRouteId(id) || id in mutableDetails.value.downloaded) return
        downloads.withLock {
            if (id in mutableDetails.value.downloaded) return
            mutableDetails.update { it.copy(downloading = it.downloading + id, failed = it.failed - id) }
            val askedFor = sourceNow()
            val outcome = fetch(id, why)
            // The address changed while this was under way: the answer is another rider's, and what it was for is gone.
            if (sourceNow() != askedFor) return
            mutableDetails.update {
                val ready = outcome.route
                it.copy(
                    downloaded = if (ready != null) it.downloaded + (id to ready) else it.downloaded,
                    downloading = it.downloading - id,
                    failed = if (ready == null) it.failed + (id to outcome.reason) else it.failed,
                )
            }
        }
    }

    private class Fetched(
        val route: Route?,
        val reason: String,
    )

    private suspend fun fetch(
        id: String,
        why: Refresh,
    ): Fetched {
        val resource = resourceOf(id)
        resource.load()
        resource.state.value.value
            ?.takeIf { it.id == id }
            ?.let { return Fetched(it, "") }
        resource.refresh(why)
        val state = resource.state.value
        val route = state.value
        return when {
            route != null && route.id == id -> Fetched(route, "")
            route != null -> Fetched(null, ServiceProblem.NotTheService.plain)
            state.problem != null -> Fetched(null, reasonOf(state.problem))
            else -> Fetched(null, NOT_SET_UP)
        }
    }

    private fun reasonOf(problem: ServiceProblem?): String =
        when (problem?.kind) {
            null -> NOT_SET_UP
            ProblemKind.NO_SUCH_ROUTE -> "The service does not have this route"
            else -> problem.plain
        }

    private fun resourceOf(id: String): RemoteResource<Route> {
        while (true) {
            val current = resources.value
            current[id]?.let { return it }
            val made =
                RemoteResource(
                    kind = "route",
                    client = client,
                    config = config,
                    path = { "/v1/routes/$id" },
                    decode = RoutesApiJson::decodeRoute,
                    store = detailStore(id),
                    wallMillis = wallMillis,
                    elapsedMillis = elapsedMillis,
                    minGapMillis = minGapMillis,
                )
            if (resources.compareAndSet(current, current + (id to made))) return made
        }
    }

    public companion object {
        private const val NOT_SET_UP = "The service is not set up"

        /**
         * The routes the planned workouts of the days the Ready screen lists name on their `free` steps, the primary's and the easier
         * version's, in order of appearance and without repeats: what to fetch ahead so that a ride on them needs no network.
         */
        public fun routeIdsOf(
            workouts: List<PlannedWorkout>,
            today: String,
        ): List<String> {
            val agenda = PlanAgenda.of(workouts, today)
            val planned = agenda.today + agenda.thisWeek.flatMap { it.workouts }
            val ids = LinkedHashSet<String>()
            for (workout in planned) {
                collect(workout.workout.steps, ids)
                workout.variant?.let { collect(it.steps, ids) }
            }
            return ids.toList()
        }

        /** The routes the steps of one workout name, in order and without repeats. */
        public fun routeIdsOf(steps: List<Step>): List<String> =
            LinkedHashSet<String>().also { collect(steps, it) }.toList()

        private fun collect(
            steps: List<Step>,
            into: MutableSet<String>,
        ) {
            for (step in steps) {
                when (step) {
                    is FreeStep -> step.routeId?.let(into::add)
                    is RepeatStep -> step.steps.forEach { leaf -> if (leaf is FreeStep) leaf.routeId?.let(into::add) }
                    else -> Unit
                }
            }
        }
    }
}
