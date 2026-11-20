package app.overdrive.core.live

import app.overdrive.core.remote.ServiceClient

/**
 * The live coach's requests to the service (docs/adr/0038, 0039), **through the one [ServiceClient]** the rest of the app uses: its address and device
 * token read at every request, its timeouts, its remembered rejection of a token and its silence about secrets. Nothing here opens a connection or knows
 * a header; each call is a path, a body and what the answer comes to. None throws: a failure is an answer ([LiveStartAnswer.Failed],
 * [LiveSnapshotAnswer.Failed]), and the calls whose answer nobody needs return nothing.
 *
 * The session id is the service's and is checked to be 1 to 64 characters of `A-Z a-z 0-9 _ -` when it is read (`LiveSessionStartResponse`), so it can
 * stand in a path as it is.
 */
public class LiveCoachClient(
    private val client: ServiceClient,
) {
    /** `POST /v1/live/sessions`: asks for a session. */
    public suspend fun start(request: LiveSessionStart): LiveStartAnswer =
        LiveStartAnswer.of(client.post(SESSIONS, LiveCoachJson.encodeSessionStart(request), MAX_ANSWER_BYTES))

    /** `POST /v1/live/sessions/{id}/snapshots`: the picture of the ride now. A `404` is [LiveSnapshotAnswer.SessionGone]. */
    public suspend fun snapshot(
        sessionId: String,
        snapshot: LiveSnapshot,
    ): LiveSnapshotAnswer =
        LiveSnapshotAnswer.of(
            client.post("$SESSIONS/$sessionId/snapshots", LiveCoachJson.encodeSnapshot(snapshot), MAX_ANSWER_BYTES),
        )

    /** `POST /v1/live/sessions/{id}/end`: the ride is over. Nothing comes back that matters: a failed end is the service's timeout's to clean up. */
    public suspend fun end(
        sessionId: String,
        end: LiveSessionEnd,
    ) {
        client.post("$SESSIONS/$sessionId/end", LiveCoachJson.encodeSessionEnd(end), MAX_ANSWER_BYTES)
    }

    /** `POST /v1/live/sessions/{id}/cues/{cueId}/played`: whether the rider was told. Optional for the service, so a failure costs nothing. */
    public suspend fun played(
        sessionId: String,
        cueId: Int,
        played: LiveCuePlayed,
    ) {
        client.post("$SESSIONS/$sessionId/cues/$cueId/played", LiveCoachJson.encodeCuePlayed(played), MAX_ANSWER_BYTES)
    }

    /** The session's cue stream (`GET /v1/live/sessions/{id}/cues`) for a [CueStream]. */
    public fun cues(sessionId: String): CueStreamSource =
        CueStreamSource { lastEventId, onChunk -> client.stream("$SESSIONS/$sessionId/cues", lastEventId, onChunk) }

    /** Whether the service is set up (an address and a token): without it nothing is asked and nothing is said. */
    public val isConfigured: Boolean get() = client.isConfigured

    private companion object {
        const val SESSIONS = "/v1/live/sessions"
        const val MAX_ANSWER_BYTES = 4_096
    }
}
