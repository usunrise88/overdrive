@file:OptIn(ExperimentalForeignApi::class)

package app.overdrive.core.record

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.stringByDeletingLastPathComponent
import platform.posix.EINTR
import platform.posix.O_APPEND
import platform.posix.O_CREAT
import platform.posix.O_TRUNC
import platform.posix.O_WRONLY
import platform.posix.close
import platform.posix.errno
import platform.posix.fsync
import platform.posix.memcpy
import platform.posix.open
import platform.posix.strerror
import platform.posix.write

/**
 * Unbuffered POSIX [ByteSink] for Apple platforms (macOS): mirrors `jvmMain`'s `FileByteSink` so every
 * appended ride-log record reaches the OS immediately and [sync] issues `fsync` (ADR 0007, ADR 0044).
 */
public class FileByteSink(
    path: String,
    append: Boolean = true,
) : ByteSink {
    private var fd: Int

    init {
        val parent = (path as NSString).stringByDeletingLastPathComponent
        if (parent.isNotEmpty() && parent != path) {
            NSFileManager.defaultManager.createDirectoryAtPath(
                path = parent,
                withIntermediateDirectories = true,
                attributes = null,
                error = null,
            )
        }
        val flags = O_WRONLY or O_CREAT or if (append) O_APPEND else O_TRUNC
        val opened = open(path, flags, FILE_MODE)
        if (opened < 0) {
            throw IllegalStateException("Cannot open ride log ($path): ${posixError()}")
        }
        fd = opened
    }

    override fun append(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        check(fd >= 0) { "FileByteSink is closed" }
        bytes.usePinned { pinned ->
            var written = 0
            while (written < bytes.size) {
                val n =
                    write(
                        fd,
                        pinned.addressOf(written),
                        (bytes.size - written).convert(),
                    )
                if (n < 0) {
                    if (errno == EINTR) continue
                    throw IllegalStateException("Write failed: ${posixError()}")
                }
                written += n.toInt()
            }
        }
    }

    override fun sync() {
        check(fd >= 0) { "FileByteSink is closed" }
        if (fsync(fd) != 0) {
            throw IllegalStateException("fsync failed: ${posixError()}")
        }
    }

    override fun close() {
        if (fd >= 0) {
            close(fd)
            fd = -1
        }
    }

    private companion object {
        const val FILE_MODE = 0x1A4 // 0644
    }
}

/** Reads an entire file on Apple platforms into a [ByteArray]. */
public class FileByteSource(
    private val path: String,
) : ByteSource {
    override fun readAll(): ByteArray {
        val data =
            NSData.dataWithContentsOfFile(path)
                ?: throw IllegalStateException("Cannot read file: $path")
        val size = data.length.toInt()
        if (size == 0) return ByteArray(0)
        val out = ByteArray(size)
        out.usePinned { pinned ->
            memcpy(pinned.addressOf(0), data.bytes, data.length)
        }
        return out
    }
}

private fun posixError(): String = strerror(errno)?.toKString() ?: "errno=$errno"
