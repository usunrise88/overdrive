package app.overdrive.companion.model

import androidx.compose.runtime.Immutable
import app.overdrive.core.health.HealthAccess
import app.overdrive.core.health.HealthAvailability
import app.overdrive.core.health.HealthDataType
import app.overdrive.core.health.HealthStatus
import app.overdrive.core.health.ReadIssue
import app.overdrive.core.health.ReadProgress
import app.overdrive.core.health.SentCounts
import java.util.Locale

/** A row of the permission list (design/companion.md §4.10): the name, the reason and, after the dialog, whether it was allowed. */
@Immutable
data class PermissionRowUi(
    val type: HealthDataType,
    val name: String,
    val reason: String,
    /** True allowed, false off, null before the rider has been asked. */
    val allowed: Boolean?,
    val optional: Boolean,
)

/** A Notice of the permission step: what is lost when something is off. */
@Immutable
data class LostUi(
    val title: String,
    val text: String,
)

/** The Health Connect group of Settings (design/companion.md §4.9), in words. */
@Immutable
data class HealthSettingsUi(
    /** The one Notice that replaces the group when Health Connect cannot be used: its title and text. */
    val unavailable: LostUi?,
    val dataAccess: String,
    /** The button next to the access row: **Manage**, or **Allow** while nothing is allowed. */
    val accessAction: AccessAction,
    val lastRead: String,
    val lastReadNote: String,
    /** "Sleep 7 · Resting heart rate 7": what the last upload sent, by type; null before one. */
    val sentByType: String?,
    val background: BackgroundUi,
    /** What waits and why, when something does: "312 records in 2 batches wait for the service." */
    val waiting: String?,
    val readProblem: String?,
)

enum class AccessAction(
    val label: String,
) {
    Manage("Manage"),
    Allow("Allow"),
}

/** The Background reading row: allowed with a check, or why not and where to fix it. */
@Immutable
data class BackgroundUi(
    val allowed: Boolean,
    val text: String,
    val action: String?,
    /** Where **Open settings** leads: the phone's battery saving, or Health Connect's own permission screen. */
    val fix: BackgroundFix?,
)

enum class BackgroundFix { BatterySaving, HealthConnect }

/**
 * Every word about the watch data that is not in a chart (design/companion.md §4.9, §4.10, §7.5): the permission list and what is lost
 * when a part is off, the Health Connect group of Settings, the progress of the first read, and the Today line when the read stopped.
 * Pure, so every sentence is a unit test.
 */
object HealthWords {
    fun rows(
        access: HealthAccess?,
        asked: Boolean,
    ): List<PermissionRowUi> =
        HealthDataType.entries.map { type ->
            PermissionRowUi(
                type,
                type.label,
                type.reason,
                allowed = if (asked && access != null) type in access.granted else null,
                optional = !type.essential,
            )
        }

    /** "Health Connect allowed 5 of 9." after the dialog. */
    fun allowedSentence(access: HealthAccess): String {
        val total = HealthDataType.entries.size
        return "Health Connect allowed ${access.granted.size} of $total."
    }

    /** What is lost when [access] leaves something off, or null when nothing the rider would miss is off. */
    fun lost(access: HealthAccess): LostUi? {
        val off = HealthDataType.entries.filter { it !in access.granted }
        if (off.isEmpty()) return null
        val essential = off.filter { it.essential }
        val title = if (off.size == 1) "${off.single().label} is off" else "${off.size} signals are off"
        val them = if (off.size == 1) "it" else "them"
        val text =
            if (essential.isNotEmpty()) {
                "Recovery works from the other signals and says less. You can allow $them later in Settings."
            } else {
                "Overdrive works without $them. You can allow $them later in Settings."
            }
        return LostUi(title, text)
    }

    /** "Sleep" for the button that asks again once: the first essential type that is off, else the first that is off. */
    fun firstOff(access: HealthAccess): HealthDataType? =
        HealthDataType.entries.firstOrNull { it.essential && it !in access.granted }
            ?: HealthDataType.entries.firstOrNull { it !in access.granted }

    fun unavailable(availability: HealthAvailability): LostUi? =
        when (availability) {
            HealthAvailability.AVAILABLE -> {
                null
            }

            HealthAvailability.NOT_INSTALLED -> {
                notAvailable(
                    "Install Health Connect from the Play Store, then come back.",
                )
            }

            HealthAvailability.UPDATE_REQUIRED -> {
                notAvailable(
                    "Update Health Connect from the Play Store, then come back.",
                )
            }

            HealthAvailability.NOT_SUPPORTED -> {
                notAvailable("This phone does not have Health Connect.")
            }
        }

    fun settings(
        status: HealthStatus,
        batteryUnrestricted: Boolean,
        words: Words,
    ): HealthSettingsUi {
        val access = status.access
        val total = HealthDataType.entries.size
        val missing = HealthDataType.entries.filter { it !in access.granted }
        val dataAccess =
            when {
                missing.isEmpty() -> {
                    "All $total allowed"
                }

                access.granted.isEmpty() -> {
                    "None allowed · Overdrive reads nothing"
                }

                else -> {
                    val essential = access.missingEssential
                    val first = essential.firstOrNull() ?: missing.first()
                    val off = "${first.label.lowercase()} is off"
                    val tail = if (essential.isEmpty()) off else "$off, so recovery says less"
                    "${access.granted.size} of $total allowed · $tail"
                }
            }
        return HealthSettingsUi(
            unavailable = unavailable(status.availability),
            dataAccess = dataAccess,
            accessAction = if (access.granted.isEmpty()) AccessAction.Allow else AccessAction.Manage,
            lastRead = status.lastReadMillis?.let(words::lastSync) ?: "Never",
            lastReadNote = "Once a day at about 06:00, and when you open the app",
            sentByType = status.lastSent?.let(::byType),
            background = background(access, status.readIssue, batteryUnrestricted),
            waiting = waiting(status),
            readProblem = readProblem(status.readIssue),
        )
    }

    /** "Sleep 7 · Resting heart rate 7 · Heart rate 12": the last upload by type, in the order of the permission list. */
    fun byType(sent: SentCounts): String? {
        val parts =
            HealthDataType.entries.mapNotNull { type ->
                val count = kinds(type).sumOf { sent.byType[it] ?: 0 }
                if (count > 0) "${type.label} ${count(count)}" else null
            }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    private fun kinds(type: HealthDataType): List<String> =
        when (type) {
            HealthDataType.RESTING_HEART_RATE -> listOf("restingHeartRate")
            HealthDataType.SLEEP -> listOf("sleepSession")
            HealthDataType.RESPIRATORY_RATE -> listOf("respiratoryRate")
            HealthDataType.OXYGEN_SATURATION -> listOf("oxygenSaturation")
            HealthDataType.HEART_RATE -> listOf("heartRateSamples")
            HealthDataType.EXERCISE -> listOf("exerciseSession")
            HealthDataType.WEIGHT -> listOf("weight")
            HealthDataType.HEART_RATE_VARIABILITY -> listOf("heartRateVariabilityRmssd")
            HealthDataType.STEPS -> listOf("steps")
        }

    fun background(
        access: HealthAccess,
        issue: ReadIssue?,
        batteryUnrestricted: Boolean,
    ): BackgroundUi =
        when {
            !access.backgroundOk || issue == ReadIssue.BACKGROUND_NOT_ALLOWED -> {
                BackgroundUi(false, NO_HC_BACKGROUND, "Manage", BackgroundFix.HealthConnect)
            }

            !batteryUnrestricted -> {
                BackgroundUi(false, BATTERY_BLOCKED, "Open settings", BackgroundFix.BatterySaving)
            }

            else -> {
                BackgroundUi(true, "Allowed", null, null)
            }
        }

    /** "312 watch records are waiting." The design's words for what has not reached the service. */
    fun waiting(status: HealthStatus): String? =
        when (status.waitingRecords) {
            0 -> null
            1 -> "1 watch record is waiting."
            else -> "${count(status.waitingRecords)} watch records are waiting."
        }

    fun readProblem(issue: ReadIssue?): String? =
        when (issue) {
            null, ReadIssue.BACKGROUND_NOT_ALLOWED -> null
            ReadIssue.PERMISSION_REVOKED -> REVOKED
            ReadIssue.UNAVAILABLE -> "Health Connect did not answer. It will be tried again."
            ReadIssue.READ_FAILED -> "The last read of your watch data did not finish. It will be tried again."
        }

    /** What step 4 of the first run says when the read cannot go on: the read's own trouble, else that nothing has reached the service. */
    fun problemOf(status: HealthStatus): String? =
        readProblem(status.readIssue)
            ?: if (status.uploadProblem != null && status.waitingRecords > 0) {
                "Nothing has reached your service yet. It will be sent when the service can be reached."
            } else {
                null
            }

    /** The Today line when the watch data stopped coming (design/companion.md §7.5), or null when all is well. */
    fun todayLine(
        status: HealthStatus,
        nowMillis: Long,
    ): ProblemUi? {
        if (status.access.granted.isEmpty() && status.lastReadMillis == null) return null
        if (status.readIssue == ReadIssue.PERMISSION_REVOKED || (status.checked && status.access.granted.isEmpty())) {
            return ProblemUi("Watch data is off", REVOKED, null, ProblemAction.Settings)
        }
        val last = status.lastReadMillis ?: return null
        return if (status.firstReadDone && nowMillis - last > STALE_MILLIS) {
            ProblemUi("Watch data is late", STOPPED, null, ProblemAction.Settings)
        } else {
            null
        }
    }

    /** "1,240 of about 3,100 records", or "Starting…" before the first window is read. */
    fun progress(progress: ReadProgress?): String =
        when {
            progress == null -> "Starting…"
            progress.chunksDone == 0 -> "Starting…"
            progress.chunksDone >= progress.chunksTotal -> "${count(progress.records)} records"
            else -> "${count(progress.records)} of about ${count(maxOf(progress.estimate, progress.records))} records"
        }

    /** The share of the first read that is done, 0 to 1. */
    fun fraction(progress: ReadProgress?): Float =
        if (progress == null || progress.chunksTotal == 0) 0f else progress.chunksDone.toFloat() / progress.chunksTotal

    /** 3100 as "3,100". */
    fun count(n: Int): String = String.format(Locale.ROOT, "%,d", n)

    private const val STALE_MILLIS = 30L * 3_600_000L
    private const val REVOKED = "Overdrive can no longer read your watch data. Allow it in Settings."
    private const val STOPPED = "The phone stopped this morning's read. The figures are from yesterday."
    private const val BATTERY_BLOCKED = "Blocked by the phone's battery saving. The morning note will be late."
    private const val NO_HC_BACKGROUND =
        "Health Connect does not let Overdrive read in the background. The morning note will be late."

    private fun notAvailable(text: String) = LostUi("Health Connect is not available", text)
}
