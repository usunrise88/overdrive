package app.overdrive.core.profile

import app.overdrive.core.sync.HttpGetRequest
import app.overdrive.core.sync.HttpGetTransport
import app.overdrive.core.sync.HttpPatchRequest
import app.overdrive.core.sync.HttpPatchTransport
import app.overdrive.core.sync.NOT_THE_SERVICE
import app.overdrive.core.sync.SyncConfig
import app.overdrive.core.sync.TransportException
import app.overdrive.core.sync.plainReason
import app.overdrive.core.sync.reasonForStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex

/** Why a refresh happened; the rule for each is in [ProfileRepository.refresh]. */
public enum class ProfileRefresh {
    /** The app started. */
    START,

    /** The Ready screen came on screen. At most one a minute. */
    SCREEN_OPENED,

    /** The rider asked (the refresh button). */
    REQUESTED,

    /** The service address or token was changed in Settings. */
    SETTINGS_CHANGED,

    /** The rider changed a value of the profile on the tablet ([ProfileRepository.edit]): send it. */
    EDITED,
}

/** What went wrong with the last request, for one line in Settings. */
public sealed interface ProfileProblem {
    /** The service answered 401, or the token cannot be sent. Not asked again until the address or token changes or the rider asks. */
    public data object TokenRejected : ProfileProblem

    /** Anything else: [reason] is plain words ("Service unreachable"). The profile in force, if any, is the last good one. */
    public data class Failed(
        val reason: String,
    ) : ProfileProblem
}

/**
 * What the app reads of the rider's profile.
 *
 * @property ready the cache has been read (or found to be none): until then nothing is known and a ride built now would use the
 * tablet's own values, so whoever builds one waits for this.
 * @property profile the profile **in force**: the service's last answer (fresh, or from the cache after a restart) with the
 * tablet's unsent edit on top; null when there is none for the address in Settings (nothing set up, or the service was never
 * reached), in which case the tablet's own values apply exactly as they did before there was a profile.
 * @property pending the edit the rider made on the tablet that the service has not taken yet, or null: what Settings says "Not
 * sent yet" about. Already part of [profile].
 * @property problem the last request failed, or null.
 * @property refreshing a request is under way.
 */
public data class ProfileState(
    val ready: Boolean = false,
    val profile: Profile? = null,
    val pending: ProfilePatch? = null,
    val problem: ProfileProblem? = null,
    val refreshing: Boolean = false,
)

/**
 * The rider's profile on the tablet (spec §8.3, ADR 0021): reads `GET /v1/profile` from the service the ride upload talks to,
 * keeps the last good answer in a [ProfileCache], sends the rider's edits with `PATCH /v1/profile`, and tells the app which
 * profile is in force. Same ground rules as the plan's repository (ADR 0017), whose structure it follows.
 *
 * - **When it reads.** [ProfileRefresh.START] and [ProfileRefresh.REQUESTED] always ask; [ProfileRefresh.SCREEN_OPENED] asks only
 *   when the last request was at least a minute ago; [ProfileRefresh.SETTINGS_CHANGED] asks unless the last request was made with
 *   these very address and token. One request at a time: a refresh that arrives during one is dropped, except a settings
 *   change and an edit, which are made once the one under way is done (it was made without them).
 * - **Offline.** A failed request keeps the profile in force and says so ([ProfileState.problem]); the cache is read by [load]
 *   before the first request, so the profile is there with no network and after a restart. A damaged cache reads as no cache. A
 *   cache written for another address is not used. With no address or token, or while the service was never reached and there
 *   is no cache, [ProfileState.profile] is null and nothing is asked or said: **the tablet's own values apply**.
 * - **Edits.** [edit] puts the rider's change on top of the profile in force at once (so the numbers move as the rider taps) and
 *   [refresh] with [ProfileRefresh.EDITED] sends it. What is unsent is one patch, the edits merged with the last write winning
 *   per field, written to the cache **before** it is sent, so it survives a restart and an offline spell; every refresh sends it
 *   first and, when it got through, the answer to the `PATCH` (the whole profile) is the fresh profile and no `GET` follows.
 *   A service that refuses the edit (a 4xx other than 401, 408, 429) loses it: the profile in force goes back to the service's.
 * - **A rejected token** (401) is remembered with the address and token it was rejected for: nothing is asked again until either
 *   changes or the rider asks ([ProfileRefresh.REQUESTED]), exactly like the plan and the ride upload (ADR 0014 section 8).
 *
 * **Nothing here logs**, and the token is read only to send it: it is in no state, no problem and no message.
 *
 * @param config the current address and token, read at every request.
 * @param elapsedMillis a monotonic clock, for the once-a-minute rule.
 */
public class ProfileRepository(
    private val config: () -> SyncConfig,
    private val reader: HttpGetTransport,
    private val writer: HttpPatchTransport,
    private val cache: ProfileCache,
    private val elapsedMillis: () -> Long,
    private val minGapMillis: Long = MIN_GAP_MILLIS,
) {
    private data class Inner(
        val known: CachedProfile? = null,
        val problem: ProfileProblem? = null,
        val refreshing: Boolean = false,
        val ready: Boolean = false,
        /** Counts the changes of [known], so what is on disk can be told from what is in memory. */
        val version: Int = 0,
    )

    private enum class Sent { NOTHING_TO_SEND, SENT, REFUSED, FAILED }

    private val inner = MutableStateFlow(Inner())
    private val mutableState = MutableStateFlow(ProfileState())

    /** What the app reads. Updated after every change; also when the settings change ([settingsChanged]). */
    public val state: StateFlow<ProfileState> = mutableState.asStateFlow()

    private val running = Mutex()
    private val askAgain = MutableStateFlow(false)
    private val sendAgain = MutableStateFlow(false)

    /** The address and token of the last request that got an outcome (an answer or no answer); not one that was cancelled. */
    private val lastAsked = MutableStateFlow<SyncConfig?>(null)

    // Touched only while holding [running].
    private var rejected: SyncConfig? = null
    private var lastAttemptAt: Long? = null
    private var persistedVersion = 0

    /**
     * Reads the cache from disk. Call once at start, before the first [refresh]; a missing or damaged cache is no cache. Either
     * way [ProfileState.ready] is true afterwards.
     */
    public suspend fun load() {
        val cached =
            try {
                cache.read()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        inner.update { it.copy(known = it.known ?: cached, ready = true) }
        publish()
    }

    /**
     * The address or token in Settings were read or changed: re-reads them for [state] (a profile of another address is no longer
     * in force) and forgets the last problem when it was about other settings than these. The refresh that follows asks again.
     */
    public fun settingsChanged() {
        val aboutThese = config() == lastAsked.value
        inner.update { if (aboutThese) it else it.copy(problem = null) }
        publish()
    }

    /**
     * The rider changed a value on the tablet: puts [patch] on top of the profile in force and writes it down as unsent, so the
     * new value is in [state] at once, and asks for it to be sent (the caller then calls [refresh] with [ProfileRefresh.EDITED];
     * an edit is not lost if a send is under way, which sends again afterwards).
     *
     * @return false when there is no profile in force: the tablet then keeps the change in its own settings, as it did before
     * there was a profile.
     * @throws IllegalArgumentException when [patch] would not make a valid profile (the tablet's own ranges are inside the
     * profile's, so this is a mistake of the caller).
     */
    public fun edit(patch: ProfilePatch): Boolean {
        var accepted = false
        inner.update { current ->
            val known = current.known
            if (known == null || !isInForce(known, config())) {
                accepted = false
                return@update current
            }
            accepted = true
            if (patch.isEmpty) return@update current
            val merged = known.pending?.andThen(patch) ?: patch
            merged.applyTo(known.profile)
            current.copy(known = CachedProfile(known.source, known.profile, merged), version = current.version + 1)
        }
        if (accepted && !patch.isEmpty) {
            sendAgain.value = true
            publish()
        }
        return accepted
    }

    /**
     * Sends what is unsent and asks the service for the profile if [why] allows it (see the class). Returns when the request is
     * done; never throws for a failed one.
     *
     * **A settings change and an edit are never lost.** Each is written down first ([askAgain], [sendAgain]) and only then is the
     * lock tried, and whoever lets go of the lock looks at them once more: one that arrives while a request is under way, or in
     * the instant between that request's last look and its letting go, is made by the holder or by whoever takes the lock next.
     */
    public suspend fun refresh(why: ProfileRefresh) {
        when (why) {
            ProfileRefresh.SETTINGS_CHANGED -> askAgain.value = true
            ProfileRefresh.EDITED -> sendAgain.value = true
            else -> Unit
        }
        var first: ProfileRefresh? = why.takeIf { it != ProfileRefresh.SETTINGS_CHANGED && it != ProfileRefresh.EDITED }
        while (true) {
            if (!running.tryLock()) return
            try {
                first?.let { attempt(it) }
                first = null
                while (true) {
                    if (sendAgain.compareAndSet(true, false)) {
                        attempt(ProfileRefresh.EDITED)
                    } else if (askAgain.compareAndSet(true, false)) {
                        attempt(ProfileRefresh.SETTINGS_CHANGED)
                    } else {
                        break
                    }
                }
            } finally {
                running.unlock()
            }
            if (!askAgain.value && !sendAgain.value) return
        }
    }

    private suspend fun attempt(why: ProfileRefresh) {
        // Before anything else: what is unsent is on disk, whatever the rules below decide.
        persist()
        val settings = config()
        if (!settings.isConfigured) {
            setInner(problem = null, refreshing = false)
            return
        }
        // The settings are the ones the last request was made with: nothing changed, so there is nothing to ask again.
        if (why == ProfileRefresh.SETTINGS_CHANGED && settings == lastAsked.value) {
            publish()
            return
        }
        if (why == ProfileRefresh.EDITED && unsentFor(settings) == null) return
        val now = elapsedMillis()
        val last = lastAttemptAt
        if (why == ProfileRefresh.SCREEN_OPENED && last != null && now - last < minGapMillis) return
        if (why == ProfileRefresh.REQUESTED) rejected = null
        // A token the service could never have issued (spaces, accents) cannot be sent as a header value: say so without asking.
        if (rejected == settings || !settings.token.all { it in '!'..'~' }) {
            setInner(problem = ProfileProblem.TokenRejected, refreshing = false)
            return
        }
        lastAttemptAt = now
        setInner(refreshing = true)
        try {
            when (sendUnsent(settings)) {
                Sent.FAILED, Sent.SENT -> return

                // Nothing to send, or the service refused what was: the profile in force is the service's, so read it.
                Sent.NOTHING_TO_SEND, Sent.REFUSED -> if (why != ProfileRefresh.EDITED) read(settings)
            }
        } finally {
            if (inner.value.refreshing) setInner(refreshing = false)
        }
    }

    /** The unsent edit made on the profile of [settings]'s address, or null. */
    private fun unsentFor(settings: SyncConfig): ProfilePatch? =
        inner.value.known?.takeIf { isInForce(it, settings) }?.pending

    private suspend fun sendUnsent(settings: SyncConfig): Sent {
        var sentAny = false
        while (true) {
            val known = inner.value.known?.takeIf { isInForce(it, settings) }
            val patch = known?.pending
            if (known == null || patch == null) return if (sentAny) Sent.SENT else Sent.NOTHING_TO_SEND
            val answer =
                try {
                    writer.patch(
                        HttpPatchRequest(
                            profileUrl(settings),
                            settings.token,
                            ProfileApiJson.encodePatch(patch),
                            MAX_ANSWER_BYTES,
                        ),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: TransportException) {
                    lastAsked.value = settings
                    setInner(problem = ProfileProblem.Failed(e.plainReason()), refreshing = false)
                    return Sent.FAILED
                }
            lastAsked.value = settings
            val status = answer.status
            when {
                status == HTTP_OK -> {
                    val profile =
                        try {
                            ProfileApiJson.decodeProfile(answer.body)
                        } catch (e: IllegalArgumentException) {
                            setInner(problem = ProfileProblem.Failed(NOT_THE_SERVICE), refreshing = false)
                            return Sent.FAILED
                        }
                    // What was sent is now the service's; an edit made while it was on its way stays unsent.
                    inner.update { current ->
                        val now = current.known
                        if (now == null || now.source != known.source) {
                            current
                        } else {
                            current.copy(
                                known = CachedProfile(now.source, profile, now.pending?.takeIf { it != patch }),
                                problem = null,
                                version = current.version + 1,
                            )
                        }
                    }
                    publish()
                    persist()
                    sentAny = true
                }

                status == HTTP_UNAUTHORIZED -> {
                    rejected = settings
                    setInner(problem = ProfileProblem.TokenRejected, refreshing = false)
                    return Sent.FAILED
                }

                isRefusal(status) -> {
                    // The service will not take this change however often it is sent: drop it, say so, and go back to the service's.
                    inner.update { current ->
                        val now = current.known
                        if (now == null || now.source != known.source) {
                            current
                        } else {
                            current.copy(
                                known = CachedProfile(now.source, now.profile, null),
                                version = current.version + 1,
                            )
                        }
                    }
                    setInner(problem = ProfileProblem.Failed(reasonFor(status, answer.body)), refreshing = false)
                    persist()
                    return Sent.REFUSED
                }

                else -> {
                    setInner(problem = ProfileProblem.Failed(reasonFor(status, answer.body)), refreshing = false)
                    return Sent.FAILED
                }
            }
        }
    }

    private suspend fun read(settings: SyncConfig) {
        val answer =
            try {
                reader.get(HttpGetRequest(profileUrl(settings), settings.token, MAX_ANSWER_BYTES))
            } catch (e: CancellationException) {
                throw e
            } catch (e: TransportException) {
                lastAsked.value = settings
                setInner(problem = ProfileProblem.Failed(e.plainReason()), refreshing = false)
                return
            }
        lastAsked.value = settings
        when (val status = answer.status) {
            HTTP_OK -> {
                accept(answer.body, settings)
            }

            HTTP_UNAUTHORIZED -> {
                rejected = settings
                setInner(problem = ProfileProblem.TokenRejected, refreshing = false)
            }

            else -> {
                setInner(problem = ProfileProblem.Failed(reasonFor(status, answer.body)), refreshing = false)
            }
        }
    }

    /** A 200: the body must be the contract's profile, else what there is stays and the problem says it is not the service. */
    private suspend fun accept(
        body: String,
        settings: SyncConfig,
    ) {
        val profile =
            try {
                ProfileApiJson.decodeProfile(body)
            } catch (e: IllegalArgumentException) {
                setInner(problem = ProfileProblem.Failed(NOT_THE_SERVICE), refreshing = false)
                return
            }
        val source = ProfileCacheFormat.sourceOf(settings.baseUrl)
        inner.update { current ->
            // An edit made while the request was on its way stays unsent (unless it is no longer valid on the new profile).
            val pending =
                current.known?.takeIf { it.source == source }?.pending?.takeIf {
                    try {
                        it.applyTo(profile)
                        true
                    } catch (e: IllegalArgumentException) {
                        false
                    }
                }
            current.copy(
                known = CachedProfile(source, profile, pending),
                problem = null,
                refreshing = false,
                version = current.version + 1,
            )
        }
        publish()
        persist()
    }

    /** Writes what is in memory to the cache when it differs from what was last written; a failure costs the cache and nothing else. */
    private suspend fun persist() {
        val current = inner.value
        val known = current.known ?: return
        if (current.version == persistedVersion) return
        try {
            cache.write(known)
            persistedVersion = current.version
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The profile works for now and the next change or refresh tries again; the cache is a convenience.
        }
    }

    private fun isInForce(
        known: CachedProfile,
        settings: SyncConfig,
    ): Boolean = settings.isConfigured && known.source == ProfileCacheFormat.sourceOf(settings.baseUrl)

    private fun profileUrl(settings: SyncConfig): String = settings.baseUrl.trimEnd('/') + "/v1/profile"

    private fun isRefusal(status: Int): Boolean =
        status in HTTP_CLIENT_ERRORS && status != HTTP_REQUEST_TIMEOUT && status != HTTP_TOO_MANY_REQUESTS

    private fun reasonFor(
        status: Int,
        body: String,
    ): String =
        if (status == HTTP_UNPROCESSABLE) {
            "The service refused the change ($status)"
        } else {
            reasonForStatus(status, body, noRoute = "The service has no profile")
        }

    private fun setInner(
        problem: ProfileProblem? = inner.value.problem,
        refreshing: Boolean = inner.value.refreshing,
    ) {
        inner.update { it.copy(problem = problem, refreshing = refreshing) }
        publish()
    }

    private fun publish() {
        val now = inner.value
        val settings = config()
        val known = now.known?.takeIf { isInForce(it, settings) }
        mutableState.value =
            ProfileState(
                ready = now.ready,
                profile = known?.effective(),
                pending = known?.pending,
                problem = if (settings.isConfigured) now.problem else null,
                refreshing = settings.isConfigured && now.refreshing,
            )
    }

    public companion object {
        /** The Ready screen opening asks at most once in this long. */
        public const val MIN_GAP_MILLIS: Long = 60_000

        /** A profile is a few hundred bytes; a longer answer is not the service's. */
        public const val MAX_ANSWER_BYTES: Int = 64 * 1024

        private const val HTTP_OK = 200
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_REQUEST_TIMEOUT = 408
        private const val HTTP_UNPROCESSABLE = 422
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private val HTTP_CLIENT_ERRORS = 400..499
    }
}
