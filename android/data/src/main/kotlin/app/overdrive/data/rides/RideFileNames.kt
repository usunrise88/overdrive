package app.overdrive.data.rides

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The name a ride's FIT file goes by when it leaves the app (share and save): `2026-11-18 18-04 Sweet spot 3×10.fit`. */
object RideFileNames {
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm")
    private val forbidden = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")
    private val spaces = Regex("\\s+")
    private const val MAX_TITLE = 60

    /**
     * Date and time of the start in [zone] (the tablet's), then the title made safe for any file system: the characters
     * Windows and shells dislike become a hyphen, runs of spaces one space, and the title is cut at 60 characters.
     */
    fun fit(
        title: String,
        startEpochMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val clean =
            title
                .replace(forbidden, "-")
                .replace(spaces, " ")
                .trim(' ', '.', '-')
                .take(MAX_TITLE)
                .trim(' ', '.', '-')
                .ifEmpty { "Ride" }
        return "${stamp.format(Instant.ofEpochMilli(startEpochMillis).atZone(zone))} $clean.fit"
    }
}
