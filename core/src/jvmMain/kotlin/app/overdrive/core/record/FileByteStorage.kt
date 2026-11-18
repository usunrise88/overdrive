package app.overdrive.core.record

import java.io.File
import java.io.FileOutputStream

/**
 * A [ByteSink] over a file, for the JVM and so for Android: `RideLogRecorder(FileByteSink(File(dir, "$id.ridelog")))`.
 *
 * The stream is unbuffered, so each [append] is one `write` call and reaches the operating system at once; a killed
 * process loses nothing that was appended. [sync] is `fsync`, for power loss. Missing parent directories are created.
 * With [append] false an existing file is truncated, which a ride log never wants; the default appends.
 */
public class FileByteSink(
    file: File,
    append: Boolean = true,
) : ByteSink {
    private val stream: FileOutputStream

    init {
        file.absoluteFile.parentFile?.mkdirs()
        stream = FileOutputStream(file, append)
    }

    override fun append(bytes: ByteArray) {
        stream.write(bytes)
    }

    override fun sync() {
        stream.fd.sync()
    }

    override fun close() {
        stream.close()
    }
}

/** A [ByteSource] over a file. [readAll] throws `java.io.IOException` if the file cannot be read. */
public class FileByteSource(
    private val file: File,
) : ByteSource {
    override fun readAll(): ByteArray = file.readBytes()
}
