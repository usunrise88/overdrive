package app.overdrive.core.remote

import app.overdrive.core.repeats.RepeatDetailResponse
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
