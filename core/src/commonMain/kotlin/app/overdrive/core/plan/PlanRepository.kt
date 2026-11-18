package app.overdrive.core.plan

import app.overdrive.core.sync.ApiErrorCode
import app.overdrive.core.sync.HttpGetRequest
import app.overdrive.core.sync.HttpGetTransport
import app.overdrive.core.sync.SyncApiJson
import app.overdrive.core.sync.SyncConfig
import app.overdrive.core.sync.TransportException
import app.overdrive.core.sync.plainReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex

/** Why a refresh happened; the rule for each is in [PlanRepository.refresh]. */
public enum class PlanRefresh {
    /** The app started. */
    START,

    /** The Ready screen came on screen. At most one a minute. */
    SCREEN_OPENED,

    /** The rider asked (the refresh button). */
    REQUESTED,

    /** The service address or token was changed in Settings. */
    SETTINGS_CHANGED,
}

/** What went wrong with the last refresh, for one line on the Ready screen. */
public sealed interface PlanProblem {
    /** The service answered 401, or the token cannot be sent. Not asked again until the address or token changes or the rider asks. */
    public data object TokenRejected : PlanProblem

    /** Anything else: [reason] is plain words ("Service unreachable"). The list on the screen, if any, is the last good one. */
    public data class Failed(
        val reason: String,
    ) : PlanProblem
}

/**
 * What the Ready screen shows of the plan.
 *
 * @property visible whether the "Planned" section appears at all. It does not when the tablet has no address and token, when
 * the service says it has no plan source (`configured: false`), and while there is nothing to say yet (no list, no problem, no
 * fetch under way). **No error is shown for what is simply not set up.**
 * @property workouts the planned workouts of the last good answer, as the service sent them (every day it covers); the screen
 * reads them through the clock with [PlanAgenda].
 * @property generatedAt when the service last read the plan from its source, UTC, or null: the age of the list.
 * @property loaded there is a good answer behind [workouts], fresh or from the cache.
 * @property stale the list may be out of date: the service said so, or the last refresh failed. Only with [loaded].
 * @property problem the last refresh failed, or null.
 * @property refreshing a request is under way.
 */
public data class PlanState(
    val visible: Boolean = false,
    val workouts: List<PlannedWorkout> = emptyList(),
    val generatedAt: String? = null,
    val loaded: Boolean = false,
    val stale: Boolean = false,
    val problem: PlanProblem? = null,
    val refreshing: Boolean = false,
)

/**
 * The planned workouts on the tablet (spec §5.1, §5.7, §8.3, ADR 0017): fetches `GET /v1/workouts` from the service the ride
 * upload talks to, keeps the last good answer in a [PlanCache], and tells the Ready screen what to show.
 *
 * - **What is asked.** `from` today to `to` today plus 13 days (the service caches a fortnight, spec §5.1), in the tablet's
 *   local dates; the address and token come from [config], read at every request, so a change in Settings needs no restart.
 * - **When.** [PlanRefresh.START] and [PlanRefresh.REQUESTED] always ask; [PlanRefresh.SCREEN_OPENED] asks only when the last
 *   request was at least a minute ago; [PlanRefresh.SETTINGS_CHANGED] always asks. One request at a time: a refresh that
 *   arrives during one is dropped, except a settings change, which is asked again once the one under way is done (it was
 *   made with the old address or token).
 * - **Offline.** A failed request keeps the last good list and says so ([PlanState.problem], [PlanState.stale]); the cache is
 *   read by [load] before the first request, so the list is there with no network and after a restart. A damaged cache reads as
 *   no cache. A cache written for another address is not used.
 * - **Nothing set up** (no address or token, or the service has no plan source) is not an error: nothing is requested and
 *   [PlanState.visible] is false.
 * - **A rejected token** (401) is remembered with the address and token it was rejected for: nothing is asked again until
 *   either changes or the rider asks ([PlanRefresh.REQUESTED]), exactly like the ride upload (ADR 0014 section 8).
 *
 * **Nothing here logs**, and the token is read only to send it: it is in no state, no problem and no message.
 *
 * @param config the current address and token.
 * @param today the tablet's local date, `YYYY-MM-DD`.
 * @param wallMillis wall-clock milliseconds, for "fetched at".
 * @param elapsedMillis a monotonic clock, for the once-a-minute rule.
 */
public class PlanRepository(
    private val config: () -> SyncConfig,
    private val transport: HttpGetTransport,
    private val cache: PlanCache,
    private val today: () -> String,
    private val wallMillis: () -> Long,
    private val elapsedMillis: () -> Long,
    private val horizonDays: Int = HORIZON_DAYS,
    private val minGapMillis: Long = MIN_GAP_MILLIS,
) {
    private class Inner(
        val known: CachedPlan? = null,
        val problem: PlanProblem? = null,
        val refreshing: Boolean = false,
    )

    private val inner = MutableStateFlow(Inner())
    private val mutableState = MutableStateFlow(PlanState())

    /** What the Ready screen shows. Updated after every change; also when the settings change ([settingsChanged]). */
    public val state: StateFlow<PlanState> = mutableState.asStateFlow()

    private val running = Mutex()
    private val askAgain = MutableStateFlow(false)

    // Touched only while holding [running].
    private var rejected: SyncConfig? = null
    private var lastAttemptAt: Long? = null

    init {
        require(
            horizonDays in 1..PlanApiJson.MAX_RANGE_DAYS,
        ) { "The horizon is 1 to ${PlanApiJson.MAX_RANGE_DAYS} days" }
    }

    /** Reads the cache from disk. Call once at start, before the first [refresh]; a missing or damaged cache is no cache. */
    public suspend fun load() {
        val plan =
            try {
                cache.read()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        inner.update { Inner(known = plan, problem = it.problem, refreshing = it.refreshing) }
        publish()
    }

    /**
     * The address or token in Settings changed: re-reads them for [state] (a section that no longer has a service disappears)
     * and forgets the last problem, which was about the old ones. The refresh that follows asks again.
     */
    public fun settingsChanged() {
        inner.update { Inner(known = it.known, problem = null, refreshing = it.refreshing) }
        publish()
    }

    /** Asks the service for the plan if [why] allows it (see the class). Returns when the request is done; never throws for a failed one. */
    public suspend fun refresh(why: PlanRefresh) {
        if (!running.tryLock()) {
            if (why == PlanRefresh.SETTINGS_CHANGED) askAgain.value = true
            return
        }
        try {
            attempt(why)
            while (askAgain.compareAndSet(true, false)) attempt(PlanRefresh.SETTINGS_CHANGED)
        } finally {
            running.unlock()
        }
    }

    private suspend fun attempt(why: PlanRefresh) {
        val settings = config()
        if (!settings.isConfigured) {
            setInner(problem = null, refreshing = false)
            return
        }
        val now = elapsedMillis()
        val last = lastAttemptAt
        if (why == PlanRefresh.SCREEN_OPENED && last != null && now - last < minGapMillis) return
        if (why == PlanRefresh.REQUESTED) rejected = null
        // A token the service could never have issued (spaces, accents) cannot be sent as a header value: say so without asking.
        if (rejected == settings || !settings.token.all { it in '!'..'~' }) {
            setInner(problem = PlanProblem.TokenRejected, refreshing = false)
            return
        }
        lastAttemptAt = now
        setInner(refreshing = true)
        try {
            val from = today()
            val to = PlanCalendar.addDays(from, horizonDays - 1)
            val url = settings.baseUrl.trimEnd('/') + "/v1/workouts?from=$from&to=$to"
            val answer =
                try {
                    transport.get(HttpGetRequest(url, settings.token, MAX_ANSWER_BYTES))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: TransportException) {
                    setInner(problem = PlanProblem.Failed(e.failure.plainReason()), refreshing = false)
                    return
                }
            when (val status = answer.status) {
                HTTP_OK -> {
                    accept(answer.body, settings)
                }

                HTTP_UNAUTHORIZED -> {
                    rejected = settings
                    setInner(problem = PlanProblem.TokenRejected, refreshing = false)
                }

                else -> {
                    setInner(problem = PlanProblem.Failed(reasonFor(status, answer.body)), refreshing = false)
                }
            }
        } finally {
            if (inner.value.refreshing) setInner(refreshing = false)
        }
    }

    /** A 200: the body must be the contract's answer, else the cache (if any) stays and the problem says it is not the service. */
    private suspend fun accept(
        body: String,
        settings: SyncConfig,
    ) {
        val response =
            try {
                PlanApiJson.decodeWorkoutsResponse(body)
            } catch (e: IllegalArgumentException) {
                setInner(problem = PlanProblem.Failed(NOT_THE_SERVICE), refreshing = false)
                return
            }
        val plan = CachedPlan(PlanCacheFormat.sourceOf(settings.baseUrl), wallMillis(), response)
        inner.update { Inner(known = plan, problem = null, refreshing = false) }
        publish()
        try {
            cache.write(plan)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The list works for now and the next request tries again; the cache is a convenience.
        }
    }

    private fun reasonFor(
        status: Int,
        body: String,
    ): String =
        when {
            status == HTTP_REQUEST_TIMEOUT -> "The service timed out"

            status == HTTP_TOO_MANY_REQUESTS -> "The service is busy"

            status in HTTP_SERVER_ERRORS -> "Service error ($status)"

            status in HTTP_REDIRECTS -> "The address redirects elsewhere"

            status == HTTP_NOT_FOUND && errorCode(
                body,
            ) == ApiErrorCode.NOT_FOUND -> "The service has no planned workouts"

            status in HTTP_CLIENT_ERRORS -> "The service refused the request ($status)"

            else -> NOT_THE_SERVICE
        }

    private fun errorCode(body: String): ApiErrorCode? =
        try {
            SyncApiJson.decodeApiError(body).code
        } catch (e: IllegalArgumentException) {
            null
        }

    private fun setInner(
        problem: PlanProblem? = inner.value.problem,
        refreshing: Boolean = inner.value.refreshing,
    ) {
        inner.update { Inner(it.known, problem, refreshing) }
        publish()
    }

    private fun publish() {
        val now = inner.value
        val settings = config()
        val plan = now.known?.takeIf { it.source == PlanCacheFormat.sourceOf(settings.baseUrl) }
        val serviceHasPlan = plan?.response?.configured != false
        val visible =
            settings.isConfigured && serviceHasPlan && (plan != null || now.problem != null || now.refreshing)
        mutableState.value =
            if (!visible) {
                PlanState()
            } else {
                PlanState(
                    visible = true,
                    workouts = plan?.response?.workouts.orEmpty(),
                    generatedAt = plan?.response?.generatedAt,
                    loaded = plan != null,
                    stale = plan != null && (plan.response.stale || now.problem != null),
                    problem = now.problem,
                    refreshing = now.refreshing,
                )
            }
    }

    public companion object {
        /** Days fetched, today included: the service caches a fortnight (spec §5.1). */
        public const val HORIZON_DAYS: Int = 14

        /** The Ready screen opening asks at most once in this long. */
        public const val MIN_GAP_MILLIS: Long = 60_000

        /** A fortnight of workouts is some hundred kilobytes at most; a longer answer is not the service's. */
        public const val MAX_ANSWER_BYTES: Int = 1 shl 20

        private const val NOT_THE_SERVICE = "That is not the Overdrive service"
        private const val HTTP_OK = 200
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_REQUEST_TIMEOUT = 408
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private val HTTP_REDIRECTS = 300..399
        private val HTTP_CLIENT_ERRORS = 400..499
        private val HTTP_SERVER_ERRORS = 500..599
    }
}
