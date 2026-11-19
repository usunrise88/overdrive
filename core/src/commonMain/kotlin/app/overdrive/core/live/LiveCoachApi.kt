package app.overdrive.core.live

import app.overdrive.core.sync.parseStrictObject
import app.overdrive.core.wellness.WellnessTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64

// The live coach's wire formats (spec §8.3, §12.1, docs/adr/0038, 0039). The JSON Schemas in `contracts/schema/live-*.schema.json` are the
// source of truth; every model here is read as strictly as they are (unknown keys, a number where a text is meant and the other way round,
// every bound, and the rules a schema cannot state), by `LiveCoachJson`, and `LiveCoachContractTest` runs the shared fixtures.
//
// THE SNAPSHOT IS THE RIDER'S BODY. Its numbers are in no message built here, only the paths and the rules.

/** The largest whole number JSON carries exactly (`Number.MAX_SAFE_INTEGER`): the bound of every `clock` and `serverTime`. */
private const val MAX_CLOCK: Long = 9_007_199_254_740_991L

private val rideIdPattern = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
private val structureHashPattern = Regex("^[0-9a-f]{16}$")
private val sessionIdPattern = Regex("^[A-Za-z0-9_-]{1,64}$")
private val triggerPattern = Regex("^[a-z][a-z0-9-]{0,31}$")
private val base64Pattern = Regex("^[A-Za-z0-9+/]+={0,2}$")

/** Why the live coach is off for a ride (`live-session-start-response.schema.json`). */
@Serializable
public enum class LiveCoachOffReason {
    /** The owner turned the live coach off on the service. */
    @SerialName("feature-off")
    FEATURE_OFF,

    /** No speech service is configured on the service. */
    @SerialName("no-tts")
    NO_TTS,

    /** No language model is configured on the service. */
    @SerialName("no-llm")
    NO_LLM,

    /** No workout id, or the service does not have that planned workout (a free ride, or one that has left the plan). */
    @SerialName("no-workout")
    NO_WORKOUT,

    /** The workout does not ask for the coach. The tablet says nothing about it. */
    @SerialName("no-live-coach")
    NO_LIVE_COACH,

    /** The workout the tablet plays is not the one the service has: it was changed in between. */
    @SerialName("structure-changed")
    STRUCTURE_CHANGED,
}

/** The body of `POST /v1/live/sessions`: the ride app starts a session. */
@Serializable
public data class LiveSessionStart(
    /** The ride's id (`ride.id`): the cue log is stored under it. */
    val rideId: String,
    /** The planned workout's id (`icu-123`), or null for a ride with no workout. */
    val workoutId: String? = null,
    /** The structure hash the service sent with the workout (the variant's when that is ridden), or null. */
    val structureHash: String? = null,
    /** The rider chose the easier version, whose [structureHash] is the variant's. */
    val variantRidden: Boolean? = null,
    /** The app's clock, milliseconds since the Unix epoch. */
    val clock: Long,
) {
    init {
        require(rideIdPattern.matches(rideId)) { "/rideId is not a ride id" }
        require(workoutId == null || rideIdPattern.matches(workoutId)) { "/workoutId is not a workout id" }
        require(structureHash == null || structureHashPattern.matches(structureHash)) {
            "/structureHash is 16 lower-case hex characters"
        }
        require(clock in 0..MAX_CLOCK) { "/clock is a time in milliseconds" }
    }
}

/** What the service says about the coach for this ride: [on], and [reason] exactly when it is off. */
@Serializable
public data class LiveCoachDecision(
    val on: Boolean,
    val reason: LiveCoachOffReason?,
) {
    init {
        require(on == (reason == null)) { "/coach/reason is null exactly when the coach is on" }
    }
}

/** The answer to `POST /v1/live/sessions` (201). */
@Serializable
public data class LiveSessionStartResponse(
    /** Names the session; null exactly when the coach is off. */
    val sessionId: String?,
    val coach: LiveCoachDecision,
    /** The service's clock, milliseconds since the Unix epoch. */
    val serverTime: Long,
    /** Always 5. */
    val snapshotEverySeconds: Int,
) {
    init {
        require(sessionId == null || sessionIdPattern.matches(sessionId)) {
            "/sessionId is 1 to 64 characters of A-Z a-z 0-9 _ -"
        }
        require((sessionId != null) == coach.on) { "/sessionId is set exactly when the coach is on" }
        require(serverTime in 0..MAX_CLOCK) { "/serverTime is a time in milliseconds" }
        require(snapshotEverySeconds == LiveCoachJson.SNAPSHOT_EVERY_SECONDS) { "/snapshotEverySeconds is 5" }
    }
}

/** What kind of step a snapshot is in: the schema's `steady`, `ramp`, `free` and `hr`. */
@Serializable
public enum class LiveStepType {
    @SerialName("steady")
    STEADY,

    @SerialName("ramp")
    RAMP,

    @SerialName("free")
    FREE,

    @SerialName("hr")
    HEART_RATE,
}

/** The step being ridden, in a snapshot, flattened as in the ride record (repeats expanded). */
@Serializable
public data class LiveSnapshotStep(
    /** From 0, as the record's `stepIndex`. */
    val index: Int,
    val type: LiveStepType,
    val label: String? = null,
    val durationSeconds: Int,
    /** Pauses do not count. */
    val timeLeftSeconds: Int,
    /** Watts the step asks for (steady and ramp), without the stall guard's cut. */
    val targetWatts: Int? = null,
    /** Beats per minute the step holds (a heart-rate step). */
    val targetBpm: Int? = null,
) {
    init {
        require(index in 0..MAX_STEP_INDEX) { "/step/index is 0 to $MAX_STEP_INDEX" }
        require(
            label == null || codePoints(label) <= MAX_LABEL_CHARS,
        ) { "/step/label is at most $MAX_LABEL_CHARS characters" }
        require(durationSeconds in 1..MAX_SECONDS) { "/step/durationSeconds is 1 to $MAX_SECONDS" }
        require(timeLeftSeconds in 0..MAX_SECONDS) { "/step/timeLeftSeconds is 0 to $MAX_SECONDS" }
        require(timeLeftSeconds <= durationSeconds) { "/step/timeLeftSeconds is more than /step/durationSeconds" }
        require(targetWatts == null || targetWatts in 0..MAX_WATTS) { "/step/targetWatts is 0 to $MAX_WATTS" }
        require(targetBpm == null || targetBpm in MIN_BPM..MAX_BPM) { "/step/targetBpm is $MIN_BPM to $MAX_BPM" }
        require(type != LiveStepType.FREE || (targetWatts == null && targetBpm == null)) {
            "/step has no target in a free step"
        }
        require(type != LiveStepType.HEART_RATE || targetWatts == null) {
            "/step/targetWatts does not belong to a heart-rate step: it holds a heart rate"
        }
        require((type != LiveStepType.STEADY && type != LiveStepType.RAMP) || targetBpm == null) {
            "/step/targetBpm belongs only to a heart-rate step"
        }
    }

    internal companion object {
        const val MAX_STEP_INDEX = 9_999
        const val MAX_LABEL_CHARS = 100
        const val MAX_SECONDS = 259_200
        const val MAX_WATTS = 3_000
        const val MIN_BPM = 20
        const val MAX_BPM = 260
    }
}

/** What happened lately that the coach may care about. */
@Serializable
public enum class LiveEventType {
    @SerialName("stall-guard")
    STALL_GUARD,

    @SerialName("strap-lost")
    STRAP_LOST,

    @SerialName("paused")
    PAUSED,

    @SerialName("resumed")
    RESUMED,
}

/** One recent event of a snapshot, with how long ago it was. */
@Serializable
public data class LiveSnapshotEvent(
    val type: LiveEventType,
    val secondsAgo: Int,
) {
    init {
        require(secondsAgo in 0..LiveCoachJson.EVENT_WINDOW_SECONDS) {
            "/events/secondsAgo is 0 to ${LiveCoachJson.EVENT_WINDOW_SECONDS}"
        }
    }
}

/**
 * The body of `POST /v1/live/sessions/{id}/snapshots`, sent every 5 seconds while the session is on: a compact picture of the ride now.
 * Every number is the rider's body or ride, so none is ever logged or put in a message.
 */
@Serializable
public data class LiveSnapshot(
    /** Counts the snapshots of the session from 0; a repeat or an older one is ignored by the service. */
    val seq: Int,
    /** The app's clock, milliseconds since the Unix epoch. */
    val clock: Long,
    /** The ride record's sense: pauses included (the offset of the latest sample). */
    val elapsedSeconds: Int,
    val paused: Boolean,
    /** The rider muted the coach for the rest of the ride. */
    val muted: Boolean,
    val step: LiveSnapshotStep,
    /** The number of steps of the flattened workout. */
    val stepCount: Int,
    /** Means over the last 5 seconds in whole watts; null (or absent) when not measured. */
    val power: Int? = null,
    val heartRate: Int? = null,
    val cadence: Int? = null,
    /** The share of the step's judged seconds within the target, 0 to 100; null in a free step and before the first judged second. */
    val compliancePercent: Int? = null,
    val stallGuardActive: Boolean? = null,
    /** Newest first, at most 8. */
    val events: List<LiveSnapshotEvent>? = null,
) {
    init {
        require(seq in 0..MAX_SEQ) { "/seq is 0 to $MAX_SEQ" }
        require(clock in 0..MAX_CLOCK) { "/clock is a time in milliseconds" }
        require(
            elapsedSeconds in 0..LiveSnapshotStep.MAX_SECONDS,
        ) { "/elapsedSeconds is 0 to ${LiveSnapshotStep.MAX_SECONDS}" }
        require(stepCount in 1..MAX_STEP_COUNT) { "/stepCount is 1 to $MAX_STEP_COUNT" }
        require(step.index < stepCount) { "/step/index is not below /stepCount" }
        require(
            power == null || power in 0..LiveSnapshotStep.MAX_WATTS,
        ) { "/power is 0 to ${LiveSnapshotStep.MAX_WATTS}" }
        require(heartRate == null || heartRate in LiveSnapshotStep.MIN_BPM..LiveSnapshotStep.MAX_BPM) {
            "/heartRate is ${LiveSnapshotStep.MIN_BPM} to ${LiveSnapshotStep.MAX_BPM}"
        }
        require(cadence == null || cadence in 0..MAX_CADENCE) { "/cadence is 0 to $MAX_CADENCE" }
        require(compliancePercent == null || compliancePercent in 0..PERCENT) { "/compliancePercent is 0 to $PERCENT" }
        require(compliancePercent == null || step.type != LiveStepType.FREE) {
            "/compliancePercent has no meaning in a free step"
        }
        require(events == null || events.size <= MAX_EVENTS) { "/events has at most $MAX_EVENTS" }
    }

    // The readings are the rider's body: a snapshot never prints them.
    override fun toString(): String = "LiveSnapshot(seq=$seq)"

    internal companion object {
        const val MAX_SEQ = 1_000_000
        const val MAX_STEP_COUNT = 10_000
        const val MAX_CADENCE = 250
        const val PERCENT = 100
        const val MAX_EVENTS = 8
    }
}

/** The format of a cue's speech. */
@Serializable
public enum class LiveAudioFormat {
    @SerialName("mp3")
    MP3,

    @SerialName("opus")
    OPUS,

    @SerialName("aac")
    AAC,

    @SerialName("flac")
    FLAC,

    @SerialName("wav")
    WAV,
}

/** The speech of a cue, inline: the [format] and the bytes in base64 (at most 400000 bytes before encoding). */
@Serializable
public data class LiveCueAudio(
    val format: LiveAudioFormat,
    val base64: String,
) {
    init {
        require(base64.length in MIN_BASE64_CHARS..MAX_BASE64_CHARS) {
            "/audio/base64 is $MIN_BASE64_CHARS to $MAX_BASE64_CHARS characters"
        }
        require(base64Pattern.matches(base64)) { "/audio/base64 is base64 with its padding only at the end" }
        require(base64.length % 4 == 0) { "/audio/base64 is not padded to a multiple of 4 characters" }
        require(byteCount() <= LiveCoachJson.AUDIO_MAX_BYTES) {
            "/audio/base64 is more than ${LiveCoachJson.AUDIO_MAX_BYTES} bytes"
        }
    }

    /** The number of bytes the speech has once decoded, worked out from the text without decoding it. */
    public fun byteCount(): Int = base64.length / 4 * 3 - base64.takeLastWhile { it == '=' }.length

    /** The speech's bytes. Held only for as long as it takes to play them. */
    public fun decode(): ByteArray = Base64.decode(base64)

    override fun toString(): String = "LiveCueAudio($format, ${byteCount()} bytes)"
}

private const val MIN_BASE64_CHARS = 4
private const val MAX_BASE64_CHARS = 533_336

/**
 * The data of a `cue` event on `GET /v1/live/sessions/{id}/cues`: one spoken line, ready to play. [id] is also the event's SSE id, so
 * the app resumes with `Last-Event-ID`. [ttlMs] is [expiresAt] minus [createdAt]: an app that does not trust its clock plays a cue only
 * within [ttlMs] of receiving it. **The text and the audio are the rider's cue: never logged.**
 */
@Serializable
public data class LiveCue(
    val id: Int,
    /** What is said, 1 to 80 characters; shown on the plate under the overlay. */
    val text: String,
    /** Why it was made: a name a later service adds is a plain string. */
    val trigger: String,
    val audio: LiveCueAudio,
    /** UTC instant: when the cue was ready. */
    val createdAt: String,
    /** UTC instant: when it stops being worth playing. */
    val expiresAt: String,
    val ttlMs: Int,
) {
    init {
        require(id in 1..MAX_ID) { "/id is 1 to $MAX_ID" }
        require(text.isNotBlank()) { "/text has words" }
        require(codePoints(text) <= LiveCoachJson.CUE_MAX_CHARS) {
            "/text is more than ${LiveCoachJson.CUE_MAX_CHARS} characters"
        }
        require(triggerPattern.matches(trigger)) { "/trigger is a lower-case name" }
        require(ttlMs in 1..MAX_TTL_MS) { "/ttlMs is 1 to $MAX_TTL_MS" }
        val created = WellnessTime.parseInstant(createdAt)
        val expires = WellnessTime.parseInstant(expiresAt)
        require(WellnessTime.isUtcInstant(createdAt) && created != null) { "/createdAt is not a real date and time" }
        require(WellnessTime.isUtcInstant(expiresAt) && expires != null) { "/expiresAt is not a real date and time" }
        require(expires > created) { "/expiresAt is not after /createdAt" }
        require(expires - created == ttlMs.toLong()) { "/ttlMs is not /expiresAt minus /createdAt" }
    }

    override fun toString(): String = "LiveCue(id=$id, trigger=$trigger, ttlMs=$ttlMs)"
}

private const val MAX_ID = 1_000_000
private const val MAX_TTL_MS = 600_000

/** Why a stream ended. */
@Serializable
public enum class LiveStreamEndReason {
    /** The app ended the session or it went quiet for too long. The session is over. */
    @SerialName("ended")
    ENDED,

    /** A new session was started: only one exists at a time. The session is over. */
    @SerialName("superseded")
    SUPERSEDED,

    /** The service is stopping: start a new session when it is back. */
    @SerialName("shutdown")
    SHUTDOWN,
}

/** The data of the `end` event, the last on the cue stream. */
@Serializable
public data class LiveStreamEnd(
    val reason: LiveStreamEndReason,
)

/** Why the app ends a session. */
@Serializable
public enum class LiveSessionEndReason {
    /** The workout ran to its end. */
    @SerialName("finished")
    FINISHED,

    /** The rider ended the ride. */
    @SerialName("stopped")
    STOPPED,

    /** The app gave up. */
    @SerialName("error")
    ERROR,
}

/** The optional body of `POST /v1/live/sessions/{id}/end`; `{}` or no body at all is as good. */
@Serializable
public data class LiveSessionEnd(
    val reason: LiveSessionEndReason? = null,
    val clock: Long? = null,
) {
    init {
        require(clock == null || clock in 0..MAX_CLOCK) { "/clock is a time in milliseconds" }
    }
}

/** What the service says to an end: the session [sessionId] is [status], and made [cues] cues. */
@Serializable
public data class LiveSessionEndResponse(
    val sessionId: String,
    val status: LiveSessionEndStatus,
    val cues: Int,
) {
    init {
        require(sessionIdPattern.matches(sessionId)) { "/sessionId is 1 to 64 characters of A-Z a-z 0-9 _ -" }
        require(cues in 0..MAX_CUES) { "/cues is 0 to $MAX_CUES" }
    }
}

private const val MAX_CUES = 1_000_000

/** Whether this request ended the session or it had ended before. */
@Serializable
public enum class LiveSessionEndStatus {
    @SerialName("ended")
    ENDED,

    @SerialName("already-ended")
    ALREADY_ENDED,
}

/** The body of `POST /v1/live/sessions/{id}/cues/{cueId}/played`: whether the cue was heard. */
@Serializable
public data class LiveCuePlayed(
    val played: Boolean,
    val clock: Long? = null,
) {
    init {
        require(clock == null || clock in 0..MAX_CLOCK) { "/clock is a time in milliseconds" }
    }
}

/** The number of characters of [text] as the contract counts them: Unicode code points. */
private fun codePoints(text: String): Int {
    var count = 0
    var i = 0
    while (i < text.length) {
        count++
        i += if (text[i].isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) 2 else 1
    }
    return count
}

/**
 * Reads and writes the live coach's bodies, **strictly like the schemas**: an unknown key, a text where a number is meant (kotlinx.serialization
 * would read `"12"` as 12) and every bound and rule are an [IllegalArgumentException] that names the path and never a value.
 */
public object LiveCoachJson {
    /** The app sends a snapshot this often, in seconds (`snapshotEverySeconds` in the answer to a start is always this). */
    public const val SNAPSHOT_EVERY_SECONDS: Int = 5

    /** The service writes a comment into the cue stream this often, in seconds, so that no proxy closes a quiet connection. */
    public const val HEARTBEAT_SECONDS: Int = 15

    /** The events of a snapshot reach back this far, in seconds. */
    public const val EVENT_WINDOW_SECONDS: Int = 600

    /** The longest cue, in characters (Unicode code points). */
    public const val CUE_MAX_CHARS: Int = 80

    /** The largest speech of one cue, in bytes before base64. */
    public const val AUDIO_MAX_BYTES: Int = 400_000

    /** The most a cue event can hold: 400000 bytes in base64 plus the rest of the cue. Longer is not read. */
    public const val CUE_EVENT_MAX_BYTES: Int = 560_000

    private val reader = Json

    /** What the tablet sends: a key with nothing to say is left out. */
    private val writer =
        Json {
            encodeDefaults = true
            explicitNulls = false
        }

    /** What the service answers (tests write it): `sessionId` and `reason` are required keys, written as `null` when they are. */
    private val answerWriter = Json { encodeDefaults = true }

    private val numbers =
        setOf(
            "seq",
            "clock",
            "elapsedSeconds",
            "index",
            "durationSeconds",
            "timeLeftSeconds",
            "targetWatts",
            "targetBpm",
            "stepCount",
            "power",
            "heartRate",
            "cadence",
            "compliancePercent",
            "secondsAgo",
            "id",
            "ttlMs",
            "serverTime",
            "snapshotEverySeconds",
            "cues",
        )
    private val booleans = setOf("paused", "muted", "stallGuardActive", "variantRidden", "on", "played")

    private fun <T> read(
        serializer: kotlinx.serialization.KSerializer<T>,
        text: String,
    ): T {
        // A text where a number or a boolean is meant is refused before the model reads it.
        parseStrictObject(text, numbers, booleans)
        return reader.decodeFromString(serializer, text)
    }

    /** The body of `POST /v1/live/sessions`. */
    public fun decodeSessionStart(text: String): LiveSessionStart = read(LiveSessionStart.serializer(), text)

    public fun encodeSessionStart(start: LiveSessionStart): String =
        writer.encodeToString(LiveSessionStart.serializer(), start)

    /** The answer to a start. A reason this build does not know is not read: the answer is then not the contract's. */
    public fun decodeSessionStartResponse(text: String): LiveSessionStartResponse =
        read(LiveSessionStartResponse.serializer(), text)

    public fun encodeSessionStartResponse(response: LiveSessionStartResponse): String =
        answerWriter.encodeToString(LiveSessionStartResponse.serializer(), response)

    public fun decodeSnapshot(text: String): LiveSnapshot = read(LiveSnapshot.serializer(), text)

    public fun encodeSnapshot(snapshot: LiveSnapshot): String =
        writer.encodeToString(LiveSnapshot.serializer(), snapshot)

    /** The data of a `cue` event. */
    public fun decodeCue(text: String): LiveCue = read(LiveCue.serializer(), text)

    public fun encodeCue(cue: LiveCue): String = writer.encodeToString(LiveCue.serializer(), cue)

    /** The data of the `end` event. */
    public fun decodeStreamEnd(text: String): LiveStreamEnd = read(LiveStreamEnd.serializer(), text)

    public fun decodeSessionEnd(text: String): LiveSessionEnd = read(LiveSessionEnd.serializer(), text)

    public fun encodeSessionEnd(end: LiveSessionEnd): String = writer.encodeToString(LiveSessionEnd.serializer(), end)

    public fun decodeSessionEndResponse(text: String): LiveSessionEndResponse =
        read(LiveSessionEndResponse.serializer(), text)

    public fun decodeCuePlayed(text: String): LiveCuePlayed = read(LiveCuePlayed.serializer(), text)

    public fun encodeCuePlayed(played: LiveCuePlayed): String =
        writer.encodeToString(LiveCuePlayed.serializer(), played)
}
