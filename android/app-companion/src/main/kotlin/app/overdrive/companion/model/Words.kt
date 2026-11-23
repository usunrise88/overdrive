package app.overdrive.companion.model

import app.overdrive.core.remote.ProblemKind
import app.overdrive.core.remote.ServiceProblem
import app.overdrive.uikit.UiFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * The clock and the locale the words are made with. A test fixes all three; the app uses the phone's.
 */
class Clock(
    val nowMillis: () -> Long = System::currentTimeMillis,
    val zone: ZoneId = ZoneId.systemDefault(),
    val locale: Locale = Locale.getDefault(),
) {
    /** The phone's local date, `YYYY-MM-DD`. */
    fun today(): String = Instant.ofEpochMilli(nowMillis()).atZone(zone).toLocalDate().toString()
}

/**
 * Every date, time and number the companion prints, as pure functions (design/companion.md §3.1, §7): no Compose, so each word on
 * every screen is a unit test. Numbers use the true minus sign and the same formats as the ride app ([UiFormat]).
 */
class Words(
    private val clock: Clock = Clock(),
) {
    private fun format(
        pattern: String,
        zoned: java.time.temporal.TemporalAccessor,
    ) = DateTimeFormatter.ofPattern(pattern, clock.locale).format(zoned)

    private fun local(millis: Long) = Instant.ofEpochMilli(millis).atZone(clock.zone)

    /** "07:40" today, "yesterday 21:10" the day before, "17 Nov 21:10" otherwise: when the data on a screen was last fetched. */
    fun updated(fetchedAtMillis: Long): String {
        val then = local(fetchedAtMillis)
        val today = local(clock.nowMillis()).toLocalDate()
        val clockTime = format("HH:mm", then)
        return when (then.toLocalDate()) {
            today -> clockTime
            today.minusDays(1) -> "yesterday $clockTime"
            else -> "${format("d MMM", then)} $clockTime"
        }
    }

    /** "Today 07:40", "Yesterday 21:10", "17 Nov 21:10": the Last sync value in Settings. */
    fun lastSync(millis: Long): String {
        val text = updated(millis)
        val sameDay = text.length == CLOCK_CHARS && text[0].isDigit()
        return if (sameDay) "Today $text" else text.replaceFirstChar { it.uppercase() }
    }

    /** The phone's local date today, `YYYY-MM-DD`: the day a "changed 3 days ago" is counted from. */
    fun today(): String = clock.today()

    /** "Wednesday 18 November". */
    fun todayLine(): String = format("EEEE d MMMM", local(clock.nowMillis()))

    /** "Wed 18 Nov": a day in a readout, a table and a best effort. */
    fun day(date: String): String = format("EEE d MMM", LocalDate.parse(date))

    /** "18 Nov", "9 Nov": a day without its weekday (week starts, "Set 2 Oct"). */
    fun dayShort(date: String): String = format("d MMM", LocalDate.parse(date))

    /** "Monday 2 November": a day in full, for TalkBack and the date picker. */
    fun dayLong(date: String): String = format("EEEE d MMMM", LocalDate.parse(date))

    /** "November 2026": a month's title in the date picker, always with its year. */
    fun monthTitle(date: String): String = format("MMMM yyyy", LocalDate.parse(date))

    /** "Mon", "Tue", …: the day names of a Monday-first week. */
    fun weekdayNames(): List<String> {
        val monday = LocalDate.of(MONDAY_YEAR, 1, MONDAY_DAY)
        return (0 until DAYS_PER_WEEK).map { format("EEE", monday.plusDays(it.toLong())) }
    }

    /** The phone's local calendar year today. */
    fun thisYear(): Int = local(clock.nowMillis()).year

    /** "Wed 18": a ride's row. */
    fun dayOfMonth(date: String): String = format("EEE d", LocalDate.parse(date))

    /** "Thu 12 Nov · 18:02–18:58". */
    fun rideWhen(
        startInstant: String,
        endInstant: String?,
    ): String {
        val start = zoned(startInstant) ?: return ""
        val end = endInstant?.let(::zoned)
        return buildString {
            append(format("EEE d MMM", start)).append(" · ").append(format("HH:mm", start))
            if (end != null) append('–').append(format("HH:mm", end))
        }
    }

    /** The wall clock now, in milliseconds. */
    fun nowMillis(): Long = clock.nowMillis()

    /** The phone's local date of a UTC instant, `YYYY-MM-DD`; null for text that is not an instant. */
    fun localDate(instant: String): String? = zoned(instant)?.toLocalDate()?.toString()

    /** The phone's local hour (0 to 23) now: Today's morning note waits for the morning. */
    fun hourNow(): Int = local(clock.nowMillis()).hour

    /** "Today", "Yesterday" or "Monday 16 November": a day's heading in the Notes list. */
    fun dayHeading(
        date: String,
        today: String,
    ): String {
        val day = LocalDate.parse(date)
        val now = LocalDate.parse(today)
        return when (day) {
            now -> "Today"
            now.minusDays(1) -> "Yesterday"
            else -> format("EEEE d MMMM", day)
        }
    }

    /** When a note was written, in the form of [updated]: "07:30", "yesterday 07:30", "16 Nov 07:30". Null for text that is not an instant. */
    fun written(instant: String): String? =
        app.overdrive.core.wellness.WellnessTime
            .parseInstant(instant)
            ?.let(::updated)

    /** The local clock time of a UTC instant, "18:04". */
    fun clockTime(instant: String): String? = zoned(instant)?.let { format("HH:mm", it) }

    /** The local clock time `seconds` after a UTC instant, "18:58". */
    fun clockTimeAfter(
        instant: String,
        seconds: Int,
    ): String? = zoned(instant)?.plusSeconds(seconds.toLong())?.let { format("HH:mm", it) }

    /** "November", or "November 2025" for a month of another year than the present one. */
    fun month(date: String): String {
        val day = LocalDate.parse(date)
        val thisYear = local(clock.nowMillis()).year
        return format(if (day.year == thisYear) "MMMM" else "MMMM yyyy", day)
    }

    /** "Jun" for a month start on a chart's axis, "Jan" for a quarter start. */
    fun monthShort(date: String): String = format("MMM", LocalDate.parse(date))

    /** "16 November": a day for TalkBack, without its weekday. */
    fun dayMonthLong(date: String): String = format("d MMMM", LocalDate.parse(date))

    /** "Nov", or "Nov 2025" for a month of another year than the present one: a month in a readout or a table. */
    fun monthShortYear(date: String): String {
        val day = LocalDate.parse(date)
        return format(if (day.year == local(clock.nowMillis()).year) "MMM" else "MMM yyyy", day)
    }

    /** "16 Nov", or "16 Nov 2025" for a day of another year than the present one: a week or a ride in a table. */
    fun dayShortYear(date: String): String {
        val day = LocalDate.parse(date)
        return format(if (day.year == local(clock.nowMillis()).year) "d MMM" else "d MMM yyyy", day)
    }

    private fun zoned(instant: String) =
        try {
            Instant.parse(instant).atZone(clock.zone)
        } catch (e: DateTimeParseException) {
            null
        }

    /** A load as a whole number, "74"; null is the dash. */
    fun load(value: Double?): String = value?.let { UiFormat.count(Math.round(it).toInt()) } ?: UiFormat.EMPTY

    /** A signed difference with the true minus sign and an explicit plus: "−3", "+4", "0". */
    fun change(value: Int): String = if (value > 0) "+$value" else UiFormat.signed(value)

    /** "1:05:12" or "42:10". */
    fun duration(seconds: Int?): String = UiFormat.elapsed(seconds)

    /** "20 min", "5 s", "1 h": a power-curve duration. */
    fun curveDuration(seconds: Int): String {
        val minutes = seconds / SECONDS_PER_MINUTE
        val rest = (seconds % SECONDS_PER_MINUTE).toString().padStart(2, '0')
        return when {
            seconds < SECONDS_PER_MINUTE -> "$seconds s"
            seconds < SECONDS_PER_HOUR && rest == "00" -> "$minutes min"
            seconds < SECONDS_PER_HOUR -> "$minutes:$rest"
            seconds % SECONDS_PER_HOUR == 0 -> "${seconds / SECONDS_PER_HOUR} h"
            else -> "$minutes min"
        }
    }

    private companion object {
        const val CLOCK_CHARS = 5

        /** 4 January 2021 is a Monday: the anchor of the day names. */
        const val MONDAY_YEAR = 2021
        const val MONDAY_DAY = 4
        const val DAYS_PER_WEEK = 7
        const val SECONDS_PER_MINUTE = 60
        const val SECONDS_PER_HOUR = 3600
    }
}

/** One problem line or error block, in the design's words (design/companion.md §7.5). */
data class ProblemUi(
    /** "Offline · updated 06:10", "Out of date · updated yesterday 21:10", or "Could not reach your service" with nothing cached. */
    val title: String,
    /** The cause and what to do. */
    val cause: String,
    /** The technical hint in brackets (error block only), or null. */
    val hint: String?,
    val action: ProblemAction?,
)

/** What a problem line's button does. */
enum class ProblemAction(
    val label: String,
) {
    /** Opens the app's own Settings screen (the address, the token). */
    Settings("Settings"),

    /** Opens the phone's own settings for this app (the network is blocked). */
    PhoneSettings("Open settings"),
}

/** The design's sentence for each kind of trouble (design/companion.md §7.5). [host] is the address the rider typed, for the DNS sentence. */
object ProblemWords {
    fun of(
        problem: ServiceProblem,
        host: String,
        updated: String?,
    ): ProblemUi {
        val title =
            when {
                updated == null -> "Could not reach your service"
                problem.isOffline -> "Offline · updated $updated"
                else -> "Out of date · updated $updated"
            }
        val action =
            when (problem.kind) {
                ProblemKind.TOKEN_REJECTED,
                ProblemKind.NAME_NOT_FOUND,
                ProblemKind.NOT_THE_SERVICE,
                -> ProblemAction.Settings

                ProblemKind.NETWORK_BLOCKED -> ProblemAction.PhoneSettings

                else -> null
            }
        return ProblemUi(title, causeOf(problem, host), hintOf(problem), action)
    }

    /** The sentence under the title. */
    fun causeOf(
        problem: ServiceProblem,
        host: String,
    ): String =
        when (problem.kind) {
            ProblemKind.NO_NETWORK -> {
                "No network connection. Pull down to try again."
            }

            ProblemKind.NAME_NOT_FOUND -> {
                "The phone could not find ${host.ifEmpty {
                    "the service"
                }}. Check the address in Settings, or try again on another network."
            }

            ProblemKind.NETWORK_BLOCKED -> {
                "The system blocks this app's network access. Allow it in the phone's app settings."
            }

            ProblemKind.NO_ANSWER -> {
                "The service did not answer. It may be restarting; try again in a minute."
            }

            ProblemKind.TOKEN_REJECTED -> {
                "The service did not accept the token. Check it in Settings."
            }

            ProblemKind.SERVER_ERROR -> {
                "The service had a problem${problem.status?.let { " ($it)" } ?: ""}. Try again later."
            }

            ProblemKind.SECURE_CONNECTION,
            ProblemKind.REQUEST_REFUSED,
            ProblemKind.NO_SUCH_ROUTE,
            ProblemKind.NOT_THE_SERVICE,
            -> {
                "${problem.plain}."
            }
        }

    /** The hint in brackets: what the transport saw, with "DNS" after a name that did not resolve. */
    fun hintOf(problem: ServiceProblem): String? =
        if (problem.kind == ProblemKind.NAME_NOT_FOUND) {
            listOfNotNull(problem.hint?.substringBefore(" · DNS"), "DNS").joinToString(" · ")
        } else {
            problem.hint
        }
}
