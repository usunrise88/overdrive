package app.overdrive.core.sync

/** What a [SyncProvider] says about one upload. */
public sealed interface SyncResult {
    /** The ride is safe at the destination: the outbox forgets it. */
    public data object Acknowledged : SyncResult

    /** It did not go through for now (no network, a server error): keep it and try again later, with backoff. */
    public data class RetryLater(
        val reason: String? = null,
    ) : SyncResult

    /** There is nowhere to upload to (no URL, no token, Phase 1): keep everything and wait for a [SyncCoordinator.kick]. */
    public data object NotConfigured : SyncResult
}

/**
 * Where finished rides go (spec §4.9). Phase 1 has only [NoopSyncProvider]; Phase 2 adds the one that talks to the service,
 * and the ride code never changes. **There is no network code and no payload format here**: what [upload] sends and how is
 * Phase 2's (spec §5, §8.3), and the result type may grow with it.
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
