package app.overdrive.data.plan

import app.overdrive.core.plan.CachedPlan
import app.overdrive.core.plan.PlanCache
import app.overdrive.core.plan.PlanCacheFormat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The planned workouts' cache on the tablet's disk (spec §5.7, ADR 0017): the last good answer of the service in one small
 * file, so the list works offline and after a restart.
 *
 * - **Written atomically.** The text goes to a temporary file next to it, is forced to storage and renamed over the cache
 *   (an atomic move), so a reader, or a restart after a crash at any instant, sees the old file or the whole new one.
 * - **Read strictly.** What is in the file is [PlanCacheFormat]'s text: a header line and the answer, with a checksum over
 *   the body. A missing file, an unreadable one, a truncated or edited one and one that is not a valid answer of the
 *   contract all read as **no cache**: never an exception and never half a list.
 * - **Nothing secret.** The file holds the answer (the rider's planned workouts) and a checksum of the service address. The
 *   token is in no file of this app but the settings.
 *
 * Calls run on [dispatcher], not on the caller's thread. A write that fails throws [IOException]; the repository reports no
 * problem for it, because the cache is a convenience.
 *
 * @param file where the cache is kept, `filesDir/plan/workouts` on the tablet.
 */
class FilePlanCache(
    private val file: File,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PlanCache {
    override suspend fun read(): CachedPlan? =
        withContext(dispatcher) {
            try {
                file
                    .takeIf { it.isFile }
                    ?.readText(Charsets.UTF_8)
                    ?.let(PlanCacheFormat::decode)
            } catch (e: IOException) {
                null
            } catch (e: SecurityException) {
                null
            }
        }

    override suspend fun write(plan: CachedPlan) {
        val bytes = PlanCacheFormat.encode(plan).encodeToByteArray()
        withContext(dispatcher) {
            val parent = file.absoluteFile.parentFile
            parent?.mkdirs()
            val temporary = File(parent, file.name + TMP)
            try {
                FileOutputStream(temporary).use { stream ->
                    stream.write(bytes)
                    stream.fd.sync()
                }
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (e: IOException) {
                temporary.delete()
                throw e
            }
        }
    }

    private companion object {
        const val TMP = ".tmp"
    }
}
