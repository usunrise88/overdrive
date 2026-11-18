package app.overdrive.core.remote

import app.overdrive.core.sync.SyncConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** Why a refresh happened; the rule for each is in [RemoteResource.refresh]. */
public enum class Refresh {
    /** The app started. */
    START,

    /** A screen came on screen. At most one a minute per resource. */
    SCREEN_OPENED,

    /** The rider asked (pull down, Try again, Sync now). Always asks, and tries a refused token once more. */
    REQUESTED,

    /** The service address or token was changed in Settings. */
    SETTINGS_CHANGED,
}

/**
 * What a screen reads of one resource of the service.
 *
 * @property loaded the cache has been read (and found or not): until then "nothing cached" cannot be said.
 * @property value the last good answer, fresh or from the cache; null when there is none for the address in Settings.
 * @property fetchedAtMillis when the phone got [value] (its wall clock): "Updated 07:40".
 * @property problem the last request failed, or null. With a [value] the screen is stale; without one it is an error block.
 * @property refreshing a request is under way.
 * @property asked a request has had an outcome since the app started, so a screen with no [value] and no [problem] is
 * still loading and not empty.
 */
public data class ResourceState<out T>(
    val loaded: Boolean = false,
    val value: T? = null,
    val fetchedAtMillis: Long? = null,
    val problem: ServiceProblem? = null,
    val refreshing: Boolean = false,
    val asked: Boolean = false,
) {
    /** Nothing to draw yet: neither an answer nor a problem, and a request is still to come or under way. */
    public val isLoading: Boolean get() = value == null && problem == null && (!asked || refreshing || !loaded)

    /** The screen shows an answer the last refresh could not renew. */
    public val isStale: Boolean get() = value != null && problem != null
}

/**
 * One answer of the service (`GET path`) kept on the phone (spec §6.1, ADR 0024): fetched when a screen opens and when the
 * rider asks, **kept on disk** in a [TextStore] so the screen draws at once and offline, and told to the screens as a
 * [ResourceState]. The structure follows the ride app's plan and profile repositories (ADR 0017, ADR 0021).
 *
 * - **When.** [Refresh.START] and [Refresh.REQUESTED] always ask; [Refresh.SCREEN_OPENED] asks only when the last request was at
 *   least [minGapMillis] ago; [Refresh.SETTINGS_CHANGED] asks unless the last request was made with these very address and token.
 *   One request at a time: a refresh that arrives during one is dropped, except a settings change, which is asked again once the
 *   one under way is done (it was made with the old address or token).
 * - **Offline.** A failed request keeps the last good answer and says why ([ResourceState.problem]); [load] reads the cache first,
 *   so the answer is there with no network and after a restart. A damaged cache reads as no cache, and so does a cache written
 *   for another address, or one that is not a valid answer of the contract.
 * - **The cache is written before the answer is published**, so a screen that has seen a new answer can rely on it being on
 *   disk too (a restart right after cannot lose it), and a cancellation cannot cut the write: the file is replaced atomically,
 *   whole or not at all.
 * - **Nothing set up** is not an error: nothing is requested and [ResourceState.problem] stays null.
 *
 * **Nothing here logs**, and the token is read only by the [client] to send it.
 *
 * @param kind what the answer is, for the cache's header ("metrics-26w").
 * @param path the path and query of the request, evaluated at every request (a range follows the clock).
 * @param decode reads a body, throwing [IllegalArgumentException] for anything that is not the contract's answer.
 * @param store the cache, or null for none.
 */
public class RemoteResource<T : Any>(
    private val kind: String,
    private val client: ServiceClient,
    private val config: () -> SyncConfig,
    private val path: () -> String,
    private val decode: (String) -> T,
    private val store: TextStore?,
    private val wallMillis: () -> Long,
    private val elapsedMillis: () -> Long,
    private val maxBytes: Int = DEFAULT_MAX_BYTES,
    private val minGapMillis: Long = MIN_GAP_MILLIS,
) {
    private class Known<T>(
        val source: String,
        val fetchedAtMillis: Long,
        val value: T,
    )

    private data class Inner<T>(
        val known: Known<T>? = null,
        val problem: ServiceProblem? = null,
        val refreshing: Boolean = false,
        val loaded: Boolean = false,
        val asked: Boolean = false,
    )

    private val inner = MutableStateFlow(Inner<T>())
    private val mutableState = MutableStateFlow(ResourceState<T>())

    /** What a screen shows. Updated after every change, and when the settings change ([settingsChanged]). */
    public val state: StateFlow<ResourceState<T>> = mutableState.asStateFlow()

    private val running = Mutex()
    private val askAgain = MutableStateFlow(false)

    /** The address and token of the last request that got an outcome (an answer or no answer); not one that was cancelled. */
    private val lastAsked = MutableStateFlow<SyncConfig?>(null)

    // Touched only while holding [running].
    private var lastAttemptAt: Long? = null

    /** Reads the cache from disk. Call once at start, before the first [refresh]; a missing or damaged cache is no cache. */
    public suspend fun load() {
        val known = readCache()
        inner.update { it.copy(known = it.known ?: known, loaded = true) }
        publish()
    }

    /**
     * The address or token in Settings were read or changed: re-reads them for [state] (an answer of another address is no
     * longer shown) and forgets the last problem when it was about other settings than these. The refresh that follows asks again.
     */
    public fun settingsChanged() {
        val aboutThese = config() == lastAsked.value
        inner.update { if (aboutThese) it else it.copy(problem = null) }
        publish()
    }

    /**
     * Asks the service if [why] allows it (see the class). Returns when the request is done; never throws for a failed one.
     *
     * **A settings change is never lost.** It is written down first ([askAgain]) and only then is the lock tried, and whoever
     * lets go of the lock looks at it once more.
     */
    public suspend fun refresh(why: Refresh) {
        if (why == Refresh.SETTINGS_CHANGED) askAgain.value = true
        var pending: Refresh? = if (why == Refresh.SETTINGS_CHANGED) null else why
        while (true) {
            if (!running.tryLock()) return
            try {
                pending?.let { attempt(it) }
                pending = null
                while (askAgain.compareAndSet(true, false)) attempt(Refresh.SETTINGS_CHANGED)
            } finally {
                running.unlock()
            }
            if (!askAgain.value) return
        }
    }

    private suspend fun attempt(why: Refresh) {
        val settings = config()
        if (!settings.isConfigured) {
            update(problem = null, refreshing = false)
            return
        }
        if (why == Refresh.SETTINGS_CHANGED && settings == lastAsked.value) {
            publish()
            return
        }
        val now = elapsedMillis()
        val last = lastAttemptAt
        if (why == Refresh.SCREEN_OPENED && last != null && now - last < minGapMillis) return
        if (why == Refresh.REQUESTED) client.forgetRejection()
        lastAttemptAt = now
        update(refreshing = true)
        try {
            when (val outcome = client.get(path(), maxBytes, decode)) {
                is Outcome.Ok -> {
                    lastAsked.value = settings
                    accept(outcome, settings)
                }

                is Outcome.Failed -> {
                    lastAsked.value = settings
                    update(problem = outcome.problem, refreshing = false, asked = true)
                }

                Outcome.NotSetUp -> {
                    update(problem = null, refreshing = false)
                }
            }
        } finally {
            if (inner.value.refreshing) update(refreshing = false)
        }
    }

    private suspend fun accept(
        outcome: Outcome.Ok<T>,
        settings: SyncConfig,
    ) {
        val source = CacheText.sourceOf(settings.baseUrl)
        val fetchedAt = wallMillis()
        val target = store
        if (target != null) {
            // Written before it is published, and not cut short by a cancellation: see the class.
            withContext(NonCancellable) {
                try {
                    target.write(CacheText.encode(kind, CachedBody(source, fetchedAt, outcome.body)))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The answer works for now and the next request tries again; the cache is a convenience.
                }
            }
        }
        inner.update {
            it.copy(known = Known(source, fetchedAt, outcome.value), problem = null, refreshing = false, asked = true)
        }
        publish()
    }

    private suspend fun readCache(): Known<T>? {
        val text =
            try {
                store?.read()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } ?: return null
        val cached = CacheText.decode(kind, text) ?: return null
        val value =
            try {
                decode(cached.body)
            } catch (e: IllegalArgumentException) {
                return null
            }
        return Known(cached.source, cached.fetchedAtMillis, value)
    }

    private fun update(
        problem: ServiceProblem? = inner.value.problem,
        refreshing: Boolean = inner.value.refreshing,
        asked: Boolean = inner.value.asked,
    ) {
        inner.update { it.copy(problem = problem, refreshing = refreshing, asked = asked) }
        publish()
    }

    private fun publish() {
        val now = inner.value
        val settings = config()
        val known = now.known?.takeIf { settings.isConfigured && it.source == CacheText.sourceOf(settings.baseUrl) }
        mutableState.value =
            ResourceState(
                loaded = now.loaded,
                value = known?.value,
                fetchedAtMillis = known?.fetchedAtMillis,
                problem = now.problem.takeIf { settings.isConfigured },
                refreshing = now.refreshing,
                asked = now.asked,
            )
    }

    public companion object {
        /** A screen opening asks at most once in this long. */
        public const val MIN_GAP_MILLIS: Long = 60_000

        /** A year of daily figures, the 25-point curves and a few hundred weekly rows are some 150 KB; a ride's series less than 100 KB. */
        public const val DEFAULT_MAX_BYTES: Int = 1 shl 20
    }
}
