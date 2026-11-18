package app.overdrive.core.sync

/** What a [SyncProvider] says about one upload. */
public sealed interface SyncResult {
    /** The ride is safe at the destination: the outbox forgets it. */
    public data object Acknowledged : SyncResult

    /** It did not go through for now (no network, a server error): keep it and try again later, with backoff. */
    public data class RetryLater(
        val reason: String? = null,
    ) : SyncResult

    /** There is nowhere to upload to (no URL, no token): keep everything and wait for a [SyncCoordinator.kick]. */
    public data object NotConfigured : SyncResult

    /**
     * The destination does not accept the credentials (HTTP 401, ADR 0014 section 6). Retrying with the same address and token
     * cannot help, so the coordinator stops without backing off, keeps every ride, and waits for a [SyncCoordinator.kick]:
     * a changed setting or the rider's **Send now**.
     */
    public data object TokenRejected : SyncResult

    /**
     * The destination refused **this ride** for good (a 4xx other than 401, ADR 0014 section 6): sending it again as it is
     * will not change the answer. [message] is the destination's short reason in plain words. The coordinator keeps the ride
     * in the outbox, remembers the refusal for the Settings line, **does not retry it in a loop** and carries on with the
     * rides behind it; a changed setting or **Send now** tries it once more.
     */
    public data class Refused(
        val message: String,
    ) : SyncResult
}

/**
 * Where finished rides go (spec §4.9). Phase 1 had only [NoopSyncProvider]; Phase 2 adds [HttpSyncProvider], which talks to
 * the service, and the ride code never changed.
 */
public interface SyncProvider {
    /** A short name for the log and the settings screen. */
    public val name: String

    /** Uploads the ride of [entry]. May throw: the coordinator treats a throw as [SyncResult.RetryLater]. */
    public suspend fun upload(entry: OutboxEntry): SyncResult
}

/**
 * The Phase 1 provider: it uploads nothing and **acknowledges nothing**, so every entry stays in the outbox, ready for the
 * day a real provider is configured.
 */
public object NoopSyncProvider : SyncProvider {
    override val name: String = "No sync"

    override suspend fun upload(entry: OutboxEntry): SyncResult = SyncResult.NotConfigured
}
