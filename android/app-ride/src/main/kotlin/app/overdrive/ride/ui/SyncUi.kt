package app.overdrive.ride.ui

import app.overdrive.core.sync.SyncReport
import app.overdrive.core.sync.SyncStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What Settings, Sync says about the uploads (spec §4.9, ADR 0014): how many rides wait, one line of plain words about what
 * the sync is doing or the last thing that went wrong, when an upload last succeeded, and whether **Send now** does anything.
 * Built from the coordinator's [SyncStatus] and [SyncReport] by [of]; the screen only shows it.
 */
data class SyncUi(
    /** Rides still in the outbox, the refused ones included. */
    val waiting: Int = 0,
    /** One line, or null when there is nothing to say (everything is sent). Never an address, a token or a ride's data. */
    val line: String? = null,
    /** "Never", "Today 18:04" or "18 Nov 18:04". */
    val lastUpload: String = NEVER,
    /** Whether there is something to send and nothing is being sent. */
    val canSendNow: Boolean = false,
) {
    companion object {
        const val NEVER = "Never"

        /** [nowMillis] decides "Today"; [zone] and [locale] are the tablet's. */
        fun of(
            status: SyncStatus,
            report: SyncReport,
            nowMillis: Long,
            zone: ZoneId = ZoneId.systemDefault(),
            locale: Locale = Locale.getDefault(),
        ): SyncUi {
            val waiting = waiting(status)
            return SyncUi(
                waiting = waiting,
                line = line(status, report),
                lastUpload = report.lastSuccessMillis?.let { whenText(it, nowMillis, zone, locale) } ?: NEVER,
                canSendNow = waiting > 0 && status !is SyncStatus.Syncing,
            )
        }

        private fun waiting(status: SyncStatus): Int =
            when (status) {
                is SyncStatus.Idle -> status.pending
                is SyncStatus.Syncing -> status.pending
                is SyncStatus.WaitingToRetry -> status.pending
                is SyncStatus.NotConfigured -> status.pending
                is SyncStatus.TokenRejected -> status.pending
                SyncStatus.Stopped, is SyncStatus.OutboxUnavailable -> 0
            }

        /** What went wrong comes before what is merely refused, and both before silence. */
        private fun line(
            status: SyncStatus,
            report: SyncReport,
        ): String? =
            when (status) {
                is SyncStatus.NotConfigured -> "Add the address and the token to send rides"
                is SyncStatus.TokenRejected -> "The service did not accept the token. Check the token above"
                is SyncStatus.WaitingToRetry -> "${status.reason ?: "Could not send"}. Trying again soon"
                is SyncStatus.OutboxUnavailable -> "Could not read the list of rides to send"
                is SyncStatus.Syncing -> "Sending…"
                is SyncStatus.Idle, SyncStatus.Stopped -> refusedLine(report)
            }

        private fun refusedLine(report: SyncReport): String? {
            val first = report.refused.firstOrNull() ?: return null
            val count = report.refused.size
            val rides = if (count == 1) "1 ride refused" else "$count rides refused"
            return "$rides: ${first.message}"
        }

        private fun whenText(
            millis: Long,
            nowMillis: Long,
            zone: ZoneId,
            locale: Locale,
        ): String {
            val then = Instant.ofEpochMilli(millis).atZone(zone)
            val now = Instant.ofEpochMilli(nowMillis).atZone(zone)
            val clock = DateTimeFormatter.ofPattern("HH:mm", locale).format(then)
            return if (then.toLocalDate() == now.toLocalDate()) {
                "Today $clock"
            } else {
                "${DateTimeFormatter.ofPattern("d MMM", locale).format(then)} $clock"
            }
        }
    }
}
