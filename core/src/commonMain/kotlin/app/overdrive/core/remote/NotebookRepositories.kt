package app.overdrive.core.remote

import app.overdrive.core.notebook.NotebookApiJson
import app.overdrive.core.notebook.NotebookResponse
import app.overdrive.core.sync.SyncConfig

/*
 * What the phone reads of the coach notebook (spec §11.6, ADR 0035): `GET /v1/notebook`, read-only. A [RemoteResource] of ADR 0024, the same
 * as the repeated sessions (ADR 0028): read from disk first, asked when the page opens or the rider pulls down, kept on disk, offline-safe.
 * Nothing here writes to the service: the companion has no way to edit the notebook.
 */

/** `GET /v1/notebook`: the coach notebook as the service has it now, with the cause in words when the cache is old. */
public fun notebookResource(
    client: ServiceClient,
    config: () -> SyncConfig,
    store: TextStore?,
    wallMillis: () -> Long,
    elapsedMillis: () -> Long,
    minGapMillis: Long = RemoteResource.MIN_GAP_MILLIS,
): RemoteResource<NotebookResponse> =
    RemoteResource(
        kind = "notebook",
        client = client,
        config = config,
        path = { "/v1/notebook" },
        decode = NotebookApiJson::decodeNotebook,
        store = store,
        wallMillis = wallMillis,
        elapsedMillis = elapsedMillis,
        minGapMillis = minGapMillis,
    )
