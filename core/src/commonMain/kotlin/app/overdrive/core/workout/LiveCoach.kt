package app.overdrive.core.workout

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive

// The workout's three live-coach fields (spec §8.1, §12.1, docs/adr/0038): `live_coach`, `coach_brief` and the steps' `must_say`.
// The agent writes them, the service keeps them and serves them on `GET /v1/workouts`; the tablet reads `live_coach` to decide
// whether to ask for a live session. **`coach_brief` and every `must_say` line are the agent's words to the coach on the service and are
// never shown on the tablet**: no screen reads them, the ride engine never sees them (`flatSteps` drops them) and a ride keeps none.

/** The longest `coach_brief`, in characters (Unicode code points). */
public const val MAX_COACH_BRIEF_CHARS: Int = 1000

/** The longest `must_say` line, and the longest cue, in characters (Unicode code points). */
public const val MAX_MUST_SAY_CHARS: Int = 80

/** At most this many `must_say` lines on a step. */
public const val MAX_MUST_SAY_PER_STEP: Int = 3

/** When, inside its step, a [MustSay] line is spoken (`contracts/schema/workout.schema.json` `$defs/mustSay`). */
@Serializable(with = MustSayMomentSerializer::class)
public sealed interface MustSayMoment {
    /** As the step begins. Written `"start"`. */
    public data object Start : MustSayMoment

    /** [seconds] into the step: a whole second from 1, below the step's duration (checked by the step). */
    public data class Second(
        val seconds: Int,
    ) : MustSayMoment {
        init {
            require(seconds >= 1) { "A line's second is 1 or more, got $seconds" }
        }
    }
}

/**
 * A line the live coach speaks **exactly as written**, tied to a step, with no model involved (spec §12.1). [text] is 1 to
 * [MAX_MUST_SAY_CHARS] characters and not blank. Never shown on the tablet.
 */
@Serializable
public data class MustSay(
    val at: MustSayMoment,
    val text: String,
) {
    init {
        require(text.isNotBlank()) { "A line has words" }
        require(codePointCount(text) <= MAX_MUST_SAY_CHARS) {
            "A line is at most $MAX_MUST_SAY_CHARS characters, got ${codePointCount(text)}"
        }
    }
}

/** The number of characters of [text] as the contract counts them: Unicode code points (a surrogate pair is one). */
internal fun codePointCount(text: String): Int {
    var count = 0
    var i = 0
    while (i < text.length) {
        count++
        i += if (text[i].isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) 2 else 1
    }
    return count
}

/**
 * The checks of a step's lines that the schema cannot state: 1 to [MAX_MUST_SAY_PER_STEP] of them, each spoken inside the step (its
 * second below [duration]) and no two at the same moment. Messages say the rule and never quote a line.
 */
internal fun requireValidMustSay(
    duration: Int,
    lines: List<MustSay>?,
) {
    if (lines == null) return
    require(lines.size in 1..MAX_MUST_SAY_PER_STEP) {
        "A step has 1 to $MAX_MUST_SAY_PER_STEP lines to say, or none; got ${lines.size}"
    }
    val seen = HashSet<MustSayMoment>()
    for (line in lines) {
        val at = line.at
        require(at !is MustSayMoment.Second || at.seconds < duration) {
            "A line is spoken inside its step: second ${(at as MustSayMoment.Second).seconds} of $duration"
        }
        require(seen.add(at)) { "Two lines of a step are not at the same moment" }
    }
}

/** `coach_brief`: 1 to [MAX_COACH_BRIEF_CHARS] characters and not blank. Never shown on the tablet. */
internal fun requireValidCoachBrief(brief: String?) {
    if (brief == null) return
    require(brief.isNotBlank()) { "A coach brief has words" }
    require(codePointCount(brief) <= MAX_COACH_BRIEF_CHARS) {
        "A coach brief is at most $MAX_COACH_BRIEF_CHARS characters, got ${codePointCount(brief)}"
    }
}

/** Reads `"start"` or a whole number from 1, and nothing else: not `"30"`, not `1.5`, not `true`, not `null`. */
internal object MustSayMomentSerializer : KSerializer<MustSayMoment> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("MustSayMoment", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): MustSayMoment {
        val element = (decoder as? JsonDecoder)?.decodeJsonElement() ?: throw SerializationException("JSON only")
        val primitive =
            element as? JsonPrimitive ?: throw SerializationException("A line's moment is start or a second")
        if (primitive.isString) {
            if (primitive.content == START) return MustSayMoment.Start
            throw SerializationException("A line's moment is \"start\" or a whole second")
        }
        val seconds =
            primitive.content.toIntOrNull() ?: throw SerializationException("A line's second is a whole number")
        return try {
            MustSayMoment.Second(seconds)
        } catch (e: IllegalArgumentException) {
            throw SerializationException(e.message ?: "A line's second is 1 or more")
        }
    }

    override fun serialize(
        encoder: Encoder,
        value: MustSayMoment,
    ) {
        val json = encoder as? JsonEncoder ?: throw SerializationException("JSON only")
        json.encodeJsonElement(
            when (value) {
                MustSayMoment.Start -> JsonPrimitive(START)
                is MustSayMoment.Second -> JsonPrimitive(value.seconds)
            },
        )
    }

    private const val START = "start"
}

/** `true` and `false` and nothing else: kotlinx.serialization would also read `"true"` as `true`. */
internal object StrictBooleanSerializer : KSerializer<Boolean> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("StrictBoolean", PrimitiveKind.BOOLEAN)

    override fun deserialize(decoder: Decoder): Boolean {
        val element = (decoder as? JsonDecoder)?.decodeJsonElement() ?: throw SerializationException("JSON only")
        val primitive = element as? JsonPrimitive
        if (primitive == null || primitive.isString) throw SerializationException("Expected true or false")
        return when (primitive.content) {
            "true" -> true
            "false" -> false
            else -> throw SerializationException("Expected true or false")
        }
    }

    override fun serialize(
        encoder: Encoder,
        value: Boolean,
    ) = encoder.encodeBoolean(value)
}

/**
 * The workout without what is only for the live coach: `live_coach`, `coach_brief` and every step's `must_say`. The ride keeps its
 * copy of the workout (what was ridden) **without** them, and so does anything that is shown: they are the agent's words to the coach
 * on the service (docs/adr/0038). The same rule as `withoutLiveCoach` in `contracts/src/live-coach.ts`.
 */
public fun Workout.withoutLiveCoach(): Workout =
    copy(
        liveCoach = null,
        coachBrief = null,
        steps =
            steps.map { step ->
                when (step) {
                    is LeafStep -> step.withoutMustSay()
                    is RepeatStep -> step.copy(steps = step.steps.map { it.withoutMustSay() })
                }
            },
    )

private fun LeafStep.withoutMustSay(): LeafStep =
    when (this) {
        is SteadyStep -> copy(mustSay = null)
        is RampStep -> copy(mustSay = null)
        is FreeStep -> copy(mustSay = null)
        is HeartRateStep -> copy(mustSay = null)
    }

/** Whether the workout asks for the live coach (`live_coach: true`). Absent and `false` both say no. */
public val Workout.wantsLiveCoach: Boolean get() = liveCoach == true
