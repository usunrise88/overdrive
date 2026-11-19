package app.overdrive.core.remote

import app.overdrive.core.repeats.RepeatDetailResponse
import app.overdrive.core.repeats.RepeatPreviousAttempt
import app.overdrive.core.repeats.RepeatsApiJson
import app.overdrive.core.repeats.RepeatsResponse
import app.overdrive.core.repeats.isRepeatHash
import app.overdrive.core.sync.SyncConfig
import kotlinx.coroutines.flow.MutableStateFlow

/*
 * What the phone reads of the repeated sessions (spec §11.7, §8.3, ADR 0026, ADR 0028): the list (`GET /v1/repeats`) and one session with
 * every attempt side by side (`GET /v1/repeats/{hash}`). Each is a [RemoteResource] of ADR 0024: read from disk first, asked when a
 * screen opens or the rider pulls down, kept on disk, offline-safe. The phone computes nothing of what they say: the heart rate at the
 * same power, the drift, the compliance and the trends are the service's, and a screen only words them.
 */

/** `GET /v1/repeats`: the sessions ridden at least twice, most recently ridden first, each with its latest attempt against the previous. */
public fun repeatsResource(
    client: ServiceClient,
    config: () -> SyncConfig,
    store: TextStore?,
    wallMillis: () -> Long,
    elapsedMillis: () -> Long,
    minGapMillis: Long = RemoteResource.MIN_GAP_MILLIS,
): RemoteResource<RepeatsResponse> =
    RemoteResource(
        kind = "repeats",
        client = client,
        config = config,
        path = { "/v1/repeats" },
        decode = RepeatsApiJson::decodeRepeats,
        store = store,
        wallMillis = wallMillis,
        elapsedMillis = elapsedMillis,
        minGapMillis = minGapMillis,
    )

/**
 * `GET /v1/repeats/{hash}`: one repeated session as the detail page shows it, a [RemoteResource] per hash, each cached by its hash, so a
 * session that was opened once opens offline. Only the most recent few are kept in memory (the list on disk is bounded by the caller's
 * store: `FileTextStores` keeps a bounded folder).
 *
 * @param storeFor the cache of one session by its hash, or null for none.
 */
public class RepeatDetails(
    private val client: ServiceClient,
    private val config: () -> SyncConfig,
    private val storeFor: (String) -> TextStore?,
    private val wallMillis: () -> Long,
    private val elapsedMillis: () -> Long,
    private val keep: Int = KEEP_IN_MEMORY,
    private val minGapMillis: Long = RemoteResource.MIN_GAP_MILLIS,
) {
    private val resources = MutableStateFlow<Map<String, RemoteResource<RepeatDetailResponse>>>(emptyMap())

    /** The resource of the session [hash], made on first use. @throws IllegalArgumentException if [hash] is not a session hash. */
    public fun resource(hash: String): RemoteResource<RepeatDetailResponse> {
        require(isRepeatHash(hash)) { "Not a session hash" }
        while (true) {
            val current = resources.value
            current[hash]?.let { return it }
            val made =
                RemoteResource(
                    kind = "repeat-detail",
                    client = client,
                    config = config,
                    path = { "/v1/repeats/$hash" },
                    decode = RepeatsApiJson::decodeDetail,
                    store = storeFor(hash),
                    wallMillis = wallMillis,
                    elapsedMillis = elapsedMillis,
                    minGapMillis = minGapMillis,
                )
            val all = current + (hash to made)
            val next = all.entries.drop(maxOf(0, all.size - keep)).associate { it.toPair() }
            if (resources.compareAndSet(current, next)) return made
        }
    }

    public fun settingsChanged() {
        for (resource in resources.value.values) resource.settingsChanged()
    }

    private companion object {
        const val KEEP_IN_MEMORY = 6
    }
}

/** The most the previous-attempt answer may hold: it carries one attempt's steps, a few kilobytes at most. */
public const val PREVIOUS_ATTEMPT_MAX_BYTES: Int = 64 * 1024

/**
 * `GET /v1/repeats/{hash}/previous` for the overlay's line against the previous attempt (spec §11.7, docs/adr/0034): the attempt before
 * the one that is starting, with its heart rate at the same power per step. **One request, no retry, no cache on disk** (the ride keeps
 * the answer in memory); the ride never waits for it. It is null for everything that is not a clean answer with an attempt in it: no
 * address or token, no network, a service that is down or has no such route, a refused token, a 404 (an unknown session or ride), a body
 * that is not the contract's, and an answer with no earlier attempt. The overlay then simply has no line; nothing is said and nothing fails.
 *
 * [hash] is the service's structure hash of the planned workout (`PlannedWorkout.structureHash`): the tablet never computes one, and a
 * workout without one has no line. No `before` is sent: the ride that is starting is not stored on the service yet, so the latest
 * attempt of all is the one before it.
 */
public suspend fun previousAttemptOf(
    client: ServiceClient,
    hash: String,
): RepeatPreviousAttempt? {
    if (!isRepeatHash(hash)) return null
    return when (
        val outcome =
            client.get(
                "/v1/repeats/$hash/previous",
                PREVIOUS_ATTEMPT_MAX_BYTES,
                RepeatsApiJson::decodePrevious,
            )
    ) {
        is Outcome.Ok -> outcome.value.previous?.takeIf { outcome.value.hash == hash }
        is Outcome.Failed, Outcome.NotSetUp -> null
    }
}
