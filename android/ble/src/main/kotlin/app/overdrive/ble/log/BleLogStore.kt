package app.overdrive.ble.log

import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Whose Bluetooth traffic a log file holds. The role is in the file name; a device's name or address never is. */
public enum class BleLogRole(
    internal val label: String,
) {
    TRAINER("trainer"),
    STRAP("strap"),
    CLICK("click"),
}

/**
 * How much the raw Bluetooth logs may grow (ADR 0010): the newest [maxFiles] files, at most [maxTotalBytes] together,
 * and a file stops growing at [maxFileBytes]. The defaults keep tens of hours of riding: a trainer and a strap write
 * about 100 bytes a second between them.
 */
public data class BleLogLimits(
    val maxFiles: Int = 60,
    val maxTotalBytes: Long = 20L * 1024 * 1024,
    val maxFileBytes: Long = 2L * 1024 * 1024,
)

/** What is on disk, for the Settings row. */
public data class BleLogSummary(
    val files: Int,
    val bytes: Long,
) {
    public companion object {
        public val None: BleLogSummary = BleLogSummary(0, 0)
    }
}

/**
 * The folder of raw Bluetooth session files (`filesDir/ble-logs/`, served by the app's FileProvider and by nobody
 * else): names them, lists them and keeps them inside [BleLogLimits]. A file is named `ble-<UTC
 * date>T<time>Z-<role>.txt` and holds one connection session of one device in the format of core's `BleSessionFormat`,
 * redacted by core's
 * `BleRecordingPolicy`: no address, no identity values. **It does hold the rider's heart rate** and is shared only by
 * the rider's own action, never uploaded.
 *
 * All calls are plain file operations: call them off the main thread.
 */
public class BleLogStore(
    private val directory: File,
    private val clock: () -> Long = System::currentTimeMillis,
    public val limits: BleLogLimits = BleLogLimits(),
) {
    /** The log files, oldest first (the names start with the time, so name order is time order). */
    public fun files(): List<File> =
        directory
            .listFiles { file -> file.isFile && file.name.startsWith(PREFIX) && file.name.endsWith(SUFFIX) }
            .orEmpty()
            .sortedBy { it.name }

    /** How many files there are and how big they are together. */
    public fun summary(): BleLogSummary =
        files().let { files ->
            BleLogSummary(files.size, files.sumOf { it.length() })
        }

    /**
     * A new, empty file name for a session of [role] starting now, in a folder that exists; older files are deleted
     * first as far as the limits ask. The file itself is created by whoever writes to it.
     */
    public fun newFile(role: BleLogRole): File {
        directory.mkdirs()
        // Two sessions in one second take the next second's name, so name order stays time order.
        var at = clock()
        var candidate = File(directory, name(at, role))
        while (candidate.exists()) candidate = File(directory, name(at.also { at += MILLIS_PER_SECOND }, role))
        prune(keep = candidate, reserve = 1)
        return candidate
    }

    /**
     * Deletes the oldest files until the count and the total size are within the limits; [keep] is never deleted, and
     * [reserve] files are left room for (the one about to be created).
     */
    public fun prune(
        keep: File? = null,
        reserve: Int = 0,
    ) {
        val files = files().toMutableList()
        var total = files.sumOf { it.length() }
        // A file about to be created may grow to the file limit, so the others leave room for it.
        val headroom = if (reserve > 0) limits.maxFileBytes else 0L
        while (files.isNotEmpty() &&
            (files.size + reserve > limits.maxFiles || total + headroom > limits.maxTotalBytes)
        ) {
            val oldest = files.first()
            if (oldest == keep) break
            total -= oldest.length()
            files.removeAt(0)
            oldest.delete()
        }
    }

    private fun name(
        epochMillis: Long,
        role: BleLogRole,
    ): String = "$PREFIX${STAMP.format(Instant.ofEpochMilli(epochMillis))}-${role.label}$SUFFIX"

    private companion object {
        const val MILLIS_PER_SECOND = 1000L
        const val PREFIX = "ble-"
        const val SUFFIX = ".txt"
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    }
}
