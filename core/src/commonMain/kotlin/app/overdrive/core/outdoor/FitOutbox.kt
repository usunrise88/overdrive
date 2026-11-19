package app.overdrive.core.outdoor

/** What the outbox knows of one file without reading it: its place in the queue, its size and checksum, and, when parked, why. */
public data class FitFileInfo(
    /** The order it was queued in: files are sent oldest first. */
    val seq: Long,
    val bytes: Int,
    /** [FitFileCheck.checksumOf] of the file: a second file with the same size and checksum is the same ride. */
    val checksum: String,
    /** Why the service refused it, for a parked file; null while it waits. */
    val refusal: FitRefusal? = null,
)

/**
 * Where FIT files wait for the service (spec §11.8, ADR 0028), the pattern of the watch batches (`BatchStore`, ADR 0027) with one
 * file per entry. **A file is removed only when the service accepted it** ([remove]); one the service refused for good is [park]ed with
 * the reason, kept on disk and counted, never in the way of the ones behind it. The persistent one is `FileFitOutbox` in `android/data`
 * (it survives the process being killed at any instant); [InMemoryFitOutbox] is the tests' stand-in. A file is the rider's ride (heart
 * rate and position): nothing here logs or prints one, and nothing keeps the name the sharing app gave it.
 */
public interface FitOutbox {
    /** Queues a file at the end and returns it. The file is on disk when this returns; a failure is thrown and nothing is queued. */
    public suspend fun add(bytes: ByteArray): FitFileInfo

    /** Everything waiting, oldest first. */
    public suspend fun pending(): List<FitFileInfo>

    /** The bytes of file [seq], or null when they cannot be read whole (the caller parks it). */
    public suspend fun bytes(seq: Long): ByteArray?

    /** The service accepted file [seq]: forget it. */
    public suspend fun remove(seq: Long)

    /** Keeps file [seq] aside, out of the queue, with the reason, for the owner to look at. */
    public suspend fun park(
        seq: Long,
        refusal: FitRefusal,
    )

    /** The files set aside, oldest first. */
    public suspend fun parked(): List<FitFileInfo>

    /** Deletes a parked file (the importer keeps only the last few: the rider still has the original in OnelapFit). */
    public suspend fun discard(seq: Long)
}

/** A [FitOutbox] in memory. Loses everything with the process; what the tests use. */
public class InMemoryFitOutbox : FitOutbox {
    private class Held(
        val info: FitFileInfo,
        val bytes: ByteArray,
    )

    private val waiting = LinkedHashMap<Long, Held>()
    private val aside = LinkedHashMap<Long, Held>()
    private var next = 1L

    /** What a test sets to make [add] fail like a full disk. */
    public var failAdds: Boolean = false

    /** The seqs whose bytes read as damaged (a test's way to corrupt a file on disk). */
    public val damaged: MutableSet<Long> = LinkedHashSet()

    override suspend fun add(bytes: ByteArray): FitFileInfo {
        if (failAdds) throw IllegalStateException("The disk refuses")
        val info = FitFileInfo(next++, bytes.size, FitFileCheck.checksumOf(bytes))
        waiting[info.seq] = Held(info, bytes.copyOf())
        return info
    }

    override suspend fun pending(): List<FitFileInfo> = waiting.values.map { it.info }

    override suspend fun bytes(seq: Long): ByteArray? = waiting[seq]?.takeIf { seq !in damaged }?.bytes?.copyOf()

    override suspend fun remove(seq: Long) {
        waiting.remove(seq)
    }

    override suspend fun park(
        seq: Long,
        refusal: FitRefusal,
    ) {
        waiting.remove(seq)?.let { aside[seq] = Held(it.info.copy(refusal = refusal), it.bytes) }
    }

    override suspend fun parked(): List<FitFileInfo> = aside.values.map { it.info }

    override suspend fun discard(seq: Long) {
        aside.remove(seq)
    }

    /** What is parked, with the bytes (a test looks that the file was kept whole). */
    public fun parkedBytes(seq: Long): ByteArray? = aside[seq]?.bytes?.copyOf()
}
