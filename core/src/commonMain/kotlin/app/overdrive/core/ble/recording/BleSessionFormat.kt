package app.overdrive.core.ble.recording

import app.overdrive.core.ble.GattUuid

/** What [BleSessionFormat.parse] made of a text, and how much of it it could not use. */
public class BleSessionParse(
    public val session: BleSession,
    /** Lines that were neither comments nor valid events: skipped, never fatal. */
    public val skippedLines: Int,
    /** The version in the header line, or null if there was none. Only version 1 is written and understood. */
    public val version: Int?,
)

/**
 * The text format of a recorded BLE session (docs/protocols/ftms-hr.md §7.1, `docs/adr/0009-ble-transport-and-adapters.md`).
 * One event per line, five fields separated by single spaces:
 *
 * ```
 * # overdrive-ble-session 1
 * # model D500
 * 0 S - - connecting
 * 210000000 S - 0 connected
 * 230000000 D 2AD2 - 1826/N
 * 1250000000 N 2AD2 - 4400C409B400FA00
 * ```
 *
 * Lines starting with `#` are comments (the first is the version, an optional second `# model <name>` is the advertised
 * model). Blank lines are ignored. Parsing never throws: a line it cannot read is counted and skipped, because a file from
 * a phone that lost power half way through a write is exactly what the replay is for.
 */
public object BleSessionFormat {
    /** The only version written and understood. */
    public const val VERSION: Int = 1

    /** The version comment, the first line of every file. */
    public const val HEADER: String = "# overdrive-ble-session $VERSION"

    private const val MODEL_PREFIX = "# model "
    private const val VERSION_PREFIX = "# overdrive-ble-session "
    private const val FIELD_COUNT = 5
    private const val NO_FIELD = "-"

    /** The header lines of a file: the version, and the model if there is one. */
    public fun headerLines(model: String?): List<String> = listOfNotNull(HEADER, model?.let { "$MODEL_PREFIX$it" })

    /** One event as its line, without a line break. */
    public fun format(event: BleSessionEvent): String =
        buildString {
            append(event.timeNanos)
            append(' ').append(event.direction.code)
            append(' ').append(event.characteristic?.shortForm ?: NO_FIELD)
            append(' ').append(event.status?.toString() ?: NO_FIELD)
            append(' ').append(event.data)
        }

    /** A whole session as text, ending with a line break. */
    public fun write(session: BleSession): String =
        buildString {
            headerLines(session.model).forEach { appendLine(it) }
            session.events.forEach { appendLine(format(it)) }
        }

    /** One line as an event, or null if it is not one. */
    public fun parseLine(line: String): BleSessionEvent? {
        val fields = line.trim().split(' ')
        if (fields.size != FIELD_COUNT) return null
        val time = fields[0].toLongOrNull()?.takeIf { it >= 0 } ?: return null
        val direction = fields[1].singleOrNull()?.let(BleDirection::of) ?: return null
        val characteristic =
            if (fields[2] == NO_FIELD) null else GattUuid.parse(fields[2]) ?: return null
        val status =
            if (fields[3] == NO_FIELD) null else fields[3].toIntOrNull() ?: return null
        val data = fields[4]
        if (data.isEmpty()) return null
        return BleSessionEvent(time, direction, characteristic, status, data)
    }

    /** Reads a whole file. Never throws. Events keep their file order. */
    public fun parse(text: String): BleSession = parseWithDiagnostics(text).session

    /** Like [parse], also reporting the version and how many lines were skipped. */
    public fun parseWithDiagnostics(text: String): BleSessionParse {
        var version: Int? = null
        var model: String? = null
        var skipped = 0
        val events = ArrayList<BleSessionEvent>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> {
                    Unit
                }

                line.startsWith(VERSION_PREFIX) -> {
                    version = line.removePrefix(VERSION_PREFIX).trim().toIntOrNull()
                }

                line.startsWith(MODEL_PREFIX) -> {
                    model = line.removePrefix(MODEL_PREFIX).trim().ifEmpty { null }
                }

                line.startsWith("#") -> {
                    Unit
                }

                else -> {
                    val event = parseLine(line)
                    if (event == null) skipped++ else events += event
                }
            }
        }
        return BleSessionParse(BleSession(model, events), skipped, version)
    }
}
