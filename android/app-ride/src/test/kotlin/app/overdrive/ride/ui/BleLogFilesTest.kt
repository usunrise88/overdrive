package app.overdrive.ride.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import app.overdrive.ble.log.BleLogArchiveResult
import app.overdrive.ble.log.BleLogSummary
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDateTime
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * Sharing the raw Bluetooth logs (ADR 0010): one chooser over **one zip** of the log files, readable by the receiver and
 * only that file, named by date and time; nothing leaves the tablet by itself. A plain Application, as in [RideFilesTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BleLogFilesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val logs = File(context.filesDir, "ble-logs")
    private val names =
        listOf("ble-20261118T180412Z-trainer.txt", "ble-20261118T180412Z-strap.txt", "ble-20261119T070000Z-trainer.txt")

    @Before
    fun putLogsInTheFolder() {
        val cache = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
        (cache.get(null) as MutableMap<*, *>).clear()
        logs.mkdirs()
        for (name in names) File(logs, name).writeText("# overdrive-ble-session 1\n# model D500\n")
    }

    @After
    fun cleanUp() {
        logs.deleteRecursively()
        File(context.cacheDir, "ble-log-archives").deleteRecursively()
        File(context.filesDir, "elsewhere.txt").delete()
    }

    private fun files() = names.map { File(logs, it) }

    private val now = LocalDateTime.of(2026, 11, 21, 7, 45)

    private fun sendIntent(chooser: Intent): Intent =
        @Suppress("DEPRECATION")
        chooser.getParcelableExtra(Intent.EXTRA_INTENT)!!

    private fun readyChooser(prepared: PreparedShare): Intent = (prepared as PreparedShare.Ready).chooser

    @Test
    fun theLogsGoAsOneZipNamedByTheDateAndTheTime() {
        val chooser = readyChooser(prepareShare(context, files(), now))
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = sendIntent(chooser)
        assertEquals("one file, not several", Intent.ACTION_SEND, send.action)
        assertEquals("application/zip", send.type)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals("only reading is granted", 0, send.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        @Suppress("DEPRECATION")
        val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals("content", uri.scheme)
        assertEquals("app.overdrive.ride.files", uri.authority)
        assertEquals(uri, send.clipData!!.getItemAt(0).uri)
        assertEquals(1, send.clipData!!.itemCount)
        val shown =
            context.contentResolver.query(uri, null, null, null, null)!!.use { cursor ->
                assertTrue(cursor.moveToFirst())
                cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            }
        assertEquals("overdrive-ble-logs-20261121-0745.zip", shown)
    }

    @Test
    fun theZipIsInTheCacheFolderAndHoldsTheLogsUnderTheirOwnNames() {
        prepareShare(context, files(), now)
        val archive = File(context.cacheDir, "ble-log-archives/overdrive-ble-logs-20261121-0745.zip")
        assertTrue(archive.exists())
        ZipFile(archive).use { zip ->
            assertEquals(names.sortedDescending(), zip.entries().asSequence().map { it.name }.toList())
            assertEquals(
                "# overdrive-ble-session 1\n# model D500\n",
                zip.getInputStream(zip.getEntry(names[0])).use { String(it.readBytes()) },
            )
        }
    }

    @Test
    fun theReceiverReadsTheZipAndSeesNothingButItsName() {
        val send = sendIntent(readyChooser(prepareShare(context, files(), now)))

        @Suppress("DEPRECATION")
        val uri = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        ZipInputStream(bytes.inputStream()).use { zip ->
            assertEquals(names.sortedDescending(), generateSequence { zip.nextEntry }.map { it.name }.toList())
        }
        assertFalse(
            "no path of the tablet in what the receiver sees",
            uri.toString().contains(context.cacheDir.absolutePath),
        )
    }

    @Test
    fun theTextSaysHowManySessionsWentAndThatTheyHoldHeartRate() {
        val text = send(prepareShare(context, files(), now)).getStringExtra(Intent.EXTRA_TEXT)!!
        assertEquals(
            "Overdrive Bluetooth logs: 3 sessions.\n" +
                "These logs hold heart-rate values. They are never uploaded; they leave the tablet only when you send them.",
            text,
        )
    }

    private fun send(prepared: PreparedShare) = sendIntent(readyChooser(prepared))

    @Test
    fun theShareTextCountsWhatWasLeftOutAndWhatCouldNotBeRead() {
        assertEquals(
            "Overdrive Bluetooth logs: 1 session.\n${BleLogShare.PRIVACY}",
            BleLogShare.shareText(BleLogArchiveResult(1, 0, 0, 100)),
        )
        assertEquals(
            "Overdrive Bluetooth logs: 12 sessions. 3 older sessions left out to keep the file small. " +
                "1 file could not be read.\n${BleLogShare.PRIVACY}",
            BleLogShare.shareText(BleLogArchiveResult(12, 3, 1, 100)),
        )
        assertEquals(
            "Overdrive Bluetooth logs: 5 sessions. 1 older session left out to keep the file small.\n${BleLogShare.PRIVACY}",
            BleLogShare.shareText(BleLogArchiveResult(5, 1, 0, 100)),
        )
    }

    @Test
    fun theArchiveIsNamedWithTheLocalDateAndTime() {
        assertEquals("overdrive-ble-logs-20261121-0745.zip", BleLogShare.archiveName(now))
        assertEquals(
            "overdrive-ble-logs-20270102-2359.zip",
            BleLogShare.archiveName(LocalDateTime.of(2027, 1, 2, 23, 59)),
        )
    }

    @Test
    fun aNewShareDeletesTheZipOfTheLastOne() {
        prepareShare(context, files(), now)
        prepareShare(context, files(), now.plusMinutes(5))
        val left = File(context.cacheDir, "ble-log-archives").listFiles()!!.map { it.name }
        assertEquals(listOf("overdrive-ble-logs-20261121-0750.zip"), left)
    }

    @Test
    fun theLeftoversAreDeletedAtTheNextLaunch() {
        prepareShare(context, files(), now)
        assertEquals(1, BleLogShare.archiveFolder(context).listFiles()!!.size)
        BleLogShare.clearArchives(context)
        assertEquals(0, BleLogShare.archiveFolder(context).listFiles()!!.size)
        BleLogShare.clearArchives(context) // and nothing to delete is fine
    }

    @Test
    fun aFileOutsideTheCacheArchiveFolderIsRefused() {
        val outside = File(context.filesDir, "elsewhere.txt").also { it.writeText("x") }
        try {
            BleLogShare.chooser(context, outside, "text")
            fail("the provider must serve its folders only")
        } catch (expected: IllegalArgumentException) {
            // prepareShare turns this into "The files could not be shared."
        }
        val other = File(context.cacheDir, "other.zip").also { it.writeText("x") }
        try {
            BleLogShare.chooser(context, other, "text")
            fail("only the archive folder of the cache is served")
        } catch (expected: IllegalArgumentException) {
            // as it should
        } finally {
            other.delete()
        }
    }

    @Test
    fun sharingWithNoLogsSaysSoAndSharesNothing() {
        assertEquals(
            "There are no logs yet. Ride once with the trainer connected.",
            shareOrExplain(context, emptyList(), now),
        )
        assertNull(shadowOf(context as Application).peekNextStartedActivity())
        assertFalse("no zip was made", File(context.cacheDir, "ble-log-archives").listFiles().orEmpty().isNotEmpty())
    }

    @Test
    fun logsThatCannotBeReadShareNothingAndLeaveNoZipBehind() {
        val broken = File(logs, "ble-20261120T093000Z-click.txt").also { it.mkdirs() }
        assertEquals("The log files could not be read.", shareOrExplain(context, listOf(broken), now))
        assertFalse(File(context.cacheDir, "ble-log-archives").listFiles().orEmpty().isNotEmpty())
        assertNull(shadowOf(context as Application).peekNextStartedActivity())
    }

    @Test
    fun aDamagedFileIsLeftOutAndCountedInTheText() {
        val broken = File(logs, "ble-20261120T093000Z-click.txt").also { it.mkdirs() }
        val text = send(prepareShare(context, files() + broken, now)).getStringExtra(Intent.EXTRA_TEXT)!!
        assertTrue(text, text.startsWith("Overdrive Bluetooth logs: 3 sessions. 1 file could not be read."))
    }

    @Test
    fun sharingOpensTheChooserAndSaysNothingWhenItWorks() {
        // The screen calls it with its activity: a context that is not one needs a flag Android insists on.
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        assertNull(shareOrExplain(activity, files(), now))
        val started = shadowOf(activity).nextStartedActivity
        assertNotNull(started)
        assertEquals(Intent.ACTION_CHOOSER, started.action)
    }

    @Test
    fun theSummaryIsInPlainWords() {
        assertEquals("No logs yet", BleLogSummary.None.describe())
        assertEquals("1 file · 312 B", BleLogSummary(1, 312).describe())
        assertEquals("4 files · 120 KB", BleLogSummary(4, 120 * 1024L + 17).describe())
        assertEquals("7 files · 2.5 MB", BleLogSummary(7, (2.5 * 1024 * 1024).toLong()).describe())
    }
}
