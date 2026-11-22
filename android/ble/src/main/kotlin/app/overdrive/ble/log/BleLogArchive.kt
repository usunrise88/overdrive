package app.overdrive.ble.log

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * What [BleLogArchive.build] did, for the share text and the Settings row (docs/adr/0010, section "Sharing").
 *
 * @property included sessions (files) in the archive, newest first.
 * @property leftOut sessions that did not fit under the size cap; the oldest go first.
 * @property skipped files that could not be read (damaged, deleted meanwhile, locked): never fatal, always counted.
 * @property archiveBytes size of the archive file.
 */
public data class BleLogArchiveResult(
    val included: Int,
    val leftOut: Int,
    val skipped: Int,
    val archiveBytes: Long,
)

/**
 * Packs the raw Bluetooth log files into **one zip** that the rider hands to another app in one step, instead of up to 60
 * separate files (ADR 0010). Plain `java.util.zip`, no Android types, so the JVM tests cover it.
 *
 * - **What goes in:** the files it is given and nothing else: no listing, no README, no comments, no folders. An entry is
 *   named exactly like its file (`ble-20261118T180412Z-trainer.txt`: a date and a role; the files are already redacted by
 *   core's policy, so an archive holds no device address, and this class adds none).
 * - **Order:** newest sessions first (the names start with the UTC time, so name order is time order).
 * - **Cap:** the archive stays under [capBytes] (20 MB by default); when it would not, the **oldest** sessions are left
 *   out, and the result says how many. Text compresses about tenfold, so the default cap is not met by logs within
 *   [BleLogLimits] (20 MB raw); it is a guard.
 * - **A file that cannot be read** (damaged, deleted while the archive was built, locked) is skipped and counted. An
 *   empty file is left out without being counted: it has nothing to send.
 *
 * Call off the main thread: it reads and compresses every file.
 */
public object BleLogArchive {
    /** The default cap of one archive. */
    public const val DEFAULT_CAP_BYTES: Long = 20L * 1024 * 1024

    /** What an entry costs beyond its compressed data (local header, central directory record), with the longest log file name. */
    private const val ENTRY_OVERHEAD_BYTES = 160L
    private const val END_OF_ARCHIVE_BYTES = 22L

    /**
     * Writes [files] to [target] (replaced if it exists) as described above and returns what went in.
     * @throws IOException if [target] itself cannot be written; a bad input file never throws.
     */
    public fun build(
        files: List<File>,
        target: File,
        capBytes: Long = DEFAULT_CAP_BYTES,
    ): BleLogArchiveResult {
        target.parentFile?.mkdirs()
        val newestFirst = files.sortedByDescending { it.name }
        var included = 0
        var full = false
        var leftOut = 0
        var skipped = 0
        var size = END_OF_ARCHIVE_BYTES
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            zip.setLevel(Deflater.BEST_COMPRESSION)
            for (file in newestFirst) {
                // Once one session does not fit, every older one is left out too: the oldest go first, never a middle one.
                if (full) {
                    leftOut++
                    continue
                }
                val bytes =
                    try {
                        file.readBytes()
                    } catch (_: IOException) {
                        skipped++
                        continue
                    } catch (_: SecurityException) {
                        skipped++
                        continue
                    }
                if (bytes.isEmpty()) continue
                val cost = compressedSize(bytes) + ENTRY_OVERHEAD_BYTES
                if (size + cost > capBytes) {
                    full = true
                    leftOut++
                    continue
                }
                val entry = ZipEntry(file.name)
                entry.time = file.lastModified()
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
                size += cost
                included++
            }
        }
        return BleLogArchiveResult(included, leftOut, skipped, target.length())
    }

    /** The deflated size of [bytes], measured by compressing them to nowhere. */
    private fun compressedSize(bytes: ByteArray): Long {
        val counter = CountingStream()
        DeflaterOutputStream(counter, Deflater(Deflater.BEST_COMPRESSION, true)).use { it.write(bytes) }
        return counter.count
    }

    private class CountingStream : OutputStream() {
        var count = 0L
            private set

        override fun write(b: Int) {
            count++
        }

        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ) {
            count += len
        }
    }
}
