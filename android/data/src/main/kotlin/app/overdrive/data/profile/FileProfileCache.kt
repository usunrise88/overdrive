package app.overdrive.data.profile

import app.overdrive.core.profile.CachedProfile
import app.overdrive.core.profile.ProfileCache
import app.overdrive.core.profile.ProfileCacheFormat
import app.overdrive.data.files.AtomicTextFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.io.IOException

/**
 * The rider's profile on the tablet's disk (spec §8.3, ADR 0021): the service's last answer and the one edit the rider made on
 * the tablet that the service has not taken yet, in one small file, so the profile works offline and after a restart.
 *
 * - **Written atomically**, like the plan's cache ([AtomicTextFile]): a crash at any instant leaves the old file or the whole new one.
 * - **Read strictly.** What is in the file is [ProfileCacheFormat]'s text: a header line with a checksum, the profile and the
 *   edit. A missing file, an unreadable one, a truncated or edited one, a profile or an edit that is not valid by the schema all
 *   read as **no cache**: never an exception and never half a profile.
 * - **Nothing secret.** The file holds the profile (the rider's FTP and body constants) and a checksum of the service address. The
 *   token is in no file of this app but the settings. A rider's body and zones are the owner's, so the file is in the app's private
 *   folder, which nothing outside the app reads and the system does not back up.
 *
 * Calls run on [dispatcher], not on the caller's thread. A write that fails throws [IOException]; the repository goes on, and the
 * next change tries again.
 *
 * @param file where the cache is kept, `filesDir/profile/profile` on the tablet.
 */
class FileProfileCache(
    file: File,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ProfileCache {
    private val text = AtomicTextFile(file, dispatcher)

    override suspend fun read(): CachedProfile? = text.read()?.let(ProfileCacheFormat::decode)

    override suspend fun write(profile: CachedProfile) = text.write(ProfileCacheFormat.encode(profile))
}
