package app.overdrive.core.record

/**
 * Where the ride log's bytes go: the one thing core needs from a file system, so that it can stay free of platform
 * imports. The JVM implementation is `FileByteSink`; tests use [InMemoryByteStore].
 *
 * The log writes every record with a single [append] call, so an implementation that hands each call to the operating
 * system as one `write` loses at most the tail of the last record when the process is killed. [sync] is for power loss.
 */
public interface ByteSink {
    /**
     * Adds [bytes] to the end, after everything appended before. Throws if the bytes could not be written (disk full,
     * storage removed); the log then stops writing and the ride goes on.
     */
    public fun append(bytes: ByteArray)

    /** Forces everything appended so far onto stable storage (`fsync`), so that a power cut does not lose it. */
    public fun sync()

    /** Releases the underlying file. Nothing is appended afterwards. */
    public fun close()
}

/** Where a ride log is read back from, after a crash or when a finished ride is exported. */
public interface ByteSource {
    /** Everything written so far, from the first byte. The log is small (a few hundred kilobytes an hour). */
    public fun readAll(): ByteArray
}

/** A [ByteSink] and [ByteSource] over a byte array, for tests and for anything that does not need a file. */
public class InMemoryByteStore :
    ByteSink,
    ByteSource {
    private var buffer = ByteArray(INITIAL_CAPACITY)
    private var length = 0

    /** Number of bytes appended. */
    public val size: Int get() = length

    /** Number of times [sync] was called. */
    public var syncCount: Int = 0
        private set

    /** Whether [close] was called. */
    public var isClosed: Boolean = false
        private set

    override fun append(bytes: ByteArray) {
        check(!isClosed) { "The store is closed" }
        if (length + bytes.size > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, length + bytes.size))
        bytes.copyInto(buffer, length)
        length += bytes.size
    }

    override fun sync() {
        syncCount++
    }

    override fun close() {
        isClosed = true
    }

    override fun readAll(): ByteArray = buffer.copyOf(length)

    private companion object {
        const val INITIAL_CAPACITY = 4096
    }
}
