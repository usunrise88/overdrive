package app.overdrive.core.workout

import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

/** A workout file could not be read: it is malformed, or says something the workout model does not allow. */
public class WorkoutParseException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

/**
 * Loads a workout from the text of a local file (spec §4.5): the native JSON format (spec §8.1) or a Zwift
 * ZWO file, which is converted to the native model. Pure Kotlin, no platform XML API.
 *
 * Every function either returns a valid [Workout] or throws [WorkoutParseException]; nothing is
 * silently dropped, because a skipped step would move every later step in time.
 */
public object WorkoutImport {
    private val json = Json // defaults: unknown keys are rejected, like the schema's additionalProperties: false

    /** Reads the native JSON format. */
    public fun fromJson(text: String): Workout =
        try {
            json.decodeFromString<Workout>(text)
        } catch (e: IllegalArgumentException) {
            // SerializationException and the model's own validation are both IllegalArgumentExceptions.
            throw WorkoutParseException("Not a valid workout file: ${e.message}", e)
        }

    /**
     * Reads a Zwift ZWO file.
     *
     * | ZWO element | Becomes |
     * |---|---|
     * | `SteadyState` (`Duration`, `Power`) | `steady` |
     * | `Warmup`, `Cooldown`, `Ramp` (`Duration`, `PowerLow` to `PowerHigh`) | `ramp` from `PowerLow` to `PowerHigh`, in file order, so a cool-down that lists its higher value as `PowerLow` ramps down. Labelled "Warm-up" and "Cool-down" |
     * | `IntervalsT` (`Repeat`, `OnDuration`, `OnPower`, `OffDuration`, `OffPower`) | `repeat` of a steady "On" step and a steady "Off" step |
     * | `FreeRide` (`Duration`) | `free` |
     * | `MaxEffort` (`Duration`) | `free`, labelled "Max effort": no target, the rider goes all out |
     * | `textevent` | ignored |
     *
     * `Cadence` becomes the step's display cadence (`CadenceResting` for the "Off" step). Powers are
     * fractions of FTP, as in the native format. The workout's name and description come from the
     * `name` and `description` elements. An unknown step element is an error.
     *
     * @param id the workout id; by default `zwo-` followed by the slugified name.
     */
    public fun fromZwo(
        text: String,
        id: String? = null,
    ): Workout {
        val root = parseXml(text)
        if (root.name !=
            "workout_file"
        ) {
            throw WorkoutParseException("Not a ZWO file: the root element is <${root.name}>")
        }
        val body = root.child("workout") ?: throw WorkoutParseException("Not a ZWO file: no <workout> element")
        val steps =
            body.children.mapIndexedNotNull { index, element ->
                try {
                    zwoStep(element, index + 1)
                } catch (e: WorkoutParseException) {
                    throw e
                } catch (e: IllegalArgumentException) {
                    // The model's constructors validate durations and targets; say which step failed.
                    throw WorkoutParseException("Step ${index + 1} <${element.name}>: ${e.message}", e)
                }
            }
        val name = root.child("name")?.text?.takeIf { it.isNotBlank() } ?: DEFAULT_ZWO_NAME
        try {
            return Workout(
                schema = Workout.SCHEMA_VERSION,
                id = id ?: "zwo-${slug(name)}",
                name = name,
                description = root.child("description")?.text?.takeIf { it.isNotBlank() },
                steps = steps,
            )
        } catch (e: IllegalArgumentException) {
            throw WorkoutParseException("Not a valid workout: ${e.message}", e)
        }
    }

    /**
     * Reads either format, told apart by the first character: `{` is native JSON, `<` is ZWO.
     */
    public fun parse(text: String): Workout {
        val body = text.removePrefix("")
        return when (body.firstOrNull { !it.isWhitespace() }) {
            '{' -> fromJson(body)
            '<' -> fromZwo(body)
            else -> throw WorkoutParseException("Not a workout file: expected JSON or ZWO (XML)")
        }
    }

    private fun zwoStep(
        element: XmlElement,
        position: Int,
    ): Step? {
        val where = "step $position <${element.name}>"
        val cadence = element.number("cadence", where)?.takeIf { it > 0.0 }
        return when (element.name) {
            "textevent" -> {
                null
            }

            "steadystate" -> {
                val power = element.number("power", where)
                when {
                    power != null -> SteadyStep(duration = element.duration(where), target = power, cadence = cadence)
                    element.attribute("powerlow") != null -> ramp(element, where, cadence, label = null)
                    else -> missing("Power", where)
                }
            }

            "warmup" -> {
                ramp(element, where, cadence, label = "Warm-up")
            }

            "cooldown" -> {
                ramp(element, where, cadence, label = "Cool-down")
            }

            "ramp" -> {
                ramp(element, where, cadence, label = null)
            }

            "intervalst" -> {
                val resting = element.number("cadenceresting", where)?.takeIf { it > 0.0 }
                RepeatStep(
                    count = element.integer("repeat", where) ?: missing("Repeat", where),
                    steps =
                        listOf(
                            SteadyStep(
                                duration = element.duration(where, "onduration"),
                                target = element.number("onpower", where) ?: missing("OnPower", where),
                                cadence = cadence,
                                label = "On",
                            ),
                            SteadyStep(
                                duration = element.duration(where, "offduration"),
                                target = element.number("offpower", where) ?: missing("OffPower", where),
                                cadence = resting,
                                label = "Off",
                            ),
                        ),
                )
            }

            "freeride" -> {
                FreeStep(duration = element.duration(where), cadence = cadence)
            }

            "maxeffort" -> {
                FreeStep(duration = element.duration(where), cadence = cadence, label = "Max effort")
            }

            else -> {
                throw WorkoutParseException("Unsupported ZWO element <${element.name}> as $where")
            }
        }
    }

    private fun ramp(
        element: XmlElement,
        where: String,
        cadence: Double?,
        label: String?,
    ): RampStep =
        RampStep(
            duration = element.duration(where),
            from = element.number("powerlow", where) ?: missing("PowerLow", where),
            to = element.number("powerhigh", where) ?: missing("PowerHigh", where),
            cadence = cadence,
            label = label,
        )

    private fun XmlElement.number(
        name: String,
        where: String,
    ): Double? {
        val raw = attribute(name) ?: return null
        return raw.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
            ?: throw WorkoutParseException("$where: $name=\"$raw\" is not a number")
    }

    private fun XmlElement.integer(
        name: String,
        where: String,
    ): Int? = number(name, where)?.roundToInt()

    private fun XmlElement.duration(
        where: String,
        name: String = "duration",
    ): Int {
        val seconds = number(name, where) ?: missing(name, where)
        if (seconds < 1.0) throw WorkoutParseException("$where: $name must be at least 1 second, got $seconds")
        return seconds.roundToInt()
    }

    private fun missing(
        attribute: String,
        where: String,
    ): Nothing = throw WorkoutParseException("$where: missing $attribute")

    private fun slug(name: String): String {
        val slug =
            buildString {
                for (c in name.lowercase()) {
                    if (c in 'a'..'z' || c in '0'..'9') {
                        append(c)
                    } else if (isNotEmpty() && last() != '-') {
                        append('-')
                    }
                }
            }.trim('-')
        return slug.ifEmpty { "workout" }
    }

    private const val DEFAULT_ZWO_NAME = "Zwift workout"
}
